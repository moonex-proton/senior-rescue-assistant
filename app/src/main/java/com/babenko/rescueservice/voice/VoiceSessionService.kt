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
 * A foreground service for speech recognition, running in a separate process (:voice).
 */
class VoiceSessionService : Service() {
    private lateinit var settingsManager: SettingsManager
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentScreenContext: String? = null

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_SESSION_TIMEOUT_SECONDS = "EXTRA_SESSION_TIMEOUT_SECONDS"
        private const val EXTRA_SCREEN_CONTEXT = "EXTRA_SCREEN_CONTEXT"
        private const val TTS_HANDOFF_DELAY_MS = 300 // previously used SR→TTS delay

        // НОВАЯ СТРОКА: Уникальный экшен для команды перезапуска прослушивания
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
            Logger.d("onError: $errorMessage")

            // On any error, send an empty result
            processCommand("")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                // Combine all matches with a delimiter to let CommandParser check them
                val combinedText = matches.joinToString(" ||| ")
                Logger.d("onResults (all): $combinedText")
                processCommand(combinedText)
            } else {
                Logger.d("onResults: No matches found.")
                processCommand("")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d("VoiceSessionService created")
        settingsManager = SettingsManager.getInstance(this)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Logger.e(Exception("SpeechRecognizer not available on this device."))
            speechRecognizer = null
            return
        }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            Logger.d("SpeechRecognizer created successfully.")
        } catch (e: Exception) {
            Logger.e(e, "Failed to create SpeechRecognizer in onCreate.")
            speechRecognizer = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d("VoiceSessionService started")
        currentScreenContext = intent?.getStringExtra(EXTRA_SCREEN_CONTEXT)
        Logger.d("Service started with context: ${currentScreenContext != null}")

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
        startForegroundServiceWithNotification()

        // НОВЫЙ БЛОК: Обработка команды перезапуска (ACTION_RESTART_LISTENING)
        if (intent?.action == ACTION_RESTART_LISTENING) {
            Logger.d("onStartCommand: Received ACTION_RESTART_LISTENING (Dialogue mode).")
            // Отменяем таймер сессии, чтобы он не прервал диалог, пока пользователь думает.
            handler.removeCallbacks(stopRunnable)

            // Начинаем прослушивание с небольшой задержкой, чтобы TTS успел договорить вопрос LLM.
            handler.postDelayed({
                // Закрываем окно наблюдения, если оно было активно, чтобы SR не прервался.
                AssistantLifecycleManager.cancelFollowUpWindow()
                startListening()
            }, TTS_HANDOFF_DELAY_MS.toLong())

            return START_NOT_STICKY
        }

        // СТАРЫЙ БЛОК (для обычного запуска через startSession):
        val timeout = intent?.getIntExtra(EXTRA_SESSION_TIMEOUT_SECONDS, 15) ?: 15
        handler.postDelayed(stopRunnable, timeout * 1000L)
        handler.postDelayed({
            // Close the follow-up window before starting the voice session
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
        val currentLang = settingsManager.getLanguage()
        // Determine the secondary language for command recognition (switching language)
        val otherLang = if (currentLang.startsWith("ru", ignoreCase = true)) "en-US" else "ru-RU"

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
            // Restore dual-language support to allow "Switch language" commands in the target language
            // Using literal string key to avoid compilation issues on older SDKs/IDE configurations
            putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf(otherLang))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        speechRecognizer?.startListening(intent)
        Logger.d("startListening: Primary=$currentLang, Secondary=$otherLang")
    }

    /**
     * Finishes SR and passes the text to the main process WITHOUT delay.
     * We use an explicit broadcast to CommandReceiver in the main process.
     */
    private fun processCommand(text: String) {
        handler.removeCallbacks(stopRunnable)
        // 1) Correctly finish SR
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        // 2) EXPLICIT broadcast to the main process on CommandReceiver
        val broadcastIntent = Intent(CommandReceiver.ACTION_PROCESS_COMMAND).apply {
            component = ComponentName(this@VoiceSessionService, CommandReceiver::class.java)
            `package` = packageName
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(CommandReceiver.EXTRA_RECOGNIZED_TEXT, text)
            putExtra(CommandReceiver.EXTRA_SCREEN_CONTEXT, currentScreenContext)
        }
        Logger.d("Command recognized: '$text'. Broadcasting explicitly to CommandReceiver.")
        sendBroadcast(broadcastIntent)
        // 3) Finish the service after sending
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(stopRunnable)
        if (speechRecognizer != null) {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
        Logger.d("VoiceSessionService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}