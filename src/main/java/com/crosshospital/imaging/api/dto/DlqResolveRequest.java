package com.crosshospital.imaging.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 死信处置请求体。
 *
 * <p>处置人（{@code who}）不放在请求体里 —— 走 {@code X-Employee-Id} 请求头，
 * 避免「请求体里写的人」与「审计里记的人」不是同一个（伪造处置人）。
 *
 * <p>请求体整体可省略（等价于只标记已处置、不写说明）。
 */
@Schema(name = "DlqResolveRequest", description = "死信处置请求（说明可选）")
public class DlqResolveRequest {

    @Schema(description = "处置说明（如：已联系外院信息科，对方明日恢复）",
            example = "已联系外院信息科，对方明日恢复 PACS 服务")
    @Size(max = 1000, message = "resolution note must be ≤ 1000 characters")
    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
