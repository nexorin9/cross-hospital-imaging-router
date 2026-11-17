# 视觉目标卡（结构化 · 无图）

> 本项目运行环境无图像生成能力，仅按结构化视觉目标卡描述。**不伪造图片**。
> Phase 4 收尾会采集真实运行截图到 `docs/screenshots/`，与本卡**诚实对照**。

## 产品形态

跨院影像调阅路由系统是**无界面 HTTP API 服务**（Spring Boot 3 + springdoc-openapi），
用户终端主要是：
1. **信息科**通过 `curl` / Postman / API 平台操作准入表与健康检查；
2. **临床医生**通过 HIS 工单触发调阅，HIS 内部调本服务；
3. **影像科技师**通过 `curl` 查审计 / DLQ；
4. **影像科医生**在 PACS 工作站看影像与 AI 草稿（不在本服务界面内）。

故本服务的「视觉目标」分三类：
- **Swagger UI**（springdoc 默认主题 + 自定义 logo / 标题）
- **CLI 终端输出**（curl JSON 响应、握手 RPC 日志）
- **OpenAPI JSON**（`/v3/api-docs` 返回的 JSON）

## 三个状态

### 起始态（启动后无任何业务）

```
┌─────────────────────────────────────────────────────────┐
│  Swagger UI                                              │
│  ┌──────────┐  ┌──────────────────────────────────────┐ │
│  │ peers    │  │                                       │ │
│  │ fetch    │  │   跨院影像互认调阅路由系统              │ │
│  │ audit    │  │                                       │ │
│  │ dlq      │  │   选择左侧 Tag 查看端点                │ │
│  │ health   │  │                                       │ │
│  └──────────┘  └──────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

- **画面类型**：浏览器界面（Swagger UI）
- **配色**：springdoc 默认浅色主题 + 自定义标题；主色 `#0F172A`（slate-900），辅色 `#1D4ED8`（blue-700）
- **字体**：系统中文栈（`-apple-system, "PingFang SC", "Microsoft YaHei"`），层级 ≥4 级
- **信息层级**：左侧 Tag 列表、右侧选中的端点详情；最显眼的是「选择一个端点」提示

### 首个可用瞬间（一次握手 health RPC 返回 JSON）

```
$ curl -sS -X POST http://127.0.0.1:8080/api/imaging/peers/OUT_PACS/health | jq
{
  "peerAet": "OUT_PACS",
  "compatible": true,
  "commonSopClasses": [
    "1.2.840.10008.5.1.4.1.1.2"
  ],
  "agreedTransferSyntaxes": [
    "1.2.840.10008.1.2.1"
  ],
  "agreedTlsProtocols": [
    "TLSv1.3"
  ],
  "lastError": null,
  "checkedAt": "2026-10-20T06:41:27Z"
}
```

- **画面类型**：终端会话（CLI）
- **配色**：终端默认浅色 / 深色主题；JSON 高亮用 jq 默认色
- **字体**：等宽字体（Menlo / Consolas），`tabular-nums` 对齐数字
- **信息层级**：`compatible`（最显眼）/ `commonSopClasses` / `agreedTransferSyntaxes` / `lastError` / `checkedAt`

### 成形态（一次完整调阅任务五态推进 + AI 草稿回执）

```
$ curl -sS http://127.0.0.1:8080/api/imaging/tasks/<EXAMPLE_TASK_ID> | jq
{
  "taskId": "<EXAMPLE_TASK_ID>",
  "patientMpi": "<EXAMPLE_MPI>",
  "studyUid": "<EXAMPLE_STUDY_UID>",
  "status": "stored",
  "retryCount": 0,
  "failureCode": null,
  "aiSummaryId": 17,
  "createdAt": "2026-10-20T06:41:25Z",
  "updatedAt": "2026-10-20T06:41:31Z"
}

# 同步看 AI 草稿
$ curl -sS http://127.0.0.1:8080/api/imaging/tasks/<taskId>/ai-summary | jq
{
  "taskId": "<EXAMPLE_TASK_ID>",
  "kind": "report_normalize",
  "source": "model",          # 或 "rule_fallback"
  "model": "gpt-4o-mini",
  "payload": {
    "bodyPart": "胸部",
    "findings": "双肺纹理清晰，未见实变",
    "impression": "胸部 CT 平扫未见明显异常",
    "studyItems": ["胸部 CT 平扫"]
  },
  "latencyMs": 482
}
```

- **画面类型**：终端会话（CLI）+ JSON 输出
- **信息层级**：`status`（最显眼，五态机当前值）/ `aiSummaryId`（关联草稿）/ `failureCode`（失败时显眼）/ 时间戳（右对齐）
- **AI 草稿**：草稿明确标注 `source=model` 或 `source=rule_fallback`，模型不可达时回退规则模板

## 视觉语言（design token 集中管理）

| Token | 值 | 用途 |
|-------|----|----|
| 主色 | `#0F172A`（slate-900） | 标题、关键值 |
| 辅色 | `#1D4ED8`（blue-700） | 链接、按钮主操作 |
| 成功 | `#15803D`（green-700） | `status=stored` / `compatible=true` |
| 警告 | `#B45309`（amber-700） | `status=notified` 待人工复核 |
| 危险 | `#B91C1C`（red-700） | `status=failed` / `failure_code` 非空 |
| 中性灰 | `#E5E7EB` `#9CA3AF` `#6B7280` `#374151` | 边框 / 辅助文字 / 正文 |
| 间距 | 4 / 8 / 12 / 16 / 24 / 32（8px 栅格） | JSON 字段缩进、表格内边距 |
| 圆角 | 卡片 8px / 浮层 6px / 模态 12px | springdoc 默认 + 自定义 |
| 阴影 | `0 1px 2px rgba(0,0,0,0.05)` / `0 4px 6px rgba(0,0,0,0.1)` | 卡片 / 浮层 |
| 字体 | `-apple-system, "PingFang SC", "Microsoft YaHei"` | 中文系统栈 |
| 等宽字体 | `Menlo, Consolas, monospace` | JSON / UID 输出 |
| 字号 | 12 / 14 / 16 / 20 / 24 / 32 | 层级 ≥4 级 |

## 必须可达的约束

- Swagger UI 由 **springdoc-openapi** 自动生成，无需手写 HTML / CSS；
- JSON 输出用 **Jackson** 默认配置 + springdoc 的 `@Schema` 注解；
- 终端输出仅依赖 **`curl` + `jq`**（jq 是可选美化器）；
- 不引入前端框架（React / Vue / Angular），无构建工具；
- 不引入 3D / WebGL / Canvas，纯静态布局。

## 与实测对照的诚实说明

实测截图（`docs/screenshots/`）会显示 springdoc 默认 Swagger UI 主题，与本卡的「主色 / 辅色」配色**有差距**。
终端 JSON 输出会显示真实样本 ID（`<EXAMPLE_TASK_ID>` / `<EXAMPLE_STUDY_UID>` / `<EXAMPLE_MPI>` 等占位符），
不出现真实患者信息。
**不**生成「事后效果图」来假装一致。