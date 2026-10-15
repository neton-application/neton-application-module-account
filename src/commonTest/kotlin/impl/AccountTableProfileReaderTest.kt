package impl

import kotlinx.coroutines.runBlocking
import neton.database.api.DbContext
import neton.database.api.QueryInterceptor
import neton.database.api.Row
import neton.database.api.Table
import neton.database.dsl.SelectBuilder
import neton.database.dsl.TableRef
import neton.database.sql.BuiltSql
import neton.database.sql.Dialect
import port.AccountPublicProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MapRow(private val values: Map<String, Any?>) : Row {
    override fun long(name: String) = values[name] as Long
    override fun int(name: String) = values[name] as Int
    override fun string(name: String) = values[name] as String
    override fun double(name: String) = values[name] as Double
    override fun boolean(name: String) = values[name] as Boolean
    override fun bytes(name: String) = values[name] as ByteArray
    override fun longOrNull(name: String) = values[name] as Long?
    override fun intOrNull(name: String) = values[name] as Int?
    override fun stringOrNull(name: String) = values[name] as String?
    override fun doubleOrNull(name: String) = values[name] as Double?
    override fun booleanOrNull(name: String) = values[name] as Boolean?
    override fun bytesOrNull(name: String) = values[name] as ByteArray?
}

/** 假库：按 IN 参数从内存表里挑行，记录每次查询。 */
private class FakeAccountDb(private val rows: List<Map<String, Any?>>) : DbContext {
    val queries = mutableListOf<Pair<String, Map<String, Any?>>>()
    override val dialect: Dialect get() = error("unused")
    override val interceptors: List<QueryInterceptor> = emptyList()
    override suspend fun fetchAll(sql: String, params: Map<String, Any?>): List<Row> {
        queries += sql to params
        val wanted = params.values.toSet()
        return rows.filter { it["id"] in wanted }.map(::MapRow)
    }
    override suspend fun execute(sql: String, params: Map<String, Any?>): Long = error("unused")
    override fun <T : Any> from(table: Table<T, *>): Pair<SelectBuilder, TableRef<T>> = error("unused")
    override suspend fun query(built: BuiltSql): List<Row> = error("unused")
    override suspend fun executeBuilt(built: BuiltSql): Long = error("unused")
    override suspend fun <R> transaction(block: suspend DbContext.() -> R): R = error("unused")
    override suspend fun inTransaction(): Boolean = false
}

private fun account(id: Long, nickname: String, avatar: String? = null, status: Int = 1, deleted: Int = 0) =
    mapOf<String, Any?>("id" to id, "nickname" to nickname, "avatar" to avatar, "status" to status, "deleted" to deleted)

class AccountTableProfileReaderTest {

    @Test
    fun `reads a batch with one query and only public fields`() = runBlocking {
        val db = FakeAccountDb(listOf(account(1, "Alice", "https://cdn/a.png"), account(2, "Bob"), account(3, "Carol", "")))
        val profiles = AccountTableProfileReader(db).profiles(listOf(1L, 2L, 3L, 2L, 404L))

        assertEquals(1, db.queries.size)
        val (sql, params) = db.queries.single()
        assertEquals(listOf(1L, 2L, 3L, 404L), params.values.toList()) // 去重，逐个绑定
        assertFalse(sql.contains("mobile") || sql.contains("email") || sql.contains("username") || sql.contains("password"))
        assertEquals(AccountPublicProfile(1, "Alice", "https://cdn/a.png"), profiles[1])
        assertEquals(AccountPublicProfile(2, "Bob", null), profiles[2])
        assertEquals(AccountPublicProfile(3, "Carol", null), profiles[3]) // 空串头像视为未设置
        assertFalse(404L in profiles)
    }

    @Test
    fun `deleted accounts keep their id but lose nickname and avatar`() = runBlocking {
        val db = FakeAccountDb(listOf(account(1, "Gone", "https://cdn/g.png", status = 2), account(2, "Soft", deleted = 1), account(3, "Banned", status = 0)))
        val profiles = AccountTableProfileReader(db).profiles(listOf(1L, 2L, 3L))

        assertEquals(AccountPublicProfile(1, null, null, deleted = true), profiles[1])
        assertEquals(AccountPublicProfile(2, null, null, deleted = true), profiles[2])
        // 被禁用不是注销：仍按原昵称展示
        assertEquals(AccountPublicProfile(3, "Banned", null), profiles[3])
    }

    @Test
    fun `empty input does not hit the database and large input is chunked`() = runBlocking {
        val db = FakeAccountDb((1L..1200L).map { account(it, "u$it") })
        val reader = AccountTableProfileReader(db)
        assertTrue(reader.profiles(emptyList()).isEmpty())
        assertTrue(db.queries.isEmpty())

        val profiles = reader.profiles((1L..1200L).toList())
        assertEquals(1200, profiles.size)
        assertEquals(3, db.queries.size)
        assertTrue(db.queries.all { it.second.size <= AccountTableProfileReader.CHUNK })
    }
}
