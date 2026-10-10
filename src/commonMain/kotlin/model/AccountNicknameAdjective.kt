package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

/**
 * 自动昵称生成 — 形容词词库。
 *
 * admin 后台 .txt 导入/导出; 启动时 [NicknameGenerator] 一次性 load 全表
 * (status=1) 到内存做随机选词。
 */
@Serializable
@Table("account_nickname_adjective")
data class AccountNicknameAdjective(
    @Id
    val id: Long = 0,
    val word: String,
    /** 0 禁用 / 1 启用; 禁用的不进生成池, 但保留行供 admin 之后启用。 */
    val status: Int = 1,
    @CreatedAt
    val createdAt: Long? = null,
    @UpdatedAt
    val updatedAt: Long? = null,
)
