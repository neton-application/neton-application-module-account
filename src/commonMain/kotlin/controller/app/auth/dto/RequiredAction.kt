package controller.app.auth.dto

import kotlinx.serialization.Serializable

/**
 * Post-login Required Action（spec PLATFORM_REQUIRED_ACTIONS_CONTRACT §4.1）。
 *
 * 由 server 在 `AccountLoginResponse.requiredActions` 与
 * `GET /app/account/required-actions` 两处返回。Client 在数组非空时阻断
 * 进入主功能，按顺序处理每个 action；完成一个后必须重新拉权威列表。
 *
 * v1 使用的 action:
 * - `complete_profile`：fields=["nickname"]，required=true
 *
 * 文档预留 / R8.4e+ 实现的 action（v1 不返）：
 * - `accept_terms`：version
 * - `bind_mobile`：reason
 * - `verify_email`
 * - `complete_kyc`
 * - `acknowledge_notice`：noticeId
 * - `reset_password`：reason
 *
 * 字段职责分工：
 * - [action]：机器可读，client 用来 dispatch 到对应 sub-component
 *   （`switch (action) { case "complete_profile": ... }`）。
 * - [title] / [titleKey]：用户可读，**只给 UI 展示**。Client 不能拿 title 去匹配
 *   业务逻辑（title 受语言 / 运营文案影响）。
 *
 * 通用对象 + 可选扩展字段，**不强约束 sealed class**：未来加 action 类型
 * 只追加可选字段，不破坏旧 client 的反序列化。
 */
@Serializable
data class RequiredAction(
    /** Action 机器名；v1 唯一支持值 `"complete_profile"`。**稳定枚举，永不本地化**。 */
    val action: String,
    /** 是否必须完成才能进 workspace。
     *  - `true`：client 不识别该 action 时 fail-closed（阻断 + 引导升级）
     *  - `false`：client 不识别时 silent skip
     *  默认 true，**client 解析时缺省也必须按 true 处理**（spec §4.3）。
     */
    val required: Boolean = true,
    /** 用户可读标题，server 直接渲染态文案（默认中文）。
     *  Client 优先用 [titleKey] 本地翻译；fallback 到 [title]；最终 fallback 到 [action] 标识。
     */
    val title: String? = null,
    /** i18n key，用于多语言客户端把同一 action 翻成本地语言。
     *  约定命名：`requiredAction.<action>.<sub>` 例：`requiredAction.completeProfile.nickname`。
     */
    val titleKey: String? = null,
    /** `complete_profile` 用：必填字段列表。v1 只支持 `["nickname"]`。 */
    val fields: List<String> = emptyList(),
    /** `accept_terms` 用：协议版本号。 */
    val version: String? = null,
    /** `acknowledge_notice` 用：通知 ID。 */
    val noticeId: String? = null,
    /** `bind_mobile` / `reset_password` 等用：触发原因，给 UI 文案。 */
    val reason: String? = null,
)
