package logic

import kotlin.time.Clock
import model.Member
import neton.database.api.DbContext

/**
 * Caller-provided id 写入 `member_users`，绕过 [SqlxTableAdapter.insert] 的 `id` 剥离。
 *
 * 框架默认 `insert(entity)` 会 `filterKeys { it != "id" }` 让 DB 用 BIGSERIAL 自增；
 * privchat fork 要求 `member_users.id = privchat-server 分配的 uid`（spec UPSTREAM §5、
 * ACCOUNT_IDENTITY §4.1），所以这里走原生 INSERT。`created_at` / `updated_at` 与
 * `mergeAutoFillForInsert` 行为对齐（epoch millis）。
 *
 * 走 [DbContext.execute] 而不是 sqlx4k 全局库：前者会拿当前协程里的事务会话，
 * 调用方把它包进 `db.transaction {}` 时失败能一起回滚。直接用 `SqlxDatabase.require()`
 * 绕开了会话 —— 实测客服进线 10 路并发时顾客行回滚了、guest 却落了 10 行。
 *
 * 仅 `MemberAuthLogic` 自动注册分支与 `MemberGuestLogic` 调用，作为 fork-divergent 代码集中在一处。
 */
internal suspend fun insertMemberWithProvidedId(db: DbContext, member: Member): Member {
    val now = Clock.System.now().toEpochMilliseconds()
    val sql = """
        INSERT INTO member_users (
            id, mobile, password, username, username_updated_at, nickname, avatar, status,
            level_id, experience, point, group_id,
            register_ip, login_ip, login_date, deleted, is_guest,
            created_at, updated_at
        ) VALUES (
            :id, :mobile, :password, :username, :usernameUpdatedAt, :nickname, :avatar, :status,
            :levelId, :experience, :point, :groupId,
            :registerIp, :loginIp, :loginDate, :deleted, :isGuest,
            :createdAt, :updatedAt
        )
    """.trimIndent()
    db.execute(
        sql,
        mapOf(
            "id" to member.id,
            "mobile" to member.mobile,
            "password" to member.password,
            "username" to member.username,
            "usernameUpdatedAt" to member.usernameUpdatedAt,
            "nickname" to member.nickname,
            "avatar" to member.avatar,
            "status" to member.status,
            "levelId" to member.levelId,
            "experience" to member.experience,
            "point" to member.point,
            "groupId" to member.groupId,
            "registerIp" to member.registerIp,
            "loginIp" to member.loginIp,
            "loginDate" to member.loginDate,
            "deleted" to member.deleted,
            "isGuest" to member.isGuest,
            "createdAt" to now,
            "updatedAt" to now,
        ),
    )
    return member.copy(createdAt = now, updatedAt = now)
}
