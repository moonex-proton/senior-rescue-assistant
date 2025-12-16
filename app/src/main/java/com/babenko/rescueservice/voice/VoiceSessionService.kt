package com.babenko.rescueservice.voice

import android.app.Service
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.babenko.rescueservice.R
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.core.NotificationChannels
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.core.AssistantLifecycleManager

/**
 * Production-grade VoiceSessionService.
 *
 * Key behaviors:
 * - Runs short SR session as a foreground service.
 * - Honors SettingsManager.isStrictRecognitionEnabled(): when strict -> force EXTRA_LANGUAGE;
 *   when non-strict -> allow SR engine to auto-detect language.
 * - Single fallback attempt with alternative locale (en <-> ru) on NO_MATCH / TIMEOUT / empty results.
 * - Supports ACTION_RESTART_LISTENING: ConversationManager (or any main-process component) can send
 *   an intent with this action to request the running service to restart listening without recreating
 *   the service. This avoids stopping/starting the foreground service and preserves UX.
 *
 * Usage:
 * - Start a session: VoiceSessionService.startSession(context, timeoutSeconds, screenContext)
 * - Request restart of listening in the running service: send Intent with action = ACTION_RESTART_LISTENING
 *   targeted to the service (explicit component or package).
 */
class VoiceSessionService : Service() {
    private lateinit var settingsManager: SettingsManager
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentScreenContext: String? = null

    // Session timeout in milliseconds (set on start)
    private var sessionTimeoutMs: Long = 15_000L

    // Fallback control: allow a single fallback attempt per session
    private var triedFallback: Boolean = false

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_SESSION_TIMEOUT_SECONDS = "EXTRA_SESSION_TIMEOUT_SECONDS"
        private const val EXTRA_SCREEN_CONTEXT = "EXTRA_SCREEN_CONTEXT"
        private const val TTS_HANDOFF_DELAY_MS = 300 // previously used SR→TTS delay

        // Public action used by other processes to restart listening in the running service
        const val ACTION_RESTART_LISTENING = "com.babenko.rescueservice.ACTION_RESTART_LISTENING"

        /**
         * Public API for starting an SR session.
         */
        fun startSession(
            context: Context,
            timeoutSeconds: Int = 15,
            screenContext: String? = null
        ) {
            val intent = Intent(context, VoiceSessionService::class.java).apply {
                putExtra(EXTRA_SESSION_TIMEOUT_SECONDS, timeoutSeconds)
                putExtra(EXTRA_SCREEN_CONTEXT, screenContext)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(intent)
                }
                Logger.d("VoiceSessionService.startSession: requested start (timeout=$timeoutSeconds s)")
            } catch (e: Exception) {
                Logger.e(e, "Failed to start VoiceSessionService via startSession")
            }
        }
    }

    private val stopRunnable = Runnable {
        Logger.d("Session timeout reached. Stopping service.")
        processCommand("")
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Logger.d("onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            handler.removeCallbacks(stopRunnable)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Logger.d("onEndOfSpeech")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Error from server"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown speech recognizer error"
            }
            Logger.d("onError: $errorMessage (code=$error)")

            // If recoverable (no-match / timeout) and we haven't tried fallback -> attempt fallback
            if (!triedFallback && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                triedFallback = true
                val alt = alternativeLanguage()
                Logger.d("Recoverable error - attempting fallback recognition with locale: $alt")
                // Reset session timeout for fallback attempt
                handler.removeCallbacks(stopRunnable)
                handler.postDelayed(stopRunnable, sessionTimeoutMs)
                startFallbackListening(alt)
                return
            }

            // Otherwise send empty result
            processCommand("")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                // Combine all matches with a delimiter (VoiceSessionService uses this format)
                val combinedText = matches.joinToString(" ||| ")
                Logger.d("onResults (all): $combinedText")
                processCommand(combinedText)
            } else {
                Logger.d("onResults: empty matches")
                // Try a single fallback if available
                if (!triedFallback) {
                    triedFallback = true
                    val alt = alternativeLanguage()
                    Logger.d("Empty results — attempting fallback SR with locale $alt")
                    handler.removeCallbacks(stopRunnable)
                    handler.postDelayed(stopRunnable, sessionTimeoutMs)
                    startFallbackListening(alt)
                } else {
                    Logger.d("No matches after fallback. Sending empty command.")
                    processCommand("")
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d("VoiceSessionService created")
        settingsManager = SettingsManager.getInstance(this)
        initSpeechRecognizerIfNeeded()
    }

    private fun initSpeechRecognizerIfNeeded() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Logger.e(Exception("SpeechRecognizer not available on this device."))
            speechRecognizer = null
            return
        }
        if (speechRecognizer == null) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(recognitionListener)
                Logger.d("SpeechRecognizer created successfully in initSpeechRecognizerIfNeeded.")
            } catch (e: Exception) {
                Logger.e(e, "Failed to create SpeechRecognizer")
                speechRecognizer = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d("VoiceSessionService started (intent action=${intent?.action})")
        currentScreenContext = intent?.getStringExtra(EXTRA_SCREEN_CONTEXT)
        Logger.d("Service started with context present=${currentScreenContext != null}")

        // Ensure speech recognizer exists (handle case where service already running but SR was destroyed)
        initSpeechRecognizerIfNeeded()

        // Handle explicit restart-listening requests
        if (intent?.action == ACTION_RESTART_LISTENING) {
            Logger.d("Received ACTION_RESTART_LISTENING — restarting listening in existing service")
            // Reset fallback flag for new listening attempt
            triedFallback = false
            // Reset / extend session timeout
            val timeout = intent.getIntExtra(EXTRA_SESSION_TIMEOUT_SECONDS, (sessionTimeoutMs / 1000L).toInt())
            sessionTimeoutMs = timeout * 1000L
            handler.removeCallbacks(stopRunnable)
            handler.postDelayed(stopRunnable, sessionTimeoutMs)
            // start listening afresh
            startListening()
            return START_NOT_STICKY
        }

        if (speechRecognizer == null) {
            Logger.e(Exception("SpeechRecognizer was not created or is not available. Stopping service."))
            // Report the error via TTS, if possible
            val errorIntent = Intent(TtsBroadcastReceiver.ACTION_SPEAK).apply {
                putExtra(TtsBroadcastReceiver.EXTRA_TEXT_TO_SPEAK, getString(R.string.speech_recognizer_error))
            }
            sendBroadcast(errorIntent)
            stopSelf()
            return START_NOT_STICKY
        }

        // Default startSession flow
        startForegroundServiceWithNotification()
        val timeout = intent?.getIntExtra(EXTRA_SESSION_TIMEOUT_SECONDS, 15) ?: 15
        sessionTimeoutMs = timeout * 1000L

        // Reset fallback flag for this session
        triedFallback = false

        handler.postDelayed(stopRunnable, sessionTimeoutMs)
        handler.postDelayed({
            AssistantLifecycleManager.cancelFollowUpWindow()
            startListening()
        }, 500)

        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, NotificationChannels.VOICE_SESSION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Listening for your command...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun startListening() {
        val language = settingsManager.getLanguage()
        val strictSR = settingsManager.isStrictRecognitionEnabled()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (strictSR) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                Logger.d("startListening: forcing SR language $language")
            } else {
                Logger.d("startListening: strict SR disabled -> allowing auto language detection")
            }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(intent)
            Logger.d("startListening called (strictSR=$strictSR) for language $language")
        } catch (e: Exception) {
            Logger.e(e, "Failed to start listening")
            processCommand("")
        }
    }

    /**
     * Start a single fallback recognition attempt with a specific locale.
     * Caller should set triedFallback = true before calling to avoid loops.
     */
    private fun startFallbackListening(locale: String) {
        Logger.d("startFallbackListening: requesting SR with locale $locale")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(intent)
            Logger.d("Fallback recognition started for locale $locale")
        } catch (e: Exception) {
            Logger.e(e, "Failed to start fallback listening for locale $locale")
            processCommand("")
        }
    }

    private fun alternativeLanguage(): String {
        val current = settingsManager.getLanguage()
        return if (current.startsWith("en", ignoreCase = true) || current.startsWith("en")) "ru-RU" else "en-US"
    }

    /**
     * Finish SR and pass the text to the main process.
     */
    private fun processCommand(text: String) {
        handler.removeCallbacks(stopRunnable)
        // Stop and destroy recognizer (service is short-lived)
        try {
            speechRecognizer?.stopListening()
        } catch (_: Throwable) { }
        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) { }
        speechRecognizer = null

        // Broadcast to CommandReceiver in main process
        val broadcastIntent = Intent(CommandReceiver.ACTION_PROCESS_COMMAND).apply {
            component = ComponentName(this@VoiceSessionService, CommandReceiver::class.java)
            `package` = packageName
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(CommandReceiver.EXTRA_RECOGNIZED_TEXT, text)
            putExtra(CommandReceiver.EXTRA_SCREEN_CONTEXT, currentScreenContext)
        }
        Logger.d("Command recognized: '$text'. Broadcasting to CommandReceiver.")
        sendBroadcast(broadcastIntent)

        // Finish service
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopRunnable)
        if (speechRecognizer != null) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Throwable) { }
            try {
                speechRecognizer?.destroy()
            } catch (_: Throwable) { }
            speechRecognizer = null
        }
        Logger.d("VoiceSessionService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}