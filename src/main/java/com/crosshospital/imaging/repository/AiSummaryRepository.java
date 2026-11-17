package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.AiSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * AI 介入点结果 Repository（报告归一 / 互认初筛）。
 *
 * <p>同一 task 可有多条不同 kind 的摘要（report_normalize 与
 * feasibility_screener 分别落一行）。
 */
public interface AiSummaryRepository extends JpaRepository<AiSummary, Long> {

    List<AiSummary> findAllByTaskId(UUID taskId);

    List<AiSummary> findAllByTaskIdAndKind(UUID taskId, String kind);

    long countBySource(String source);
}