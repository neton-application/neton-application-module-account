@file:OptIn(neton.core.annotations.InternalNetonApi::class)

package logic

import init.generated.AccountMigrationResources
import init.generated.InfraMigrationResources
import init.generated.SystemMigrationResources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import neton.core.component.NetonContext
import neton.core.config.getEnv
import neton.core.http.adapter.HttpServerConfig
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestEngine
import neton.core.interfaces.RouteDefinition
import neton.core.interfaces.RouteGroupSecurityConfig
import neton.core.interfaces.RouteGroupSecurityConfigs
import neton.core.interfaces.SecurityConfiguration
import neton.core.module.MigrationDialect
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.api.DbContext
import neton.database.config.DatabaseConfig
import neton.database.config.DatabaseDriver
import neton.database.config.PostgresUriInfo
import neton.database.dbContext
import neton.database.migration.MigrationCommand
import neton.database.migration.MigrationConfig
import neton.database.migration.MigrationEngine
import neton.database.migration.MigrationResult
import neton.http.adapter.BufferedHttpDispatcher
import neton.http.adapter.BufferedHttpRequest
import neton.logging.Fields
import neton.logging.Logger
import neton.module.account.generated.AccountRouteInitializer
import neton.security.AuthenticationContextImpl
import neton.security.DefaultGuardImpl
import neton.security.identity.UserId
import neton.security.jwt.JwtAuthenticator
import neton.security.jwt.VerifiedJwtToken
import security.RealmAuthenticator
import security.RealmIdentity
import security.TokenRealm
import security.TokenRealmRegistry
import security.WildcardPermissionEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real HTTP *dispatch* verification of the authenticated, dotted language-preference round trip that
 * spec 10 step (1) demands and that an anonymous 401 probe never proved. It drives the PRODUCTION
 * [AccountRouteInitializer] route table, the PRODUCTION security pipeline (a [RealmAuthenticator] +
 * guard + permission evaluator) and the PRODUCTION [AccountPreferenceController] / [AccountPreferenceLogic]
 * against a real, disposable PostgreSQL, through [BufferedHttpDispatcher] (the same dispatcher the server
 * runs; it does not open a socket here). It proves, on the request path:
 *  - an anonymous write to the auth-gated route is rejected with 401;
 *  - the dotted `app.language` namespace is bound as ONE path variable by the real router (not truncated
 *    at the dot) and written for the *session* subject id, then read back over a second authenticated call;
 *  - a token from another realm cannot reach this realm's route group (an admin-realm token 401s here), so
 *    same numeric id across admin/account does not authenticate on the wrong side.
 *
 * What it deliberately does NOT claim (recorded so a helper test is never read as the full flow):
 *  - the account is a TEST-ONLY realm resolver that trusts a directly-issued HS256 token; this is not the
 *    product login / MFA / session-revocation path;
 *  - there is still no per-request `attachRequestLanguage` hook wired into the dispatch pipeline (the
 *    framework exposes none), so request-scoped language *attach* is NOT proven here;
 *  - no headless browser / SSR is involved (see spec 9, 10).
 *
 * Requires `ACCOUNT_DB_URI` (PostgreSQL role with CREATEDB); skipped when unset, like the other account DbTests.
 */
class AccountPreferenceHttpDispatchTest {

    private object Quiet : Logger {
        override fun trace(msg: String, fields: Fields) {}
        override fun debug(msg: String, fields: Fields) {}
        override fun info(msg: String, fields: Fields) {}
        override fun warn(msg: String, fields: Fields, cause: Throwable?) {}
        override fun error(msg: String, fields: Fields, cause: Throwable?) {}
    }

    private val uid = 70001L

    @Test
    fun `authenticated dotted preference path round trips over real http dispatch and rejects anonymous and wrong realm`() {
        val adminUri = getEnv("ACCOUNT_DB_URI")?.takeIf { it.isNotBlank() } ?: run {
            println("AccountPreferenceHttpDispatchTest skipped: ACCOUNT_DB_URI not set")
            return
        }
        val info = DatabaseConfig(DatabaseDriver.POSTGRESQL, adminUri).parseUri() as PostgresUriInfo
        require(info.host == "127.0.0.1" || info.host == "localhost") {
            "ACCOUNT_DB_URI must point at localhost for the disposable dispatch test"
        }
        val tempDb = "aclhttp_" + neton.security.random.SecureRandom.hex(6)
        val tempUri = withDatabaseName(adminUri, tempDb)
        onDatabase(adminUri) { it.execute("CREATE DATABASE $tempDb") }
        try {
            onDatabase(tempUri) { db ->
                migrate(db)
                val dispatcher = buildDispatcher(db)
                val bearer = mapOf("Authorization" to listOf("Bearer ${signedToken("account")}"))
                val json = bearer + ("Content-Type" to listOf("application/json"))
                val contentTypeOnly = mapOf("Content-Type" to listOf("application/json"))
                val body = "{\"value\":\"en-US\"}".encodeToByteArray()

                // (a) anonymous write to the auth-gated route is rejected before the controller runs.
                val anon = dispatcher.dispatch(
                    BufferedHttpRequest("PUT", "/app/account/preferences/app.language", "", contentTypeOnly, body),
                )
                assertEquals(401, anon.status, "anonymous PUT must be rejected by the real auth pipeline")

                // (b) the dotted namespace binds as one path variable and is written for the session subject.
                val put = dispatcher.dispatch(
                    BufferedHttpRequest("PUT", "/app/account/preferences/app.language", "", json, body),
                )
                assertEquals(200, put.status, "authenticated PUT should succeed; body=${put.body.decodeToString()}")

                // (c) a second authenticated call reads it back over the same router -> value + full dotted key.
                val get = dispatcher.dispatch(
                    BufferedHttpRequest("GET", "/app/account/preferences/app.language", "", bearer, ByteArray(0)),
                )
                assertEquals(200, get.status, "authenticated GET should succeed")
                val text = get.body.decodeToString()
                assertTrue("app.language" in text, "the dotted key must survive the wire, got: $text")
                assertTrue("en-US" in text, "the stored value must round-trip, got: $text")
                // and it landed under the correct subject id (taken from the session, not a request arg).
                val stored = AccountPreferenceLogic(Quiet, db).get(uid, "app.language")
                assertEquals("en-US", (stored.value as? JsonPrimitive)?.content)

                // (d) a wrong-realm token cannot authenticate this realm's route group (same numeric id).
                val wrongRealm = mapOf("Authorization" to listOf("Bearer ${signedToken("admin")}"))
                val cross = dispatcher.dispatch(
                    BufferedHttpRequest("GET", "/app/account/preferences/app.language", "", wrongRealm, ByteArray(0)),
                )
                assertEquals(401, cross.status, "an admin-realm token must not authenticate on the app group")
            }
        } finally {
            onDatabase(adminUri) {
                it.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :d AND pid <> pg_backend_pid()", mapOf("d" to tempDb))
                it.execute("DROP DATABASE IF EXISTS $tempDb")
            }
        }
    }

    private fun buildDispatcher(db: DbContext): BufferedHttpDispatcher {
        val ctx = NetonContext(emptyArray())
        val routes = mutableListOf<RouteDefinition>()
        ctx.bind(RequestEngine::class, object : RequestEngine {
            override fun registerRoute(route: RouteDefinition) { routes.add(route) }
            override fun getRoutes() = routes.toList()
        })
        ctx.bind(AccountPreferenceLogic::class, AccountPreferenceLogic(Quiet, db))
        AccountRouteInitializer.initialize(ctx)

        val jwt = JwtAuthenticator(secretKey = "account-http-dispatch-test-only-secret-0123456789")
        val registry = TokenRealmRegistry()
        registry.register(object : TokenRealm {
            override val name = "account"
            override val routeGroups = setOf("app")
            override val acceptsExternalTokens = false
            override suspend fun resolve(token: VerifiedJwtToken): Identity? {
                if (token.claimString(TokenRealm.TYPE_CLAIM) != TokenRealm.TYPE_ACCESS) return null
                return RealmIdentity(token.identity.userId, setOf("account"), emptySet(), "account", null)
            }
        })
        val authenticator = RealmAuthenticator(jwt, null, registry, "Authorization", "Bearer ", "neton_session")
        val guard = DefaultGuardImpl()
        ctx.bind(
            SecurityConfiguration::class,
            SecurityConfiguration(
                true, 1, 1,
                AuthenticationContextImpl(listOf(authenticator), listOf(guard)),
                authenticator, guard,
                permissionEvaluator = WildcardPermissionEvaluator(),
            ),
        )
        ctx.bind(
            RouteGroupSecurityConfigs::class,
            RouteGroupSecurityConfigs(mapOf("app" to RouteGroupSecurityConfig(true, emptySet()))),
        )
        // stash the jwt so token minting uses the same secret
        this.jwtForTokens = jwt
        return BufferedHttpDispatcher(HttpServerConfig(port = 0)).also { it.bind(ctx) }
    }

    private var jwtForTokens: JwtAuthenticator? = null

    private fun signedToken(realm: String): String =
        (jwtForTokens ?: error("dispatcher not built")).createToken(
            UserId(uid.toULong()),
            expiresInSeconds = 7200,
            extraClaims = mapOf(TokenRealm.CLAIM to realm, TokenRealm.TYPE_CLAIM to TokenRealm.TYPE_ACCESS),
        )

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
}
