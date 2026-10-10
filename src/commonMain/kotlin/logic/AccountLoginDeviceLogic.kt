package logic

import controller.admin.auth.dto.LoginDeviceInfo
import controller.admin.account.dto.AccountLoginDeviceVO
import neton.database.api.DbContext
import neton.logging.Logger
import kotlin.time.Clock

/**
 * 登录设备审计。
 *
 * deviceId 使用身份后端最终签进 token 的值，而不是直接相信客户端请求值；IP 只使用
 * HTTP 入口从连接信息得到的 remoteIp，忽略客户端 JSON 里的 ipAddress。
 * 审计落库失败不应让已经签发的 token 变成客户端看见的登录失败，因此这里记录告警后放行。
 */
class AccountLoginDeviceLogic(
    private val log: Logger,
    private val db: DbContext,
) {
    suspend fun record(
        userId: Long,
        authoritativeDeviceId: String,
        device: LoginDeviceInfo?,
        remoteIp: String?,
    ) {
        val deviceId = authoritativeDeviceId.cleaned(64) ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        val ip = remoteIp.cleaned(64)
        runCatching {
            db.execute(
                """
                INSERT INTO account_login_devices (
                    user_id, device_id, platform, device_name, device_model, os_version, app_version,
                    first_login_ip, last_login_ip, first_login_at, last_login_at, login_count,
                    created_at, updated_at
                ) VALUES (
                    :userId, :deviceId, :platform, :deviceName, :deviceModel, :osVersion, :appVersion,
                    :ip, :ip, :now, :now, 1, :now, :now
                )
                ON CONFLICT (user_id, device_id) DO UPDATE SET
                    platform = COALESCE(EXCLUDED.platform, account_login_devices.platform),
                    device_name = COALESCE(EXCLUDED.device_name, account_login_devices.device_name),
                    device_model = COALESCE(EXCLUDED.device_model, account_login_devices.device_model),
                    os_version = COALESCE(EXCLUDED.os_version, account_login_devices.os_version),
                    app_version = COALESCE(EXCLUDED.app_version, account_login_devices.app_version),
                    last_login_ip = COALESCE(EXCLUDED.last_login_ip, account_login_devices.last_login_ip),
                    last_login_at = EXCLUDED.last_login_at,
                    login_count = account_login_devices.login_count + 1,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                mapOf(
                    "userId" to userId,
                    "deviceId" to deviceId,
                    "platform" to device?.platform.cleaned(32),
                    "deviceName" to device?.deviceName.cleaned(128),
                    "deviceModel" to device?.deviceModel.cleaned(128),
                    "osVersion" to device?.osVersion.cleaned(64),
                    "appVersion" to device?.appVersion.cleaned(64),
                    "ip" to ip,
                    "now" to now,
                ),
            )
        }.onFailure { error ->
            log.warn(
                "account.login_device.record_failed",
                mapOf("userId" to userId, "deviceId" to deviceId, "error" to (error.message ?: "")),
            )
        }
    }

    suspend fun list(userId: Long): List<AccountLoginDeviceVO> =
        db.fetchAll(
            """
            SELECT id, user_id, device_id, platform, device_name, device_model, os_version,
                   app_version, first_login_ip, last_login_ip, first_login_at, last_login_at,
                   login_count, created_at, updated_at
            FROM account_login_devices
            WHERE user_id = :userId
            ORDER BY last_login_at DESC, id DESC
            LIMIT 100
            """.trimIndent(),
            mapOf("userId" to userId),
        ).map { row ->
            AccountLoginDeviceVO(
                id = row.long("id"),
                userId = row.long("user_id"),
                deviceId = row.string("device_id"),
                platform = row.stringOrNull("platform"),
                deviceName = row.stringOrNull("device_name"),
                deviceModel = row.stringOrNull("device_model"),
                osVersion = row.stringOrNull("os_version"),
                appVersion = row.stringOrNull("app_version"),
                firstLoginIp = row.stringOrNull("first_login_ip"),
                lastLoginIp = row.stringOrNull("last_login_ip"),
                firstLoginAt = row.long("first_login_at"),
                lastLoginAt = row.long("last_login_at"),
                loginCount = row.long("login_count"),
                createdAt = row.long("created_at"),
                updatedAt = row.long("updated_at"),
            )
        }

    private fun String?.cleaned(maxLength: Int): String? =
        this?.trim()?.takeIf(String::isNotEmpty)?.take(maxLength)
}
