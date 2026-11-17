package com.crosshospital.imaging.service.routing;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.crosshospital.imaging.domain.FetchTask;
import com.crosshospital.imaging.domain.RetrieveResult;
import com.crosshospital.imaging.domain.StoreResult;
import com.crosshospital.imaging.domain.StudyRef;
import com.crosshospital.imaging.domain.TaskStatus;
import com.crosshospital.imaging.exception.RoutingFailureException;
import com.crosshospital.imaging.service.task.FetchTaskStepExecutor;
import com.crosshospital.imaging.service.task.FetchTaskStepOutcome;
import com.crosshospital.imaging.service.task.InFlightImageBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 状态 → 协议子步的落地实现：把五态机的每一次推进翻译成一次真正的 DICOM 调用。
 *
 * <table border="1">
 *   <caption>状态与协议子步的对应</caption>
 *   <tr><th>状态</th><th>子步</th><th>产出</th></tr>
 *   <tr><td>{@code querying}</td><td>C-FIND（查外院 STUDY 列表）</td>
 *       <td>协商出的 SOP Class、实例数</td></tr>
 *   <tr><td>{@code pulling}</td><td>C-MOVE（把对象拉回本端 C-STORE-SCP）</td>
 *       <td>拉回的字节流（暂存在途区）、实例数、字节数</td></tr>
 *   <tr><td>{@code stored}</td><td>C-STORE（把暂存的字节写进本院 PACS）</td>
 *       <td>写入本院 PACS 的实例数、字节数</td></tr>
 * </table>
 *
 * <p><b>为什么走重试装饰器</b>：{@link DicomRoutingRetryDecorator} 是 task 16 落的
 * 「失败只有一个出口」那一层 —— 退避重试、终局失败落审计 + 进死信 + 推任务终态。
 * 本类不自己写重试循环，否则会出现两套退避参数、两套失败记账。
 *
 * <p><b>为什么拉回要加锁</b>：C-MOVE 的落点是本端那一个 C-STORE-SCP（单例），
 * 它收到的字节先进同一个 sink。两次拉回并发时，第二次会 {@code sink.clear()} 掉第一次的，
 * 导致张冠李戴。本类用一把锁把「拉回 + 快照」串行化，
 * 拉回完立刻把字节复制进在途暂存区，锁外的 C-STORE 不再碰 sink。
 *
 * <p>源主路径证据：{@code github_ref/dcm4che/dcm4che-net/.../Connection.java:1034-1091}
 * 的 {@code connect + checkCompatible} 与 README「Utilities」的
 * {@code findscu / movescu / storescu} 三件套 —— 本类把三件套按状态串成一条流水线。
 */
@Component
@Profile("!test")
public class RoutingStepExecutor implements FetchTaskStepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingStepExecutor.class);

    private final DicomRoutingRetryDecorator routing;
    private final MoveDestinationStoreScp moveDestination;
    private final InFlightImageBuffer buffer;
    private final ReentrantLock sinkLock = new ReentrantLock();

    public RoutingStepExecutor(DicomRoutingRetryDecorator routing,
                               MoveDestinationStoreScp moveDestination,
                               InFlightImageBuffer buffer) {
        if (routing == null || moveDestination == null || buffer == null) {
            throw new IllegalArgumentException("RoutingStepExecutor 依赖不能为 null");
        }
        this.routing = routing;
        this.moveDestination = moveDestination;
        this.buffer = buffer;
    }

    @Override
    public boolean supports(TaskStatus status) {
        return status == TaskStatus.QUERYING
                || status == TaskStatus.PULLING
                || status == TaskStatus.STORED;
    }

    @Override
    public FetchTaskStepOutcome execute(UUID taskId, FetchTask task, TaskStatus targetStatus) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        return switch (targetStatus) {
            case QUERYING -> runQuery(taskId, task);
            case PULLING -> runRetrieve(taskId, task);
            case STORED -> runStore(taskId, task);
            default -> FetchTaskStepOutcome.none();
        };
    }

    /** 子步 1：C-FIND —— 确认外院确实有这次检查，并把元数据带回来。 */
    private FetchTaskStepOutcome runQuery(UUID taskId, FetchTask task) {
        List<StudyRef> refs = routing.queryStudies(
                task.getPeerAet(),
                task.getPatientMpi(),
                task.getAccessionNo(),
                task.getStudyUid(),
                taskId == null ? null : taskId.toString(),
                "system");

        if (refs == null || refs.isEmpty()) {
            // 「外院没有这次检查」不是协议错误，是业务事实 ——
            // 用一个带失败码的异常把它交给统一失败出口，而不是静默当成查到了 0 条。
            throw new RoutingFailureException(
                    RoutingFailureException.FailureCode.NO_STUDY_FOUND,
                    "外院 " + task.getPeerAet() + " 没有患者 " + task.getPatientMpi()
                            + " 的检查记录（影像引用 " + task.getStudyUid() + "）",
                    task.getPeerAet(), task.getPatientMpi());
        }

        StudyRef first = refs.get(0);
        Integer instanceCount = null;
        for (StudyRef ref : refs) {
            if (ref.getNumberOfInstances() != null) {
                instanceCount = (instanceCount == null ? 0 : instanceCount) + ref.getNumberOfInstances();
            }
        }
        LOG.info("C-FIND 完成：taskId={} peer={} 命中 {} 个 study sop_class={}",
                taskId, task.getPeerAet(), refs.size(), first.getSopClassUid());
        return new FetchTaskStepOutcome(first.getSopClassUid(), instanceCount, null, null);
    }

    /** 子步 2：C-MOVE —— 把外院的 DICOM 对象拉回本端，暂存在在途区。 */
    private FetchTaskStepOutcome runRetrieve(UUID taskId, FetchTask task) {
        RetrieveResult result;
        sinkLock.lock();
        try {
            result = routing.retrieveStudy(
                    task.getPeerAet(),
                    task.getStudyUid(),
                    moveDestination.getSink(),
                    task.getPatientMpi(),
                    taskId == null ? null : taskId.toString(),
                    "system");
            // 立刻把字节复制进在途区：锁一放，sink 就可能被下一次拉回清空
            buffer.put(taskId, toBuffers(result));
        } finally {
            sinkLock.unlock();
        }

        if (!result.isNonEmpty()) {
            throw new RoutingFailureException(
                    RoutingFailureException.FailureCode.PARTIAL_FAILURE,
                    "C-MOVE 未拉回任何影像：peer=" + task.getPeerAet()
                            + " study=" + task.getStudyUid()
                            + "（失败 sub-op " + result.getFailedCount() + " 个）",
                    task.getPeerAet(), task.getPatientMpi());
        }
        LOG.info("C-MOVE 完成：taskId={} peer={} 拉回 {} 个 instance 共 {} 字节",
                taskId, task.getPeerAet(), result.getInstanceCount(), result.getTotalBytes());
        return new FetchTaskStepOutcome(null, result.getInstanceCount(),
                result.getTotalBytes(), result.getDurationMs());
    }

    /** 子步 3：C-STORE —— 把在途区的字节写进本院 PACS。 */
    private FetchTaskStepOutcome runStore(UUID taskId, FetchTask task) {
        List<ByteBuffer> instances = buffer.take(taskId);
        if (instances.isEmpty()) {
            throw new RoutingFailureException(
                    RoutingFailureException.FailureCode.STORE_FAILED,
                    "在途暂存区没有这次调阅拉回的影像（task=" + taskId
                            + "），不能写入本院 PACS",
                    task.getPeerAet(), task.getPatientMpi());
        }
        StoreResult stored = routing.storeToLocal(
                task.getPeerAet(),
                task.getStudyUid(),
                instances,
                task.getPeerAet(),
                task.getPatientMpi(),
                taskId == null ? null : taskId.toString(),
                "system");

        if (!stored.isTerminalSuccess()) {
            throw new RoutingFailureException(
                    RoutingFailureException.FailureCode.PARTIAL_FAILURE,
                    "写入本院 PACS 未完全成功：成功 " + stored.getSopInstanceCount()
                            + " / 失败 " + stored.getFailedCount()
                            + "（目标 " + stored.getTargetPacsAet() + "）",
                    task.getPeerAet(), task.getPatientMpi());
        }
        LOG.info("C-STORE 完成：taskId={} 写入本院 PACS {} 共 {} 个 instance",
                taskId, stored.getTargetPacsAet(), stored.getSopInstanceCount());
        return new FetchTaskStepOutcome(null, stored.getSopInstanceCount(),
                stored.getTotalBytes(), stored.getDurationMs());
    }

    /** {@code byte[]} → {@code ByteBuffer}（在途区统一按 ByteBuffer 存）。 */
    private static List<ByteBuffer> toBuffers(RetrieveResult result) {
        List<ByteBuffer> out = new ArrayList<>(result.getInstanceCount());
        for (byte[] bytes : result.getInstances()) {
            out.add(ByteBuffer.wrap(bytes));
        }
        return out;
    }
}
