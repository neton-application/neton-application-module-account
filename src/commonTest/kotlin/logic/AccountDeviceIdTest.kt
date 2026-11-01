package logic

import controller.admin.auth.dto.LoginDeviceInfo
import controller.app.auth.dto.AccountLoginRequest
import controller.app.auth.dto.AccountRegisterRequest
import kotlinx.coroutines.runBlocking
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.database.api.DbContext
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.api.Table
import neton.database.dsl.SelectBuilder
import neton.database.dsl.TableRef
import neton.database.sql.BuiltSql
import neton.database.sql.Dialect
import neton.logging.Fields
import neton.logging.Logger
import port.AccountIdentityAdapter
import port.AccountRef
import port.AccountTokenBundle
import port.CreateMemberAccountCommand
import port.IssueMemberTokenCommand
import port.RefreshMemberTokenCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeviceIdTest {
    private val valid = "3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c"

    @Test fun acceptsLowercaseHyphenatedV4() {
        assertEquals(valid, AccountDeviceId.require(valid, "deviceId"))
        for (variant in listOf('8', '9', 'a', 'b')) {
            assertTrue(AccountDeviceId.isValid("3f2b8c1e-9d4a-4e7b-${variant}1c2-5d6e7f8a9b0c"))
        }
    }

    @Test fun rejectsEverythingElse() {
        val rejected = listOf(
            "d-mv7w83ig-2hyz0", // the format the server used to mint
            "3F2B8C1E-9D4A-4E7B-A1C2-5D6E7F8A9B0C", // uppercase is not normalised
            "3f2b8c1e9d4a4e7ba1c25d6e7f8a9b0c", // no hyphens
            "{3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c}",
            "urn:uuid:3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c",
            " 3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c",
            "3f2b8c1e-9d4a-4e7b-a1c2-5d6e7f8a9b0c\n",
            "3f2b8c1e-9d4a-1e7b-a1c2-5d6e7f8a9b0c", // version 1
            "3f2b8c1e-9d4a-5e7b-a1c2-5d6e7f8a9b0c", // version 5
            "3f2b8c1e-9d4a-4e7b-c1c2-5d6e7f8a9b0c", // Microsoft variant
            "3f2b8c1e-9d4a-4e7b-71c2-5d6e7f8a9b0c", // NCS variant
            "00000000-0000-0000-0000-000000000000",
            "e2e-device",
        )
        for (value in rejected) {
            assertFalse(AccountDeviceId.isValid(value), value)
            val error = assertFailsWith<HttpException>(value) { AccountDeviceId.require(value, "device.deviceId") }
            assertEquals(NetonErrorCode.INVALID_PARAMS, error.code)
            assertEquals(AccountErrorCodes.INVALID_DEVICE_ID, error.message)
            assertEquals("device.deviceId", error.errors.single().path)
            assertEquals(AccountErrorCodes.INVALID_DEVICE_ID, error.errors.single().code)
        }
    }

    @Test fun missingOrBlankIsRequired() {
        for (value in listOf(null, "", "   ")) {
            val error = assertFailsWith<HttpException> { AccountDeviceId.require(value, "deviceId") }
            assertEquals(NetonErrorCode.MISSING_REQUIRED_PARAM, error.code)
            assertEquals(AccountErrorCodes.DEVICE_ID_REQUIRED, error.message)
            assertEquals("deviceId", error.errors.single().path)
        }
    }

    // ---- entry points: the device id is checked before any database or identity-backend work ----

    private class UntouchableDb : DbContext {
        override val dialect: Dialect get() = error("database touched")
        override val interceptors: List<QueryInterceptor> = emptyList()
        override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> = error("database touched")
        override suspend fun execute(sql: String, params: Map<String, Any?>): Long = error("database touched")
        override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> = error("database touched")
        override suspend fun query(built: BuiltSql): List<Row> = error("database touched")
        override suspend fun executeBuilt(built: BuiltSql): Long = error("database touched")
        override suspend fun <R> transaction(block: suspend DbContext.() -> R): R = error("database touched")
        override suspend fun inTransaction(): Boolean = error("database touched")
    }

    private class RecordingAdapter : AccountIdentityAdapter {
        var calls = 0
        override suspend fun createOrBindAccount(command: CreateMemberAccountCommand): AccountRef { calls++; error("unused") }
        override suspend fun issueToken(command: IssueMemberTokenCommand): AccountTokenBundle { calls++; error("unused") }
        override suspend fun refreshToken(command: RefreshMemberTokenCommand): AccountTokenBundle { calls++; error("unused") }
    }

    private object NoopLogger : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private fun logic(adapter: AccountIdentityAdapter): AccountAuthLogic {
        val db = UntouchableDb()
        return AccountAuthLogic(NoopLogger, db, adapter, RequiredActionsLogic(AccountLogic(NoopLogger, db)))
    }

    private fun assertDeviceRejected(expected: String, field: String, block: suspend () -> Unit) {
        val error = assertFailsWith<HttpException> { runBlocking { block() } }
        assertEquals(expected, error.message)
        assertEquals(field, error.errors.single().path)
    }

    @Test fun everyTokenEntryRejectsBadDeviceIdsUpFront() {
        val adapter = RecordingAdapter()
        val auth = logic(adapter)
        val bad = LoginDeviceInfo(deviceId = "d-mv7w83ig-2hyz0", platform = "web")
        val e = AccountErrorCodes.INVALID_DEVICE_ID
        val r = AccountErrorCodes.DEVICE_ID_REQUIRED

        assertDeviceRejected(e, "deviceId") { auth.refreshToken("x".repeat(64), "d-mv7w83ig-2hyz0") }
        assertDeviceRejected(r, "deviceId") { auth.refreshToken("x".repeat(64), null) }
        assertDeviceRejected(e, "device.deviceId") {
            auth.login(AccountLoginRequest(mobile = "+8615000000000", password = "password1", device = bad))
        }
        assertDeviceRejected(r, "device.deviceId") {
            auth.login(AccountLoginRequest(mobile = "+8615000000000", password = "password1", device = null))
        }
        assertDeviceRejected(e, "device.deviceId") {
            auth.smsLogin(AccountLoginRequest(mobile = "+8615000000000", smsCode = "123456", device = bad))
        }
        assertDeviceRejected(r, "device.deviceId") {
            auth.smsLogin(AccountLoginRequest(mobile = "+8615000000000", smsCode = "123456", device = LoginDeviceInfo(platform = "web")))
        }
        assertDeviceRejected(e, "device.deviceId") { auth.usernameLogin("someone", "password1", bad) }
        assertDeviceRejected(e, "device.deviceId") { auth.socialLogin("wechat", "code", "https://example.test/cb", bad) }
        assertDeviceRejected(e, "device.deviceId") {
            auth.register(AccountRegisterRequest(mode = "USERNAME_PASSWORD", username = "someone", password = "password1", device = bad))
        }
        assertDeviceRejected(e, "device.deviceId") { auth.issueLoginResponseForUid(42, bad) }
        assertEquals(0, adapter.calls)
    }
}
