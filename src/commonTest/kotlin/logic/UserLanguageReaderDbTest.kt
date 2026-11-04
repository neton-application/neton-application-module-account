@file:OptIn(neton.core.annotations.InternalNetonApi::class)

package logic

import contract.core.UserLanguage
import init.generated.AccountMigrationResources
import init.generated.InfraMigrationResources
import init.generated.SystemMigrationResources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
 * End-to-end (real, disposable database) verification of the language preference path that
 * [AccountRuntimeBootstrap] binds, without touching captcha or any real account:
 *  - the dotted `app.language` namespace is accepted and round-trips through the real store;
 *  - optimistic version + conflict code hold;
 *  - the PRODUCTION reader (same factory the bootstrap binds) turns the stored tag into a UserLanguage
 *    and reflects updates on the next read (no cache -> the token's next request sees the new language);
 *  - isolation per subject id and a non-string value yields null rather than a crash.
 *
 * Requires `ACCOUNT_DB_URI` (PostgreSQL role with CREATEDB); skipped when unset, like AccountPreferenceDbTest.
 */
class UserLanguageReaderDbTest {

    private object Quiet : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private fun withDb(block: suspend (DbContext) -> Unit) {
        val adminUri = getEnv("ACCOUNT_DB_URI")?.takeIf { it.isNotBlank() } ?: run {
            println("UserLanguageReaderDbTest skipped: ACCOUNT_DB_URI not set")
            return
        }
        val tempDb = "aclang_" + neton.security.random.SecureRandom.hex(6)
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

    @Test
    fun `dotted app language round trips and the production reader resolves it`() = withDb { db ->
        val logic = AccountPreferenceLogic(Quiet, db)
        val reader = userLanguageReaderFromPreferences(logic)
        val uid = 4242L

        // No preference yet: the reader returns null so callers fall back to the site default (no crash).
        assertNull(reader.language(uid))

        // Store the dotted namespace through the real logic + database (requireNamespace accepts "app.language").
        val created = logic.put(uid, UserLanguage.PREFERENCE_NAMESPACE, JsonPrimitive("en-US"), null)
        assertEquals(1L, created.version)
        assertEquals("en-US", (logic.get(uid, UserLanguage.PREFERENCE_NAMESPACE).value as JsonPrimitive).content)

        // The production reader turns the stored tag into UserLanguage.EN (reader consumption on real data).
        assertEquals(UserLanguage.EN, reader.language(uid))

        // Explicit script beats region, and the reader reflects the new value on the next call
        // (no cache -> the token's next request sees the updated language).
        logic.put(uid, UserLanguage.PREFERENCE_NAMESPACE, JsonPrimitive("zh-Hans-HK"), created.version)
        assertEquals(UserLanguage.ZH_CN, reader.language(uid))

        // A stale version is rejected with the conflict code and never silently overwrites.
        val e = assertFailsWith<HttpException> { logic.put(uid, UserLanguage.PREFERENCE_NAMESPACE, JsonPrimitive("ja"), created.version) }
        assertEquals(NetonErrorCode.OPERATION_CONFLICT, e.code)
        assertEquals(AccountErrorCodes.PREFERENCE_VERSION_CONFLICT, e.message)
        assertEquals(UserLanguage.ZH_CN, reader.language(uid))

        // Isolation: another subject id has no preference and is unaffected by these writes.
        assertNull(reader.language(4243L))

        // A non-string stored value (wrong type) yields null from the reader instead of throwing.
        logic.put(uid, UserLanguage.PREFERENCE_NAMESPACE, JsonArray(listOf(JsonPrimitive("en-US"))), 2L)
        assertNull(reader.language(uid))
    }
}
