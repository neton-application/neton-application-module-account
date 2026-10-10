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

    /** 资料变更后回调(默认空:builtin 无外部同步)。 */
    suspend fun onProfileChanged(event: AccountProfileChangedEvent) {}
    suspend fun onMobileChanged(event: AccountMobileChangedEvent) {}
    suspend fun onPasswordChanged(event: AccountPasswordChangedEvent) {}
}
