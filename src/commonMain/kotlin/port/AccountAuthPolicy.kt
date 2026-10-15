package port

/**
 * 注册策略(MEMBER_INVITE_CODE §5.0)。来源优先级:产品装配层在代码里 bind 的(PrivChat 从
 * privchat.conf 读) > account 自己的 `config/account.conf` `[auth]` 段(见 [init.AccountAuthPolicyConfig])
 * > 默认值。bootstrap `/config/bootstrap` 的 auth 块下发同一份给客户端。
 * `inviteCodeRequired` 只作用于**新注册流程**;存量用户登录不受影响(评审 B)。
 */
data class AccountAuthPolicy(
    val registerModes: List<String> = listOf(MODE_PHONE_SMS),
    val defaultRegisterMode: String = MODE_PHONE_SMS,
    val inviteCodeRequired: Boolean = false,
    /**
     * 邀请码强制的注册时间锚。支持 epoch millis 或 ISO-8601，最终与
     * `account_users.created_at` 的 epoch millis 比较。
     * null = 约束所有未绑定用户(默认);配置后只 gate 该时间之后注册的账号——
     * 用于开启 inviteCodeRequired 时豁免存量用户,不必逐个补种子绑定。
     */
    val inviteCodeRequiredSince: String? = null,
    val nicknameRequired: Boolean = false,
    /**
     * 是否在登录后**提示**绑定手机号。名字沿用 `xxxRequired` 是为了跟同族配置一致，但语义是
     * **推荐不是强制**：下发的 action 带 `required=false`，客户端引导但允许跳过，旧客户端静默
     * 略过（见 [RequiredActionsLogic.computeForUid] 里的原因）。已绑定的用户永不触发。
     *
     * 绑定**不做短信验证**（产品决定）：只查重 + 落库。未验证的号码同样可用于短信登录，
     * 也就是说查重只能保证「一个号对应一个账号」，保证不了「这个号真属于填它的人」。
     */
    val mobileRequired: Boolean = false,
    /** 手机号强制的注册时间锚，语义同 [inviteCodeRequiredSince]。 */
    val mobileRequiredSince: String? = null,
    /**
     * 是否开放**用户自助注销账号**（App Store 5.1.1(v) 要求的应用内删除入口）。
     *
     * 默认 **false**：注销不可逆，是否提供由发行方按合规要求显式打开，不能因为
     * 忘配一行 conf 就让所有用户都能一键删号。bootstrap 的 auth 块下发同一份值，
     * 客户端据此决定是否渲染入口；服务端在 [logic.AccountAuthLogic.deleteOwnAccount]
     * 独立再判一次——入口藏起来不等于路由关掉了。
     */
    val accountDeletionEnabled: Boolean = false,
) {
    companion object {
        const val MODE_PHONE_SMS = "PHONE_SMS"
        const val MODE_USERNAME_PASSWORD = "USERNAME_PASSWORD"
    }
}
