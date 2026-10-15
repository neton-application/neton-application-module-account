package init

import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import port.AccountAuthPolicy

/**
 * 从 account 自己的模块配置 `config/account.conf` 的 `[auth]` 段读取注册策略。
 *
 * ```toml
 * [auth]
 * register_modes = ["PHONE_SMS", "USERNAME_PASSWORD"]   # 也接受 "PHONE_SMS,USERNAME_PASSWORD"
 * default_register_mode = "USERNAME_PASSWORD"
 * invite_code_required = false
 * invite_code_required_since = "2026-10-01T00:00:00Z"
 * nickname_required = false
 * mobile_required = false
 * mobile_required_since = "..."
 * account_deletion_enabled = false
 * ```
 *
 * 每一项都可省略，省略即 [AccountAuthPolicy] 的默认值；没有文件或没有 `[auth]` 段时返回 null
 * （行为与从前完全一样）。环境覆盖按模块配置的标准规则（`account.{env}.conf`、ENV、CLI）。
 *
 * 产品在代码里绑定的 [AccountAuthPolicy] 优先：bootstrap 只在没人绑定时才用这里的结果。
 * 配置写错（未知注册方式、默认方式不在列表里、布尔值不是 true/false）启动即失败，不悄悄退回默认。
 */
object AccountAuthPolicyConfig {
    private val KNOWN_MODES = setOf(AccountAuthPolicy.MODE_PHONE_SMS, AccountAuthPolicy.MODE_USERNAME_PASSWORD)

    fun load(ctx: NetonContext): AccountAuthPolicy? = from(
        ConfigLoader.loadModuleConfig(
            "account",
            configPath = "config",
            environment = ConfigLoader.resolveEnvironment(ctx.args),
            args = ctx.args,
        ),
    )

    fun from(conf: Map<String, Any?>?): AccountAuthPolicy? {
        if (conf == null || !ConfigLoader.hasConfig(conf, "auth")) return null
        fun str(key: String) = ConfigLoader.getString(conf, "auth.$key")?.trim()?.takeIf { it.isNotEmpty() }
        fun bool(key: String, default: Boolean) =
            if (ConfigLoader.hasConfig(conf, "auth.$key")) ConfigLoader.getBoolean(conf, "auth.$key") else default

        val defaults = AccountAuthPolicy()
        val modes = str("register_modes")?.let(::parseModes) ?: defaults.registerModes
        require(modes.isNotEmpty()) { "account.conf: auth.register_modes must not be empty" }
        modes.forEach { require(it in KNOWN_MODES) { "account.conf: unknown auth.register_modes entry '$it' (known: $KNOWN_MODES)" } }
        val defaultMode = str("default_register_mode")?.uppercase() ?: modes.first()
        require(defaultMode in modes) { "account.conf: auth.default_register_mode '$defaultMode' is not in auth.register_modes $modes" }

        return AccountAuthPolicy(
            registerModes = modes,
            defaultRegisterMode = defaultMode,
            inviteCodeRequired = bool("invite_code_required", defaults.inviteCodeRequired),
            inviteCodeRequiredSince = str("invite_code_required_since"),
            nicknameRequired = bool("nickname_required", defaults.nicknameRequired),
            mobileRequired = bool("mobile_required", defaults.mobileRequired),
            mobileRequiredSince = str("mobile_required_since"),
            accountDeletionEnabled = bool("account_deletion_enabled", defaults.accountDeletionEnabled),
        )
    }

    /** 接受 TOML 行内数组 `["A", "B"]`（解析器按原文给出）或逗号分隔 `"A,B"`。 */
    internal fun parseModes(raw: String): List<String> =
        raw.trim().removePrefix("[").removeSuffix("]")
            .split(',')
            .map { it.trim().trim('"', '\'').trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
}
