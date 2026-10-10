package logic

import controller.admin.auth.dto.LoginDeviceInfo
import kotlinx.coroutines.runBlocking
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object LoginDeviceNoopLogger : Logger {
    override fun trace(msg: String, fields: Fields) {}
    override fun debug(msg: String, fields: Fields) {}
    override fun info(msg: String, fields: Fields) {}
    override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
    override fun error(msg: String, fields: Fields, cause: Throwable?) {}
}

private class RecordingLoginDeviceDb : DbContext {
    var sql: String = ""
    var params: Map<String, Any?> = emptyMap()
    override val dialect: Dialect get() = error("unused")
    override val interceptors: List<QueryInterceptor> = emptyList()
    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> = error("unused")
    override suspend fun execute(sql: String, params: Map<String, Any?>): Long {
        this.sql = sql
        this.params = params
        return 1
    }
    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> = error("unused")
    override suspend fun query(built: BuiltSql): List<Row> = error("unused")
    override suspend fun executeBuilt(built: BuiltSql): Long = error("unused")
    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R = error("unused")
    override suspend fun inTransaction(): Boolean = false
}

class AccountLoginDeviceLogicTest {
    @Test
    fun `records authoritative device id and server observed ip`() = runBlocking {
        val db = RecordingLoginDeviceDb()
        AccountLoginDeviceLogic(LoginDeviceNoopLogger, db).record(
            userId = 42,
            authoritativeDeviceId = "server-issued-device",
            device = LoginDeviceInfo(
                deviceId = "client-claimed-device",
                platform = "ios",
                deviceName = "Test iPhone",
                deviceModel = "iPhone",
                osVersion = "26.2",
                appVersion = "1.0 (1)",
                ipAddress = "203.0.113.99",
            ),
            remoteIp = "198.51.100.8",
        )

        assertTrue(db.sql.contains("ON CONFLICT (user_id, device_id)"))
        assertTrue(db.sql.contains("login_count = account_login_devices.login_count + 1"))
        assertEquals("server-issued-device", db.params["deviceId"])
        assertEquals("198.51.100.8", db.params["ip"])
        assertFalse(db.params.values.contains("203.0.113.99"), "客户端自报 IP 不能进入审计表")
    }
}
