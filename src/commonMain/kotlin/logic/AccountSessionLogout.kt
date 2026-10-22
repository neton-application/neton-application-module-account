package logic

import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.interfaces.Identity
import port.AccountIdentityAdapter
import security.DeviceBoundIdentity
import security.RealmIdentity

/** Resolve the logout target only from the authenticated identity, never from request parameters. */
internal suspend fun logoutCurrentAccountSession(identity: Identity, adapter: AccountIdentityAdapter) {
    val userId = identity.id.toLongOrNull()?.takeIf { it > 0 }
        ?: throw HttpException(NetonErrorCode.INVALID_TOKEN, AccountErrorCodes.INVALID_CREDENTIALS)
    when (identity) {
        is DeviceBoundIdentity -> {
            if (identity.deviceId.isBlank()) {
                throw HttpException(NetonErrorCode.INVALID_TOKEN, AccountErrorCodes.DEVICE_ID_REQUIRED)
            }
            adapter.logoutSession(userId, sessionId = null, deviceId = identity.deviceId)
        }
        is RealmIdentity -> {
            if (identity.realm != "account") {
                throw HttpException(NetonErrorCode.INVALID_TOKEN, AccountErrorCodes.INVALID_CREDENTIALS)
            }
            // Keep the built-in adapter's documented legacy-session fallback.
            adapter.logoutSession(userId, sessionId = identity.sessionId, deviceId = null)
        }
        else -> throw HttpException(NetonErrorCode.INVALID_TOKEN, AccountErrorCodes.INVALID_CREDENTIALS)
    }
}
