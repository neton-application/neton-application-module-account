package controller.app.account

import logic.AccountErrorCodes
import controller.app.account.dto.AccountPreferenceListVO
import controller.app.account.dto.AccountPreferenceVO
import controller.app.account.dto.PutAccountPreferenceRequest
import controller.app.account.dto.toVO
import logic.AccountPreferenceLogic
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Delete
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Put
import neton.core.annotations.Query
import neton.core.annotations.RateLimit
import neton.core.annotations.RateLimitScope
import neton.core.interfaces.Identity

/**
 * 当前登录用户的偏好（`/app/account/preferences/…`，spec application/account-preferences.md）。
 * 用户 id 只取自会话，不接受请求参数，所以只能读写自己的偏好。
 */
@Controller("/account/preferences")
class AccountPreferenceController(
    private val preferences: AccountPreferenceLogic,
) {

    @Get("")
    suspend fun list(identity: Identity): AccountPreferenceListVO =
        AccountPreferenceListVO(preferences.list(identity.id.toLong()).map { it.toVO() })

    @Get("/{namespace}")
    suspend fun get(identity: Identity, @PathVariable namespace: String): AccountPreferenceVO =
        preferences.get(identity.id.toLong(), namespace).toVO()

    @Put("/{namespace}")
    @RateLimit(windowSeconds = 60, maxRequests = 60, scope = RateLimitScope.USER, message = AccountErrorCodes.PREFERENCE_WRITE_RATE_LIMITED)
    suspend fun put(
        identity: Identity,
        @PathVariable namespace: String,
        @Body request: PutAccountPreferenceRequest,
    ): AccountPreferenceVO =
        preferences.put(identity.id.toLong(), namespace, request.value, request.version).toVO()

    @Delete("/{namespace}")
    @RateLimit(windowSeconds = 60, maxRequests = 60, scope = RateLimitScope.USER, message = AccountErrorCodes.PREFERENCE_WRITE_RATE_LIMITED)
    suspend fun delete(identity: Identity, @PathVariable namespace: String, @Query version: Long? = null) {
        preferences.delete(identity.id.toLong(), namespace, version)
    }
}
