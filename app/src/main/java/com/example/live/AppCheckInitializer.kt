package com.example.live

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

// App Check contract keys defined by the Firebase SDK and AI Studio runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Configures Firebase App Check in [com.example.MainActivity.onCreate] BEFORE any Compose UI,
 * ViewModel, or Firebase AI Logic (`Firebase.ai`) access.
 *
 * Enforces Phase 03 App Check requirements:
 * 1. Safely ensures [FirebaseApp] is initialized first (`NEEDS_SETUP / CONFIG_ERROR` guard if unconfigured).
 * 2. Debug builds (`BuildConfig.DEBUG == true`) ONLY:
 *    - Synchronously persists `FIREBASE_APPCHECK_DEBUG_TOKEN` via `.commit()` when supplied by the
 *      AI Studio emulator launch Intent.
 *    - Installs [DebugAppCheckProviderFactory.getInstance] so the official Firebase App Check Debug SDK
 *      (`com.google.firebase.appcheck.debug.internal.DebugAppCheckProvider`) generates/loads the local
 *      debug token and emits the standard Firebase Console debug secret message in development Logcat.
 *    - Triggers [FirebaseAppCheck.getAppCheckToken] (`forceRefresh = false`) in debug mode so the
 *      standard Firebase SDK debug token log line appears immediately on launch in development logs.
 * 3. Release builds (`BuildConfig.DEBUG == false`) ONLY:
 *    - Preserves the production Play Integrity attestation path ([PlayIntegrityAppCheckProviderFactory]),
 *      never installing the debug provider in release builds.
 * 4. Never hardcodes or logs any permanent API key, debug token, or secret from custom code.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        val firebaseApp = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        } else {
            FirebaseApp.getInstance()
        }

        if (firebaseApp == null) {
            Log.w(
                TAG,
                "NEEDS_SETUP / CONFIG_ERROR: Default FirebaseApp is not configured. Skipping App Check install."
            )
            return
        }

        val firebaseAppCheck = FirebaseAppCheck.getInstance(firebaseApp)

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = firebaseApp.persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // CRITICAL: MUST use synchronous commit() instead of apply()
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret from launch Intent configured synchronously.")
            } else {
                Log.d(
                    TAG,
                    "No launch Intent debug token extra; DebugAppCheckProviderFactory will use/generate local debug secret and print it via DebugAppCheckProvider in Logcat."
                )
            }

            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            // Request initial token evaluation in debug builds so the official Firebase SDK
            // (com.google.firebase.appcheck.debug.internal.DebugAppCheckProvider) immediately emits
            // its standard debug token message in development logs.
            firebaseAppCheck.getAppCheckToken(false)
            Log.d(TAG, "DebugAppCheckProviderFactory installed for debug build.")
        } else {
            // Production release path: preserve Play Integrity attestation for registered Android app.
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed for production release build.")

            // Keep safe resource lookup check for enterprise environments that also supply a reCAPTCHA key.
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isBlank()) {
                    Log.w(TAG, "recaptcha_site_key resource string is blank; using PlayIntegrityAppCheckProviderFactory.")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check (${e.javaClass.simpleName})", e)
    }
}
