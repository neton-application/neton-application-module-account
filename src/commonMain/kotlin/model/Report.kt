package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.SoftDelete
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

/**
 * 用户举报记录（App Store Guideline 1.2 UGC 最小治理链路）。
 *
 * 客户端入口：消息长按举报 / 用户资料页举报用户。落库后由 IM Admin 后台
 * 「举报记录展示」消费（MODULE_IM_ADMIN_SPEC §投诉入口）。
 *
 * target_type：message / user / channel；target_id 对应不同实体的 id（字符串兼容）。
 * reported_uid：被举报用户（冗余，便于 admin 按用户聚合）。
 * reason：举报理由码，见 [enums.ReportReason]。status：0 待处理 / 1 处理中 / 2 已处理 / 3 已驳回。
 */
@Serializable
@Table("account_reports")
data class Report(
    @Id
    val id: Long = 0,
    val reporterUid: Long,
    val targetType: String,
    val targetId: String,
    val reportedUid: Long? = null,
    val reason: Int,
    val description: String? = null,
    val evidenceMessageId: Long? = null,
    val status: Int = 0,
    @SoftDelete
    val deleted: Int = 0,
    @CreatedAt
    val createdAt: Long? = null,
    @UpdatedAt
    val updatedAt: Long? = null,
)
