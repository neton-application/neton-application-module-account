package logic

import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.http.ValidationError

/**
 * The one device-id contract shared by PrivChat, Weey and Neton.
 *
 * A device id is a random UUID version 4 (RFC 4122 variant), lowercase and hyphenated. The client
 * generates it once with a cryptographically secure RNG, persists it, and sends the same value on
 * every login, registration and refresh. The server never mints, normalises or rewrites it:
 * anything else is rejected.
 */
object AccountDeviceId {
    val REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun isValid(value: String?): Boolean = value != null && REGEX.matches(value)

    /**
     * Returns [value] when it is a canonical device id. A missing or blank value fails with
     * [AccountErrorCodes.DEVICE_ID_REQUIRED], any other value with [AccountErrorCodes.INVALID_DEVICE_ID];
     * both carry a field error on [field] (the request body path, e.g. `deviceId` or `device.deviceId`).
     */
    fun require(value: String?, field: String): String {
        if (value.isNullOrBlank()) {
            throw HttpException(
                NetonErrorCode.MISSING_REQUIRED_PARAM,
                AccountErrorCodes.DEVICE_ID_REQUIRED,
                listOf(ValidationError(field, AccountErrorCodes.DEVICE_ID_REQUIRED, AccountErrorCodes.DEVICE_ID_REQUIRED)),
            )
        }
        if (!REGEX.matches(value)) {
            throw HttpException(
                NetonErrorCode.INVALID_PARAMS,
                AccountErrorCodes.INVALID_DEVICE_ID,
                listOf(ValidationError(field, AccountErrorCodes.INVALID_DEVICE_ID, AccountErrorCodes.INVALID_DEVICE_ID)),
            )
        }
        return value
    }
}
