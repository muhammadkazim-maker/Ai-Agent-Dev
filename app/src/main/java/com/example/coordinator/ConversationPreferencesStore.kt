package com.example.coordinator

import android.content.Context
import com.example.contract.InactivityTimeoutOption

/**
 * Storage contract for explicit user conversation preferences in Phase 05.
 *
 * Ensures that:
 * - Active continuous conversation mode (`isActiveConversationEnabled`, default `false`)
 * - Auto-resume on screen reopen (`autoResumeOnScreenReopen`, default `false`)
 * - First-use consent and configuration completion (`hasCompletedFirstUseConsentAndConfig`, default `false`)
 * - Inactivity session timeout (`inactivityTimeoutOption`, default `MINUTES_2`)
 * are persisted across process restarts while never overriding an explicit `Pause` or `Stop`.
 */
interface ConversationPreferencesStore {
    var isActiveConversationEnabled: Boolean
    var autoResumeOnScreenReopen: Boolean
    var hasCompletedFirstUseConsentAndConfig: Boolean
    var inactivityTimeoutOption: InactivityTimeoutOption
}

class InMemoryConversationPreferencesStore(
    override var isActiveConversationEnabled: Boolean = false,
    override var autoResumeOnScreenReopen: Boolean = false,
    override var hasCompletedFirstUseConsentAndConfig: Boolean = false,
    override var inactivityTimeoutOption: InactivityTimeoutOption = InactivityTimeoutOption.MINUTES_2
) : ConversationPreferencesStore

class SharedPreferencesConversationPreferencesStore(
    context: Context
) : ConversationPreferencesStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override var isActiveConversationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE_CONVERSATION_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ACTIVE_CONVERSATION_ENABLED, value).apply()
        }

    override var autoResumeOnScreenReopen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME_ON_SCREEN_REOPEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_RESUME_ON_SCREEN_REOPEN, value).apply()
        }

    override var hasCompletedFirstUseConsentAndConfig: Boolean
        get() = prefs.getBoolean(KEY_HAS_COMPLETED_FIRST_USE_CONSENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HAS_COMPLETED_FIRST_USE_CONSENT, value).apply()
        }

    override var inactivityTimeoutOption: InactivityTimeoutOption
        get() {
            val savedName = prefs.getString(KEY_INACTIVITY_TIMEOUT_OPTION, null)
            return InactivityTimeoutOption.entries.firstOrNull { it.name == savedName }
                ?: InactivityTimeoutOption.MINUTES_2
        }
        set(value) {
            prefs.edit().putString(KEY_INACTIVITY_TIMEOUT_OPTION, value.name).apply()
        }

    companion object {
        const val PREFS_NAME = "omnivoice_phase05_conversation_prefs"
        const val KEY_ACTIVE_CONVERSATION_ENABLED = "phase05_active_conversation_enabled"
        const val KEY_AUTO_RESUME_ON_SCREEN_REOPEN = "phase05_auto_resume_on_reopen"
        const val KEY_HAS_COMPLETED_FIRST_USE_CONSENT = "phase05_completed_first_use_consent"
        const val KEY_INACTIVITY_TIMEOUT_OPTION = "phase05_inactivity_timeout_option"
    }
}
