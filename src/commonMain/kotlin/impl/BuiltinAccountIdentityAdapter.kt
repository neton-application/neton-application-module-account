package impl

import model.Account
import model.AccountStatus
import security.SessionRevocation
import security.TokenRealm
import table.AccountTable
import port.CreateMemberAccountCommand
import port.IssueMemberTokenCommand
import port.AccountRef
import port.AccountIdentityAdapter
import port.AccountIdentityProvider
import port.AccountPasswordChangedEvent
import port.AccountTokenBundle
import port.RefreshMemberTokenCommand
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.database.api.DbContext
import neton.database.dsl.*
import setting.AccountSettingKeys
import setting.currentValue
import neton.security.identity.UserId
import neton.security.jwt.JwtAuthenticator
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 内置身份后端（自托管账号，无外部 server）。
 *
 * - 账号：按 mobile/username 命中 account_users 复用 id，否则取数据库序列
 *   `account_users_id_seq` 的下一个值（普通自增主键，JS 安全的小整数）。
 * - token：复用应用的 [JwtAuthenticator]（HS256 + security.jwt.secretKey），与 admin token
 *   同一套签验，`/app` 路由组直接验通，`Identity.id = sub`。
 * - 域：token 带 `realm=account`，只能用在账号域的路由组；管理员域不收（见 system 的 TokenRealm）。
 * - 会话：token 携带 `sid`(session_version)/`did`(device)。刷新和每次访问都校验账号状态与
 *   会话版本，改密、注销、全端退出 bump session_version 后，已签发的 access/refresh 立即失效。
 *
 * privchat 模式由 PrivchatMemberIdentityAdapter override（走 privchat-server）。
 */
class BuiltinAccountIdentityAdapter(
    private val jwt: JwtAuthenticator,
    private val db: DbContext,
    private val revocation: SessionRevocation = SessionRevocation(null),
) : AccountIdentityAdapter {

    companion object {
        const val ACCESS_TTL_SECONDS = 7200L          // 2h

        /**
         * refresh token TTL：一年。
         *
         * [buildBundle] 每次续期都重新签发 refresh token，所以这是个**滑动窗口**——它真正
         * 决定的是「连续多久不打开 App 会被登出」，不是「登录后多久必须重登」。
         *
         * 之前是 30 天，意思是一个月没打开的用户下次打开被踢回登录页。被踢掉的不是攻击者，
         * 是那些本来还会回来的用户；而重新登录要走验证码，相当一部分人就此不回来了。
         * 微信/Telegram 的会话基本是「除非主动吊销否则不过期」，一年已经接近那个体感。
         *
         * 安全性不靠这个 TTL 兜底：[onPasswordChanged] 会原子自增 `sessionVersion`，
         * 而 [refreshToken] 校验 `sid == sessionVersion`——改密码一次作废该用户全部会话，
         * 且立刻生效。那才是吊销开关，比等 token 自然过期快得多。
         */
        const val REFRESH_TTL_SECONDS = 31536000L     // 365d
        private val SCOPE = listOf("user")
        private const val ISSUER = "yese-builtin"

        /** 账号域的 realm 值，见 [security.AccountTokenRealm]。 */
        const val REALM = "account"
    }

    override suspend fun createOrBindAccount(command: CreateMemberAccountCommand): AccountRef {
        val mobile = command.mobile?.takeIf { it.isNotBlank() }
        val username = command.username?.takeIf { it.isNotBlank() }
        val existing = when {
            mobile != null -> AccountTable.oneWhere { Account::mobile eq mobile }
            username != null -> AccountTable.oneWhere { Account::username eq username }
            else -> null
        }
        if (existing != null) {
            return AccountRef(AccountIdentityProvider.BUILTIN, existing.id, created = false)
        }
        return AccountRef(AccountIdentityProvider.BUILTIN, nextMemberId(), created = true)
    }

    /** 数据库序列取下一个主键（自增小整数，JS 安全）。 */
    private suspend fun nextMemberId(): Long =
        db.fetchAll("SELECT nextval('account_users_id_seq') AS id")
            .first().long("id")

    override suspend fun issueToken(command: IssueMemberTokenCommand): AccountTokenBundle {
        val deviceId = command.deviceId?.takeIf { it.isNotBlank() } ?: mintDeviceId()
        val member = AccountTable.get(command.memberId)
        var sessionVersion = member?.sessionVersion ?: 0L

        if (AccountSettingKeys.SINGLE_DEVICE_LOGIN.currentValue()) {
            val previous = member?.currentDeviceId
            if (previous != null && previous != deviceId) {
                // 换设备登录顶掉上一台：自增而不是读后写，并发登录才不会互相覆盖。
                // 自增后必须回读真实值 —— 本地 +1 在并发下会算出一个别人已经用掉的
                // 版本号，签出的 token 一刷新就失效。
                AccountTable.query { where { Account::id eq command.memberId } }
                    .update { increment(Account::sessionVersion) }
                sessionVersion = AccountTable.get(command.memberId)?.sessionVersion ?: (sessionVersion + 1)
            }
            if (member != null && previous != deviceId) {
                AccountTable.update(member.copy(sessionVersion = sessionVersion, currentDeviceId = deviceId))
            }
        }

        return buildBundle(command.memberId, deviceId, sessionVersion, deviceCreated = command.deviceId.isNullOrBlank(), sessionId = SessionRevocation.newId())
    }

    /**
     * 刷新令牌。失效原因（签名坏 / 类型不对 / 主体缺失 / session_version 已 bump）在语义上
     * 都是 **401 需要重新登录**。此前抛裸 [IllegalArgumentException] → HTTP 500，客户端
     * 不会清理会话、不会重新登录，只会带着废 token 继续重试。
     *
     * 注意：只把**已识别**的失效原因映射成 401，其它异常照旧上抛为 500，
     * 不做「IllegalArgumentException 一律 400/401」的全局降级（会掩盖真实故障）。
     */
    override suspend fun refreshToken(command: RefreshMemberTokenCommand): AccountTokenBundle {
        val verified = try {
            jwt.verifyToken(command.refreshToken)
        } catch (e: Exception) {
            throw HttpException(NetonErrorCode.INVALID_TOKEN, "INVALID_REFRESH_TOKEN")
        }
        // 管理员令牌和本域令牌用同一把密钥签，域不对就不是给这里用的（评审 R01）。
        if (verified.claimString(TokenRealm.CLAIM) != REALM) {
            throw HttpException(NetonErrorCode.INVALID_TOKEN, "INVALID_REFRESH_TOKEN")
        }
        if (verified.claimString(TokenRealm.TYPE_CLAIM) != TokenRealm.TYPE_REFRESH) {
            throw HttpException(NetonErrorCode.INVALID_TOKEN, "NOT_A_REFRESH_TOKEN")
        }
        val memberId = verified.claimString("sub")?.toLongOrNull()
            ?: throw HttpException(NetonErrorCode.INVALID_TOKEN, "INVALID_REFRESH_TOKEN_SUBJECT")
        val account = AccountTable.get(memberId)
        val tokenSid = verified.claimString("sid")?.toLongOrNull()
        // 被禁用、已注销的账号同样不能续期：缺这一条时，禁用只挡住了登录，已登录的人照样续命一年。
        if (account == null || account.status != AccountStatus.NORMAL || tokenSid != account.sessionVersion) {
            throw HttpException(NetonErrorCode.REFRESH_TOKEN_EXPIRED, "REFRESH_TOKEN_EXPIRED")
        }
        val sessionVersion = account.sessionVersion
        val sessionId = verified.claimString(SessionRevocation.SESSION_CLAIM)
            ?: throw HttpException(NetonErrorCode.REFRESH_TOKEN_EXPIRED, "REFRESH_TOKEN_EXPIRED")
        if (revocation.isRevoked(REALM, sessionId)) {
            throw HttpException(NetonErrorCode.REFRESH_TOKEN_REVOKED, "REFRESH_TOKEN_REVOKED")
        }
        // 刷新令牌只能换一次：并发窗口外的重复使用说明它被复制了，整个会话作废（评审 R04）。
        val jti = verified.claimString(SessionRevocation.TOKEN_ID_CLAIM)
            ?: throw HttpException(NetonErrorCode.REFRESH_TOKEN_EXPIRED, "REFRESH_TOKEN_EXPIRED")
        val nowSeconds = Clock.System.now().toEpochMilliseconds() / 1000
        if (revocation.claimRefresh(REALM, jti, REFRESH_TTL_SECONDS, nowSeconds) == SessionRevocation.RefreshUse.REUSED) {
            revocation.revokeSession(REALM, sessionId, REFRESH_TTL_SECONDS)
            throw HttpException(NetonErrorCode.REFRESH_TOKEN_REVOKED, "REFRESH_TOKEN_REVOKED")
        }
        val deviceId = command.deviceId?.takeIf { it.isNotBlank() } ?: mintDeviceId()
        return buildBundle(memberId, deviceId, sessionVersion, deviceCreated = false, sessionId = sessionId)
    }

    override suspend fun acceptsAccessToken(memberId: Long, sessionVersion: Long, sessionId: String?): Boolean {
        val account = AccountTable.get(memberId) ?: return false
        if (account.status != AccountStatus.NORMAL || account.sessionVersion != sessionVersion) return false
        return sessionId == null || !revocation.isRevoked(REALM, sessionId)
    }

    /** 只吊销这一次登录；没有 Redis 无法按会话吊销时，退回到作废全部会话。 */
    override suspend fun logoutSession(memberId: Long, sessionId: String?, deviceId: String?) {
        if (sessionId != null && revocation.available) {
            revocation.revokeSession(REALM, sessionId, REFRESH_TTL_SECONDS)
        } else {
            revokeAllSessions(memberId, reason = "logout")
        }
    }

    /** 内置后端的会话就是 session_version：bump 一次，所有已签发的令牌立即失效。 */
    override suspend fun revokeAllSessions(memberId: Long, reason: String) {
        AccountTable.query { where { Account::id eq memberId } }
            .update { increment(Account::sessionVersion) }
    }

    override suspend fun onPasswordChanged(event: AccountPasswordChangedEvent) {
        // 原子自增，不能读出来再写回去：并发改密会互相覆盖，旧 token 就失效不掉
        AccountTable.query { where { Account::id eq event.memberId } }
            .update { increment(Account::sessionVersion) }
    }

    private fun buildBundle(
        memberId: Long, deviceId: String, sessionVersion: Long, deviceCreated: Boolean, sessionId: String,
    ): AccountTokenBundle {
        val uid = UserId(memberId.toULong())
        val access = jwt.createToken(
            userId = uid, expiresInSeconds = ACCESS_TTL_SECONDS,
            extraClaims = mapOf(
                TokenRealm.CLAIM to REALM, TokenRealm.TYPE_CLAIM to TokenRealm.TYPE_ACCESS,
                SessionRevocation.SESSION_CLAIM to sessionId, SessionRevocation.TOKEN_ID_CLAIM to SessionRevocation.newId(),
                "sid" to sessionVersion, "did" to deviceId, "scope" to "user",
            ),
        )
        val refresh = jwt.createToken(
            userId = uid, expiresInSeconds = REFRESH_TTL_SECONDS,
            extraClaims = mapOf(
                TokenRealm.CLAIM to REALM, TokenRealm.TYPE_CLAIM to TokenRealm.TYPE_REFRESH,
                SessionRevocation.SESSION_CLAIM to sessionId, SessionRevocation.TOKEN_ID_CLAIM to SessionRevocation.newId(),
                "sid" to sessionVersion, "did" to deviceId,
            ),
        )
        return AccountTokenBundle(
            userId = memberId, accessToken = access, refreshToken = refresh,
            expiresIn = ACCESS_TTL_SECONDS, refreshExpiresIn = REFRESH_TTL_SECONDS,
            deviceId = deviceId, sessionVersion = sessionVersion, deviceCreated = deviceCreated,
            scope = SCOPE, issuer = ISSUER,
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun mintDeviceId(): String =
        "d-${Clock.System.now().toEpochMilliseconds().toString(36)}-${Random.nextInt(0, 0x1000000).toString(36)}"
}
