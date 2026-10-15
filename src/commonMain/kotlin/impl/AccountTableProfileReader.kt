package impl

import model.AccountStatus
import neton.database.api.DbContext
import port.AccountProfileReader
import port.AccountPublicProfile

/**
 * [AccountProfileReader] 的默认实现：从本模块的 `account_users` 一次批量读取。
 *
 * 一次调用只发一条 `IN` 查询（超过 [CHUNK] 个 id 时按块拆分，避免参数个数失控）。
 * 软删除的行和状态为「已注销」的账号返回 `deleted = true`，不带昵称、头像。
 */
class AccountTableProfileReader(private val db: DbContext) : AccountProfileReader {

    override suspend fun profiles(ids: Collection<Long>): Map<Long, AccountPublicProfile> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, AccountPublicProfile>(distinct.size)
        distinct.chunked(CHUNK).forEach { chunk ->
            val placeholders = chunk.indices.joinToString(", ") { ":p$it" }
            val params = chunk.mapIndexed { i, id -> "p$i" to id }.toMap()
            db.fetchAll(
                "SELECT id, nickname, avatar, status, deleted FROM account_users WHERE id IN ($placeholders)",
                params,
            ).forEach { row ->
                val id = row.long("id")
                val deleted = (row.intOrNull("deleted") ?: 0) != 0 || row.intOrNull("status") == AccountStatus.DELETED
                result[id] = if (deleted) {
                    AccountPublicProfile(id = id, nickname = null, avatar = null, deleted = true)
                } else {
                    AccountPublicProfile(
                        id = id,
                        nickname = row.stringOrNull("nickname")?.takeIf { it.isNotBlank() },
                        avatar = row.stringOrNull("avatar")?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        return result
    }

    companion object {
        const val CHUNK = 500
    }
}
