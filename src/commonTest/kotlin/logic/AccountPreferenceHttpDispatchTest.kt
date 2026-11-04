@file:OptIn(neton.core.annotations.InternalNetonApi::class)

package logic

import init.generated.AccountMigrationResources
import init.generated.InfraMigrationResources
import init.generated.SystemMigrationResources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import contract.core.LanguageReaderRegistry
import impl.BuiltinAccountIdentityAdapter
import model.Account
import table.AccountTable
import neton.security.password.PasswordHasher
import controller.admin.auth.dto.LoginDeviceInfo
import logic.provider.MessageProvider
import model.MessageChannel
import model.MessageTemplate
import table.MessageChannelTable
import table.MessageTemplateTable
import neton.database.dsl.*
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
 *  - credentials run through production AccountAuthLogic.usernameLogin and BuiltinAccountIdentityAdapter;
 *    requests then use production AccountTokenRealm (including account state/session-version checks).
 *    The login controller's CAPTCHA transport and socket listener are not exercised by this dispatch test.
 *  - the production preference controller lazily attaches request language at its actual consumer; the
 *    original access token sees preference changes. No global framework hook is needed.
 *  - notification composition uses both real realm readers, while delivery is captured instead of sent.
 *  - no headless browser is involved in this native test; browser evidence is recorded separately.
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

    private var uid = 0L
    private var accountAccessToken = ""

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
                assertTrue("\"effectiveLanguage\":\"en\"" in text, "production request consumer resolves the account reader")
                // and it landed under the correct subject id (taken from the session, not a request arg).
                val stored = AccountPreferenceLogic(Quiet, db).get(uid, "app.language")
                assertEquals("en-US", (stored.value as? JsonPrimitive)?.content)

                val updated = dispatcher.dispatch(BufferedHttpRequest("PUT", "/app/account/preferences/app.language", "", json,
                    "{\"value\":\"zh-CN\",\"version\":1}".encodeToByteArray()))
                assertEquals(200, updated.status)
                val sameToken = dispatcher.dispatch(BufferedHttpRequest("GET", "/app/account/preferences/app.language", "", bearer, ByteArray(0)))
                assertTrue("\"effectiveLanguage\":\"zh-CN\"" in sameToken.body.decodeToString(),
                    "the exact same access token sees the new language on the next real request")
                verifyRealRecipientReaders(db)

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

    private suspend fun buildDispatcher(db: DbContext): BufferedHttpDispatcher {
        val ctx = NetonContext(emptyArray())
        val routes = mutableListOf<RouteDefinition>()
        ctx.bind(RequestEngine::class, object : RequestEngine {
            override fun registerRoute(route: RouteDefinition) { routes.add(route) }
            override fun getRoutes() = routes.toList()
        })
        ctx.bind(AccountPreferenceLogic::class, AccountPreferenceLogic(Quiet, db))
        ctx.bind(LanguageReaderRegistry::class, LanguageReaderRegistry(readers = mapOf(
            "account" to userLanguageReaderFromPreferences(ctx.get(AccountPreferenceLogic::class)),
        )))
        AccountRouteInitializer.initialize(ctx)

        val jwt = JwtAuthenticator(secretKey = "account-http-dispatch-test-only-secret-0123456789")
        val adapter = BuiltinAccountIdentityAdapter(jwt, db)
        val password = "isolated-i18n-fixture-password"
        val reference = adapter.createOrBindAccount(port.CreateMemberAccountCommand(username = "i18n-fixture"))
        val account = insertAccountWithProvidedId(db, Account(id = reference.memberId, username = "i18n-fixture", nickname = "Fixture", password = PasswordHasher.hash(password)))
        uid = account.id
        val login = AccountAuthLogic(Quiet, db, adapter, RequiredActionsLogic(AccountLogic(Quiet, db)))
            .usernameLogin("i18n-fixture", password, LoginDeviceInfo(deviceId = "bb7e5423-3533-4ecd-8ef1-6c79f10a9dd2"), "127.0.0.1")
        accountAccessToken = login.accessToken
        val registry = TokenRealmRegistry()
        registry.register(security.AccountTokenRealm { adapter })
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

    private suspend fun verifyRealRecipientReaders(db: DbContext) {
        // Both owners' actual stores/readers, with the SAME numeric subject ID. No fake account reader.
        db.execute("""INSERT INTO system_users (id,username,nickname,password_hash,locale,status)
            VALUES (:id,'i18n-admin','Operator','not-a-login-hash','en-US',1)
            ON CONFLICT (id) DO UPDATE SET locale = 'en-US'""", mapOf("id" to uid))
        val readers = LanguageReaderRegistry(readers = mapOf(
            "admin" to adminLanguageReader(),
            "account" to userLanguageReaderFromPreferences(AccountPreferenceLogic(Quiet, db)),
        ))
        assertEquals(contract.core.UserLanguage.EN, readers.contextFor("admin", uid).languageOrNull())
        assertEquals(contract.core.UserLanguage.ZH_CN, readers.contextFor("account", uid).languageOrNull())
        val delivered = mutableListOf<String>()
        val provider = object : MessageProvider {
            override val type = "email"
            override suspend fun send(receiver: String, content: String, config: String): Boolean {
                delivered += content
                return true
            }
        }
        val channel = MessageChannelTable.insert(MessageChannel(name = "Isolated", code = "i18n-fixture", type = "email", status = 1))
        MessageTemplateTable.insert(MessageTemplate(name = "English", code = "i18n", content = "Hello {{name}}", language = "en", channelId = channel.id))
        MessageTemplateTable.insert(MessageTemplate(name = "Chinese", code = "i18n", content = "你好 {{name}}", language = "zh-CN", channelId = channel.id))
        val sender = MessageSendLogic(Quiet, MessageChannelLogic(Quiet, mapOf("email" to provider)), MessageTemplateLogic(Quiet), readers)
        assertTrue(sender.sendByTemplate("i18n", "fixture@example.invalid", mapOf("name" to "A"), uid, 1))
        assertTrue(sender.sendByTemplate("i18n", "fixture@example.invalid", mapOf("name" to "B"), uid, 2))
        assertEquals(listOf("Hello A", "你好 B"), delivered)
        val snapshot = table.MessageLogTable.query { orderBy(model.MessageLog::id.asc()) }.list().last()
        AccountPreferenceLogic(Quiet, db).put(uid, "app.language", JsonPrimitive("en-US"), 2L)
        assertTrue(sender.resend(snapshot.id))
        assertEquals("你好 B", delivered.last(), "retry replays the snapshot after the real recipient preference changes")
    }

    private fun signedToken(realm: String): String =
        if (realm == "account") accountAccessToken else (jwtForTokens ?: error("dispatcher not built")).createToken(
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
