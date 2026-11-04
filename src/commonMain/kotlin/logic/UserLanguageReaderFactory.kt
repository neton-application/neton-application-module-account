package logic

import contract.core.UserLanguage
import contract.core.UserLanguageReader
import kotlinx.serialization.json.JsonPrimitive

/**
 * The production language reader: resolves a user's stored `app.language` preference (a JSON string
 * scalar) into a [UserLanguage], returning null when the user has no preference or the stored value
 * is not a language tag. [AccountRuntimeBootstrap] binds this so other modules write text for a user
 * in the language they last reported; a null result means "fall back to the caller's site default".
 *
 * Kept as one function so the assembled component and its database test exercise the exact same
 * resolution path (no copy that could drift from what runs in production).
 */
fun userLanguageReaderFromPreferences(preferences: AccountPreferenceLogic): UserLanguageReader =
    UserLanguageReader { userId ->
        val value = preferences.get(userId, UserLanguage.PREFERENCE_NAMESPACE).value
        UserLanguage.of((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
    }
