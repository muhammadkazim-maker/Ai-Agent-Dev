package com.example.coordinator

import android.content.Context
import android.content.SharedPreferences
import com.example.contract.InactivityTimeoutOption

/**
 * Storage contract for remembered Phase 05 active conversation preferences and first-use consent.
 */
interface ConversationPreferencesStore {
    var autoResumeOnScreenReopen: Boolean
    var hasCompletedFirstUseConsentAndConfig: Boolean
    var inactivityTimeoutOption: InactivityTimeoutOption
    var isActiveConversationEnabled: Boolean
}

/**
 * Deterministic in-memory preferences store used by default when no Android [Context] is supplied.
 */
class InMemoryConversationPreferencesStore(
    override var autoResumeOnScreenReopen: Boolean = false,
    override var hasCompletedFirstUseConsentAndConfig: Boolean = false,
    override var inactivityTimeoutOption: InactivityTimeoutOption = InactivityTimeoutOption.MINUTES_2,
    override var isActiveConversationEnabled: Boolean = false
) : ConversationPreferencesStore

/**
 * Android [SharedPreferences]-backed implementation for remembering user preferences across
 * visible screen reopens and Activity recreation.
 */
class SharedPreferencesConversationPreferencesStore(
    context: Context
) : ConversationPreferencesStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var autoResumeOnScreenReopen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME_ON_REOPEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_RESUME_ON_REOPEN, value).apply()
        }

    override var hasCompletedFirstUseConsentAndConfig: Boolean
        get() = prefs.getBoolean(KEY_FIRST_USE_CONSENT_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_USE_CONSENT_DONE, value).apply()
        }

    override var inactivityTimeoutOption: InactivityTimeoutOption
        get() {
            val raw = prefs.getString(KEY_INACTIVITY_TIMEOUT_OPTION, InactivityTimeoutOption.MINUTES_2.name)
            return InactivityTimeoutOption.entries.firstOrNull { it.name == raw }
                ?: InactivityTimeoutOption.MINUTES_2
        }
        set(value) {
            prefs.edit().putString(KEY_INACTIVITY_TIMEOUT_OPTION, value.name).apply()
        }

    override var isActiveConversationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE_CONVERSATION_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ACTIVE_CONVERSATION_ENABLED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "omnivoice_phase05_conversation_prefs"
        private const val KEY_AUTO_RESUME_ON_REOPEN = "auto_resume_on_reopen"
        private const val KEY_FIRST_USE_CONSENT_DONE = "first_use_consent_done"
        private const val KEY_INACTIVITY_TIMEOUT_OPTION = "inactivity_timeout_option"
        private const val KEY_ACTIVE_CONVERSATION_ENABLED = "active_conversation_enabled"
    }
}
