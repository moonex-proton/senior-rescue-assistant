package com.babenko.rescueservice.data

import android.content.Context
import android.content.SharedPreferences
import com.babenko.rescueservice.R

/**
 * Production-grade SettingsManager.
 *
 * Responsibilities:
 * - store/load language, speech rate, user name
 * - store flag for strict speech recognition behavior (controls whether SR is forced
 *   to the app's current language or left to auto-detection)
 * - safe reload of SharedPreferences for multi-process access
 *
 * NOTE: DEFAULT_STRICT_SR controls default behavior for speech recognition strictness.
 * Set to false to allow auto-detection by default, true to force SR language by default.
 */
class SettingsManager private constructor(private var context: Context) {

    private lateinit var prefs: SharedPreferences

    init {
        loadSettings()
    }

    /**
     * Forces a reload of the SharedPreferences from disk.
     * This is important for correct behavior when multiple processes access prefs.
     */
    fun loadSettings() {
        val prefName = "${context.packageName}_preferences"
        // MODE_MULTI_PROCESS used historically to try to observe changes from other processes.
        prefs = context.getSharedPreferences(prefName, Context.MODE_MULTI_PROCESS)
    }

    // --- Language ---
    fun saveLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun getLanguage(): String {
        loadSettings()
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    // --- Strict SR control ---
    /**
     * If true, VoiceSessionService will force the SR engine to the stored language
     * via RecognizerIntent.EXTRA_LANGUAGE. If false, VoiceSessionService will not
     * set EXTRA_LANGUAGE, allowing the recognizer to auto-detect language (useful
     * for language-switching commands).
     */
    fun isStrictRecognitionEnabled(): Boolean {
        loadSettings()
        return prefs.getBoolean(KEY_STRICT_SR, DEFAULT_STRICT_SR)
    }

    fun setStrictRecognitionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT_SR, enabled).apply()
    }

    // --- Speech rate ---
    fun saveSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, rate).apply()
    }

    fun getSpeechRate(): Float {
        return prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    // --- User name ---
    fun saveUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getUserName(): String {
        loadSettings()
        val storedName = prefs.getString(KEY_USER_NAME, null)
        if (storedName != null) {
            return storedName
        }
        return context.getString(R.string.default_user_name)
    }

    /**
     * Returns true if the user name has been set before.
     * Used to determine first-run flows.
     */
    fun isUserNameSet(): Boolean {
        return prefs.contains(KEY_USER_NAME)
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_USER_NAME = "user_name"

        // Controls strict SR behavior. Set false to allow auto language detection by default.
        private const val KEY_STRICT_SR = "strict_speech_recognition"
        private const val DEFAULT_STRICT_SR = false

        // Defaults
        const val DEFAULT_LANGUAGE: String = "en-US"
        const val DEFAULT_SPEECH_RATE: Float = 1.0f

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Update stored context so the singleton can reload localized resources if needed.
         */
        fun updateContext(newContext: Context) {
            INSTANCE?.let {
                it.context = newContext
            }
        }
    }
}