package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

/**
 * 自动昵称生成 — 名词词库。
 *
 * 跟 [AccountNicknameAdjective] 同结构;拆两张表是为了 admin 后台清晰分类
 * (导入 .txt 时按目标 endpoint 自动归类,避免混词)。
 */
@Serializable
@Table("account_nickname_noun")
data class AccountNicknameNoun(
    @Id
    val id: Long = 0,
    val word: String,
    /** 0 禁用 / 1 启用。 */
    val status: Int = 1,
    @CreatedAt
    val createdAt: Long? = null,
    @UpdatedAt
    val updatedAt: Long? = null,
)
