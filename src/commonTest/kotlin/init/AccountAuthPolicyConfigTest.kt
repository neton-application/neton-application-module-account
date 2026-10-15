package init

import neton.core.config.TomlParser
import port.AccountAuthPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AccountAuthPolicyConfigTest {
    private fun parse(toml: String) = AccountAuthPolicyConfig.from(TomlParser.parse(toml, "account.conf"))

    @Test
    fun `no file or no auth section keeps the old default`() {
        assertNull(AccountAuthPolicyConfig.from(null))
        assertNull(parse("[other]\nx = 1\n"))
        assertEquals(AccountAuthPolicy(), parse("[auth]\n") ?: AccountAuthPolicy())
        assertEquals(AccountAuthPolicy(), parse("[auth]\nnickname_required = false\n"))
    }

    @Test
    fun `reads array modes and every flag`() {
        val policy = parse(
            """
            [auth]
            register_modes = ["PHONE_SMS", "USERNAME_PASSWORD"]
            default_register_mode = "username_password"
            invite_code_required = true
            invite_code_required_since = "2026-10-01T00:00:00Z"
            nickname_required = true
            mobile_required = true
            mobile_required_since = "1790000000000"
            account_deletion_enabled = true
            """.trimIndent(),
        )
        assertEquals(
            AccountAuthPolicy(
                registerModes = listOf("PHONE_SMS", "USERNAME_PASSWORD"),
                defaultRegisterMode = "USERNAME_PASSWORD",
                inviteCodeRequired = true,
                inviteCodeRequiredSince = "2026-10-01T00:00:00Z",
                nicknameRequired = true,
                mobileRequired = true,
                mobileRequiredSince = "1790000000000",
                accountDeletionEnabled = true,
            ),
            policy,
        )
    }

    @Test
    fun `accepts comma strings and defaults to the first mode`() {
        val policy = parse("[auth]\nregister_modes = \"USERNAME_PASSWORD\"\n")!!
        assertEquals(listOf("USERNAME_PASSWORD"), policy.registerModes)
        assertEquals("USERNAME_PASSWORD", policy.defaultRegisterMode)
        assertEquals(listOf("PHONE_SMS", "USERNAME_PASSWORD"), parse("[auth]\nregister_modes = \"phone_sms, USERNAME_PASSWORD\"\n")!!.registerModes)
    }

    @Test
    fun `invalid configuration fails fast`() {
        assertFailsWith<IllegalArgumentException> { parse("[auth]\nregister_modes = [\"EMAIL\"]\n") }
        assertFailsWith<IllegalArgumentException> { parse("[auth]\nregister_modes = [\"PHONE_SMS\"]\ndefault_register_mode = \"USERNAME_PASSWORD\"\n") }
        assertFailsWith<IllegalArgumentException> { parse("[auth]\nregister_modes = []\n") }
        assertFailsWith<Exception> { parse("[auth]\nnickname_required = \"maybe\"\n") }
    }
}
