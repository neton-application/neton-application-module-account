package setting

import model.SystemSetting
import neton.database.dsl.*
import table.SystemSettingTable

/**
 * account 模块的全局配置定义（SYSTEM_CONFIG_SPEC）。
 *
 * 配置归 `module-system`，而 account 本来就依赖 system，所以这里直接用类型化的
 * [SettingDefinition]，读取方拿定义去读即可 —— 不再需要中间的端口与适配器。
 */
object AccountSettingKeys {

    const val CATEGORY = "account"


    /**
     * 是否只允许同时在一台设备上登录。
     *
     * 开启后，换设备登录会顶掉上一台（自增 session_version，旧刷新令牌立即失效）。
     * 默认关闭：这条会改变登录语义，不该由框架替各个产品做主 —— 会员制内容站
     * 需要它防账号共享，而多端同时在线本来就是聊天类产品的正常形态。
     *
     * 注意生效时机：旧设备的**访问令牌**要到过期才真正失效（见
     * `BuiltinAccountIdentityAdapter.ACCESS_TTL_SECONDS`），刷新令牌是立即失效的。
     * 要缩短这段重叠窗口就调短访问令牌有效期。
     */
    val SINGLE_DEVICE_LOGIN: SettingDefinition<Boolean> = SettingDefinition.boolean(
        category = CATEGORY,
        key = "account.login.single_device",
        default = false,
        name = "只允许单设备登录",
        description = "开启后，在新设备登录会顶掉上一台设备的登录状态。用于防止账号共享。",
    )

    /** 交给装配层聚合进 `SettingDefinitionRegistry`。 */
    val definitions: List<SettingDefinition<*>> = listOf(
        SINGLE_DEVICE_LOGIN,
    )
}

/**
 * 按定义直读当前值；缺失或解析失败一律回退定义默认值，所以返回非空。
 *
 * 走表而不是注入 `SystemSettingLogic`：读取点在 [impl.BuiltinAccountIdentityAdapter] 里，
 * 而它由装配层构造，拿不到 system 的 Logic。语义与 `SystemSettingLogic.get` 一致。
 */
suspend fun SettingDefinition<Boolean>.currentValue(): Boolean =
    SystemSettingTable.oneWhere { SystemSetting::settingKey eq key }
        ?.value?.let { parse(it) }
        ?: default
