package security

import impl.BuiltinAccountIdentityAdapter
import neton.core.interfaces.Identity
import neton.security.jwt.VerifiedJwtToken
import port.AccountIdentityAdapter

/**
 * 账号（终端用户）身份域：`/app` 和 `/manage`（经营者用普通账号登录管理自己的业务）。
 *
 * - 本应用签发的令牌：必须是 access 类型，账号仍可用、会话版本仍是当前版本（评审 R02）。
 *   refresh 令牌有效期一年，不校验类型的话它就是一张一年期的访问凭证。
 * - 外部统一令牌（PrivChat 的 RS256）也接受：账号后端是 privchat 时令牌由 privchat-server 签发，
 *   会话与吊销由框架的 UnifiedTokenAuthenticator（introspect + session version）负责。
 */
class AccountTokenRealm(
    private val adapter: () -> AccountIdentityAdapter,
) : TokenRealm {
    override val name: String = BuiltinAccountIdentityAdapter.REALM
    override val routeGroups: Set<String> = setOf("app", "manage")
    override val acceptsExternalTokens: Boolean = true

    override suspend fun resolve(token: VerifiedJwtToken): Identity? {
        if (token.claimString(TokenRealm.TYPE_CLAIM) != TokenRealm.TYPE_ACCESS) return null
        val memberId = token.identity.userId.value.toLong()
        val sid = token.claimString("sid")?.toLongOrNull() ?: return null
        val sessionId = token.claimString(SessionRevocation.SESSION_CLAIM)
        if (!adapter().acceptsAccessToken(memberId, sid, sessionId)) return null
        return RealmIdentity(
            userId = token.identity.userId,
            roles = token.identity.roles,
            permissions = token.identity.permissions,
            realm = name,
            sessionId = sessionId,
        )
    }
}
