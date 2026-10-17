package controller.app.account.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import logic.AccountPreferenceLogic

/**
 * `PUT /app/account/preferences/{namespace}` 请求体。
 *
 * - [value]：要保存的 JSON（对象、数组或标量均可，不能是 null —— 删除走 DELETE）。
 * - [version]：客户端读到的版本。缺省或 0 = 只创建（已存在则 409）；N = 仅当当前版本为 N 时覆盖。
 */
@Serializable
data class PutAccountPreferenceRequest(
    val value: JsonElement? = null,
    val version: Long? = null,
)

/** 单个偏好。不存在时 `value = null`、`version = 0`、`updatedAt = null`。 */
@Serializable
data class AccountPreferenceVO(
    val namespace: String,
    val value: JsonElement?,
    val version: Long,
    val updatedAt: Long?,
)

@Serializable
data class AccountPreferenceSummaryVO(
    val namespace: String,
    val version: Long,
    val sizeBytes: Int,
    val updatedAt: Long,
)

@Serializable
data class AccountPreferenceListVO(
    val items: List<AccountPreferenceSummaryVO>,
)

fun AccountPreferenceLogic.Preference.toVO() = AccountPreferenceVO(namespace, value, version, updatedAt)

fun AccountPreferenceLogic.Summary.toVO() = AccountPreferenceSummaryVO(namespace, version, sizeBytes, updatedAt)
