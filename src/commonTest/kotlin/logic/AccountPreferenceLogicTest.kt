package logic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.core.http.BadRequestException
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object PreferenceNoopLogger : Logger {
    override fun trace(msg: String, fields: Fields) {}
    override fun debug(msg: String, fields: Fields) {}
    override fun info(msg: String, fields: Fields) {}
    override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
    override fun error(msg: String, fields: Fields, cause: Throwable?) {}
}

/** 校验必须在任何 SQL 之前完成：碰到数据库就说明非法输入漏过了逻辑层。 */
private object UntouchableDb : DbContext {
    override val dialect: Dialect get() = error("db touched")
    override val interceptors: List<QueryInterceptor> = emptyList()
    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> = error("db touched: $sql")
    override suspend fun execute(sql: String, params: Map<String, Any?>): Long = error("db touched: $sql")
    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> = error("db touched")
    override suspend fun query(built: BuiltSql): List<Row> = error("db touched")
    override suspend fun executeBuilt(built: BuiltSql): Long = error("db touched")
    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R = error("db touched")
    override suspend fun inTransaction(): Boolean = false
}

class AccountPreferenceLogicTest {
    private val logic = AccountPreferenceLogic(PreferenceNoopLogger, UntouchableDb, AccountPreferenceLogic.Limits(maxValueBytes = 32))

    @Test
    fun `accepts dotted lowercase namespaces`() {
        listOf("app", "app.channels", "itwind.feed_tabs", "a-b.c_d.e1", "x".repeat(64)).forEach {
            assertEquals(it, logic.requireNamespace(it))
        }
    }

    @Test
    fun `rejects malformed namespaces before touching the database`() = runBlocking {
        listOf("", "App", "1app", "app.", ".app", "app..x", "app/x", "app x", "app.Channels", "x".repeat(65), "中文").forEach { ns ->
            val e = assertFailsWith<BadRequestException>(ns) { logic.get(1, ns) }
            assertEquals(AccountPreferenceLogic.INVALID_NAMESPACE, e.message)
        }
    }

    @Test
    fun `null value is rejected - deletion goes through DELETE`() = runBlocking {
        assertEquals(AccountPreferenceLogic.VALUE_REQUIRED, assertFailsWith<BadRequestException> { logic.put(1, "app.x", null, null) }.message)
        assertEquals(AccountPreferenceLogic.VALUE_REQUIRED, assertFailsWith<BadRequestException> { logic.put(1, "app.x", JsonNull, null) }.message)
    }

    @Test
    fun `negative versions are rejected`() = runBlocking {
        assertEquals(AccountPreferenceLogic.INVALID_VERSION, assertFailsWith<BadRequestException> { logic.put(1, "app.x", JsonPrimitive(1), -1) }.message)
        assertEquals(AccountPreferenceLogic.INVALID_VERSION, assertFailsWith<BadRequestException> { logic.delete(1, "app.x", 0) }.message)
    }

    @Test
    fun `value size is measured in UTF-8 bytes of the compact encoding`() = runBlocking {
        // {"k":"……"}：8 个汉字 = 24 字节 + 8 字节外壳 = 32，正好在上限内的那一个不该被拒，多一个字就超。
        val tooBig = buildJsonObject { put("k", "汉".repeat(9)) }
        val e = assertFailsWith<HttpException> { logic.put(1, "app.x", tooBig, null) }
        assertEquals(NetonErrorCode.PAYLOAD_TOO_LARGE, e.code)
        assertEquals(AccountPreferenceLogic.VALUE_TOO_LARGE, e.message)
    }
}
