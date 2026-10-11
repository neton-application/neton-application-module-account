package port

/**
 * member 账号身份后端的抽象(MEMBER-IDENTITY-ADAPTER v1)。
 *
 * member 业务层只依赖本接口,**不直接依赖 PrivchatServiceClient**。
 * - 内置实现 [impl.BuiltinAccountIdentityAdapter](member 自带);
 * - PrivChat 实现 `PrivchatMemberIdentityAdapter`(由 neton-application-module-privchat 提供,Commit 3)。
 *
 * 资料变更回调默认空实现:builtin 不需要外部同步;privchat override 后同步到 privchat-server。
 *
 * 见 docs/ACCOUNT_IDENTITY_MODE_SPEC.md。
 */
interface AccountIdentityAdapter {

    /** 注册/登录时:产生或绑定底层账号,返回外部账号引用。 */
    suspend fun createOrBindAccount(command: CreateMemberAccountCommand): AccountRef

    /** 签发 token。 */
    suspend fun issueToken(command: IssueMemberTokenCommand): AccountTokenBundle

    /** 刷新 token。 */
    suspend fun refreshToken(command: RefreshMemberTokenCommand): AccountTokenBundle

    /**
     * 撤销该账号的**全部**会话。
     *
     * 🔴 注销账号必须调它，而且它才是权威动作。
     *
     * application 的 `account_users.session_version` 和 privchat 的
     * `privchat_devices.session_version` 是**两个库里的两份数据**：IM 连接鉴权
     * （`verify_device_session`）读的是后者。只 bump 前者，旧 token 照样能连 IM、
     * 照样能刷新出新的 access token——"注销"就只是把登录入口关上，会话还活着。
     *
     * 实现要求：把设备会话置为不可用（不只是 bump 版本号），确保
     *   - 已建立的 IM 连接失效；
     *   - 旧 access token 换不出新 token；
     *   - 旧 token 重连被拒。
     *
     * 默认空实现：builtin 模式没有外部会话存储，账号状态就在本库里。
     */
    suspend fun revokeAllSessions(memberId: Long, reason: String) {}

    /**
     * 本应用签发的账号访问令牌（HS256、realm=account）在每次请求时的会话校验：账号仍可用、
     * 令牌里的会话版本仍是当前版本。改密、注销、全端退出靠 bump 版本号即时作废已签发的令牌。
     *
     * 默认 false：只有自己签发这类令牌的后端（内置适配器）才接受它。外部身份后端（如 privchat）
     * 的令牌由它们自己的验证器和会话体系负责，本应用不会签出 realm=account 的令牌给它们的用户。
     */
    suspend fun acceptsAccessToken(memberId: Long, sessionVersion: Long, sessionId: String?): Boolean = false

    /**
     * 退出当前会话（评审 R04）：只让这一次登录失效，其他设备不受影响。
     * [sessionId] 是本应用令牌的 `ssn`，[deviceId] 是外部令牌（如 PrivChat 统一令牌）的设备。
     * 默认实现无法定位单个会话，只能作废该账号全部会话 —— 宁可多踢，不能退出了令牌还能用。
     */
    suspend fun logoutSession(memberId: Long, sessionId: String?, deviceId: String?) {
        revokeAllSessions(memberId, reason = "logout")
    }

    /** 资料变更后回调(默认空:builtin 无外部同步)。 */
    suspend fun onProfileChanged(event: AccountProfileChangedEvent) {}
    suspend fun onMobileChanged(event: AccountMobileChangedEvent) {}
    suspend fun onPasswordChanged(event: AccountPasswordChangedEvent) {}
}
