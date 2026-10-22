package logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import neton.core.http.HttpException
import neton.security.identity.IdentityUser
import neton.security.identity.UserId
import port.*
import security.RealmIdentity
import security.UnifiedSessionIdentity
import kotlin.test.*

class AccountSessionLogoutTest {
    private class Adapter : AccountIdentityAdapter {
        val calls = mutableListOf<Triple<Long, String?, String?>>()
        var failure: Exception? = null
        override suspend fun createOrBindAccount(command: CreateMemberAccountCommand): AccountRef = error("unused")
        override suspend fun issueToken(command: IssueMemberTokenCommand): AccountTokenBundle = error("unused")
        override suspend fun refreshToken(command: RefreshMemberTokenCommand): AccountTokenBundle = error("unused")
        override suspend fun revokeAllSessions(memberId: Long, reason: String) = error("not logout-all")
        override suspend fun logoutSession(memberId: Long, sessionId: String?, deviceId: String?) {
            failure?.let { throw it }
            calls += Triple(memberId, sessionId, deviceId)
        }
    }

    @Test fun unifiedLogoutUsesOnlyAuthenticatedDevice() = runBlocking {
        val adapter = Adapter()
        logoutCurrentAccountSession(UnifiedSessionIdentity(UserId(42u), "device-b", 7), adapter)
        assertEquals(listOf<Triple<Long, String?, String?>>(Triple(42L, null, "device-b")), adapter.calls)
    }

    @Test fun builtinAccountSessionAndLegacyIdentityRemainCompatible() = runBlocking {
        val adapter = Adapter()
        logoutCurrentAccountSession(RealmIdentity(UserId(42u), emptySet(), emptySet(), "account", "session-a"), adapter)
        logoutCurrentAccountSession(RealmIdentity(UserId(42u), emptySet(), emptySet(), "account", null), adapter)
        assertEquals(listOf<Triple<Long, String?, String?>>(Triple(42L, "session-a", null), Triple(42L, null, null)), adapter.calls)
    }

    @Test fun missingDeviceUnknownIdentityAndWrongRealmDoNotFallBackToGlobalLogout() = runBlocking {
        val adapter = Adapter()
        assertFailsWith<HttpException> { logoutCurrentAccountSession(UnifiedSessionIdentity(UserId(42u), " ", 7), adapter) }
        assertFailsWith<HttpException> { logoutCurrentAccountSession(IdentityUser(UserId(42u)), adapter) }
        assertFailsWith<HttpException> { logoutCurrentAccountSession(RealmIdentity(UserId(42u), emptySet(), emptySet(), "admin", "session"), adapter) }
        assertTrue(adapter.calls.isEmpty())
    }

    @Test fun failureAndCancellationDoNotReportLogoutSuccess() = runBlocking {
        val adapter = Adapter()
        val identity = UnifiedSessionIdentity(UserId(42u), "b", 7)
        adapter.failure = IllegalStateException("backend unavailable")
        assertFailsWith<IllegalStateException> { logoutCurrentAccountSession(identity, adapter) }
        adapter.failure = CancellationException("cancel")
        assertFailsWith<CancellationException> { logoutCurrentAccountSession(identity, adapter) }
        assertTrue(adapter.calls.isEmpty())
    }
}
