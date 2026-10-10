package controller.admin.account.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountLoginDeviceVO(
    val id: Long,
    val userId: Long,
    val deviceId: String,
    val platform: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val firstLoginIp: String? = null,
    val lastLoginIp: String? = null,
    val firstLoginAt: Long,
    val lastLoginAt: Long,
    val loginCount: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
