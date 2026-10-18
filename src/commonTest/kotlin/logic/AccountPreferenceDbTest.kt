package logic

import init.generated.AccountMigrationResources
import init.generated.InfraMigrationResources
import init.generated.SystemMigrationResources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import neton.core.config.getEnv
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.module.MigrationDialect
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.api.DbContext
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.dbContext
import neton.database.migration.MigrationCommand
import neton.database.migration.MigrationConfig
import neton.database.migration.MigrationEngine
import neton.database.migration.MigrationResult
import neton.logging.Fields
import neton.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * 账号偏好的真实数据库契约：临时库里跑 system + infra + account 的真实迁移，驱动真实的 [AccountPreferenceLogic]。
 *
 * 需要 `ACCOUNT_DB_URI`（PostgreSQL，角色须有 CREATEDB），例如
 * `ACCOUNT_DB_URI=postgresql://user:pass@localhost:5432/postgres`。未设置时整组跳过，不影响普通 `macosArm64Test`。
 */
class AccountPreferenceDbTest {

    private object Quiet : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private fun withDb(block: suspend (DbContext) -> Unit) {
        val adminUri = getEnv("ACCOUNT_DB_URI")?.takeIf { it.isNotBlank() } ?: run {
            println("AccountPreferenceDbTest skipped: ACCOUNT_DB_URI not set")
            return
        }
        val tempDb = "acctest_" + neton.security.random.SecureRandom.hex(6)
        val tempUri = withDatabaseName(adminUri, tempDb)
        onDatabase(adminUri) { it.execute("CREATE DATABASE $tempDb") }
        try {
            onDatabase(tempUri) { db ->
                migrate(db)
                block(db)
            }
        } finally {
            onDatabase(adminUri) {
                it.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :d AND pid <> pg_backend_pid()", mapOf("d" to tempDb))
                it.execute("DROP DATABASE IF EXISTS $tempDb")
            }
        }
    }

    private fun withDatabaseName(uri: String, database: String): String {
        val queryStart = uri.indexOf('?').let { if (it < 0) uri.length else it }
        val base = uri.substring(0, queryStart)
        return base.substring(0, base.lastIndexOf('/') + 1) + database + uri.substring(queryStart)
    }

    private fun onDatabase(uri: String, block: suspend (DbContext) -> Unit) {
        SqlxDatabase.initialize(DatabaseConfig(driver = DatabaseDriver.POSTGRESQL, uri = uri))
        try {
            runBlocking { block(dbContext()) }
        } finally {
            runBlocking { SqlxDatabase.close() }
        }
    }

    private suspend fun migrate(db: DbContext) {
        val engine = MigrationEngine(db, MigrationConfig(dialect = MigrationDialect.POSTGRESQL))
        listOf(SystemMigrationResources.sources, InfraMigrationResources.sources, AccountMigrationResources.sources).forEach { sources ->
            when (val r = engine.run(MigrationCommand.UP, sources)) {
                is MigrationResult.Up -> if (!r.ok) fail("migration failed at ${r.failedAt}")
                else -> fail("unexpected migration result: $r")
            }
        }
    }

    private suspend fun conflict(block: suspend () -> Unit) {
        val e = assertFailsWith<HttpException> { block() }
        assertEquals(NetonErrorCode.OPERATION_CONFLICT, e.code)
        assertEquals(AccountErrorCodes.PREFERENCE_VERSION_CONFLICT, e.message)
    }

    @Test
    fun `optimistic versions isolation listing and purge`() = withDb { db ->
        var now = 1_000L
        val logic = AccountPreferenceLogic(Quiet, db, clock = { now })
        val channels = buildJsonObject { put("order", JsonArray(listOf(JsonPrimitive("tech"), JsonPrimitive("news")))) }

        val absent = logic.get(7, "app.channels")
        assertNull(absent.value); assertEquals(0, absent.version); assertNull(absent.updatedAt)

        val created = logic.put(7, "app.channels", channels, null)
        assertEquals(1, created.version); assertEquals(1_000L, created.updatedAt)
        assertEquals(channels, logic.get(7, "app.channels").value)

        // 「只创建」碰上已存在：409，原值不动。
        conflict { logic.put(7, "app.channels", JsonPrimitive("x"), 0) }
        assertEquals(channels, logic.get(7, "app.channels").value)

        now = 2_000L
        val updated = logic.put(7, "app.channels", JsonPrimitive("v2"), 1)
        assertEquals(2, updated.version); assertEquals(2_000L, updated.updatedAt)
        conflict { logic.put(7, "app.channels", JsonPrimitive("stale"), 1) }
        conflict { logic.put(7, "app.missing", JsonPrimitive("x"), 3) }
        assertEquals(JsonPrimitive("v2"), logic.get(7, "app.channels").value)

        // 别的用户同名命名空间互不影响。
        assertEquals(0, logic.get(8, "app.channels").version)
        assertEquals(1, logic.put(8, "app.channels", JsonPrimitive("other"), null).version)

        logic.put(7, "app.theme", JsonPrimitive("dark"), null)
        val listed = logic.list(7)
        assertEquals(listOf("app.channels", "app.theme"), listed.map { it.namespace })
        assertEquals(listOf(2L, 1L), listed.map { it.version })
        assertEquals("\"v2\"".length, listed[0].sizeBytes)

        conflict { logic.delete(7, "app.theme", 5) }
        logic.delete(7, "app.theme", 1)
        logic.delete(7, "app.theme", null) // 已不存在：无条件删除幂等
        conflict { logic.delete(7, "app.theme", 1) }
        assertEquals(listOf("app.channels"), logic.list(7).map { it.namespace })

        assertEquals(1, logic.deleteAllFor(7))
        assertEquals(emptyList(), logic.list(7))
        assertEquals(1, logic.list(8).size)

        // 删除后重新创建从版本 1 开始。
        assertEquals(1, logic.put(7, "app.channels", channels, 0).version)
    }

    @Test
    fun `concurrent writers - one wins per version and the namespace cap holds`() = withDb { db ->
        val logic = AccountPreferenceLogic(Quiet, db, AccountPreferenceLogic.Limits(maxNamespacesPerUser = 3))

        // 同一版本并发覆盖：只有一个成功，其余 409。
        logic.put(1, "app.counter", JsonPrimitive(0), null)
        val outcomes = coroutineScope {
            (1..8).map { i -> async { runCatching { logic.put(1, "app.counter", JsonPrimitive(i), 1) } } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(2, logic.get(1, "app.counter").version)

        // 并发创建不同命名空间：数量上限不被越过（已有 1 个，上限 3 → 只能再成功 2 个）。
        val creates = coroutineScope {
            (1..6).map { i -> async { runCatching { logic.put(1, "app.ns$i", JsonPrimitive(i), null) } } }.awaitAll()
        }
        assertEquals(2, creates.count { it.isSuccess })
        creates.filter { it.isFailure }.forEach {
            val e = it.exceptionOrNull() as HttpException
            assertEquals(NetonErrorCode.PARAM_OUT_OF_RANGE, e.code)
            assertEquals(AccountErrorCodes.PREFERENCE_NAMESPACE_LIMIT_EXCEEDED, e.message)
        }
        assertEquals(3, logic.list(1).size)

        // 并发创建同一命名空间：一个成功，其余 409。
        val other = coroutineScope {
            (1..6).map { i -> async { runCatching { logic.put(2, "app.same", JsonPrimitive(i), null) } } }.awaitAll()
        }
        assertEquals(1, other.count { it.isSuccess })
        other.filter { it.isFailure }.forEach { assertEquals(AccountErrorCodes.PREFERENCE_VERSION_CONFLICT, it.exceptionOrNull()?.message) }

        // 已存在的命名空间在满额时仍可更新。
        assertEquals(3, logic.put(1, "app.counter", JsonPrimitive("x"), 2).version)
    }
}
