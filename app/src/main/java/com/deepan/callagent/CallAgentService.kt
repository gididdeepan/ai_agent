package com.deepan.callagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale

/**
 * Flow:
 * call rings -> "Sir, <name> kitta irundhu call varudhu, attend pannattuma?"
 * yes -> answer the call | no -> cut the call | not clear -> ask once more, then let it ring.
 */
class CallAgentService : Service() {

    companion object {
        const val ACTION_START = "com.deepan.callagent.START"
        const val ACTION_STOP = "com.deepan.callagent.STOP"
        const val ACTION_CALL = "com.deepan.callagent.CALL"
        private const val CHANNEL_ID = "call_agent"
        private const val TAG = "CallAgent"

        @Volatile
        var running = false

        // Words we understand (Tamil script, Tanglish and English)
        private val NO_WORDS = listOf(
            "no", "nope", "cut", "reject", "disconnect", "busy",
            "vendam", "vendaam", "venam", "vendam", "illa", "illai",
            "வேண்டாம்", "வேணாம்", "இல்ல", "இல்லை", "நோ", "கட்", "கட் பண்ணு"
        )
        private val YES_WORDS = listOf(
            "yes", "yeah", "yep", "ok", "okay", "attend", "answer", "pick",
            "aama", "aam", "sari", "pannu", "pesu", "edu",
            "ஆமா", "ஆம்", "சரி", "எஸ்", "ஓகே", "அட்டென்ட்", "எடு", "பேசு", "பண்ணு"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tamilOk = false
    private var recognizer: SpeechRecognizer? = null

    private var ringing = false
    private var scheduled = false
    private var handled = false
    private var attempts = 0
    private var pendingNumber: String? = null
    private var callerName: String? = null
    private var callerDigits: String? = null

    // ---------------- Service lifecycle ----------------
    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ta", "IN"))
                tamilOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!tamilOk) tts?.setLanguage(Locale("en", "IN"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { startListening() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { startListening() }
                    }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALL -> onCallState(
                intent.getStringExtra("state"),
                intent.getStringExtra("number")
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        resetCall()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Call agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startAsForeground() {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Agent running")
            .setContentText("Call vandha naan sollren")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------- Call state ----------------
    private fun onCallState(state: String?, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!number.isNullOrBlank()) pendingNumber = number
                if (!ringing) {
                    ringing = true
                    handled = false
                    attempts = 0
                }
                // The number often arrives in a 2nd broadcast, so wait a moment.
                if (!scheduled) {
                    scheduled = true
                    main.postDelayed({ beginAsking() }, 1200)
                }
            }
            else -> resetCall()   // OFFHOOK (answered) or IDLE (ended)
        }
    }

    private fun resetCall() {
        ringing = false
        scheduled = false
        handled = false
        attempts = 0
        pendingNumber = null
        callerName = null
        callerDigits = null
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        recognizer?.destroy()
        recognizer = null
    }

    // ---------------- Ask the user ----------------
    private fun beginAsking() {
        if (!ringing || handled) return
        val number = pendingNumber
        callerName = lookupName(number)
        callerDigits = number?.filter { it.isDigit() }?.takeLast(10)?.toList()?.joinToString(" ")
        speak(questionText())
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            startListening()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ask")
    }

    private fun questionText(): String {
        val name = callerName
        val digits = callerDigits
        return if (tamilOk) {
            val who = when {
                name != null -> name
                digits != null -> "தெரியாத number $digits"
                else -> "தெரியாத number"
            }
            "சார், $who கிட்ட இருந்து call வருது. Attend பண்ணட்டுமா?"
        } else {
            val who = when {
                name != null -> name
                digits != null -> "an unknown number $digits"
                else -> "an unknown number"
            }
            "Sir, a call is coming from $who. Shall I attend it?"
        }
    }

    private fun retryText() =
        if (tamilOk) "சார், attend பண்ணட்டுமா, வேண்டாமா?" else "Sir, attend or cut?"

    // ---------------- Listen for yes / no ----------------
    private fun startListening() {
        if (!ringing || handled) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            startListening(i)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            Log.d(TAG, "Recognizer error $error")
            main.post { retryOrGiveUp() }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            Log.d(TAG, "Heard: $texts")
            main.post {
                when (decide(texts)) {
                    true -> { handled = true; acceptCall() }
                    false -> { handled = true; rejectCall() }
                    null -> retryOrGiveUp()
                }
            }
        }
    }

    private fun retryOrGiveUp() {
        if (!ringing || handled) return
        attempts++
        if (attempts <= 1) speak(retryText())
        else Log.d(TAG, "No clear answer, letting the call ring")
    }

    /** true = attend, false = cut, null = not clear */
    private fun decide(texts: List<String>): Boolean? {
        val all = texts.joinToString(" ").lowercase()
        val tokens = all.split(Regex("[\\s,.!?]+")).filter { it.isNotEmpty() }.toSet()

        fun hit(words: List<String>) = words.any { w ->
            if (w.all { it.code < 128 }) tokens.contains(w) else all.contains(w)
        }
        if (hit(NO_WORDS)) return false      // check "no" first, so "cut pannu" is not taken as yes
        if (hit(YES_WORDS)) return true
        return null
    }

    // ---------------- Answer / cut ----------------
    @Suppress("DEPRECATION")
    private fun acceptCall() {
        recognizer?.destroy()
        recognizer = null
        try {
            getSystemService(TelecomManager::class.java).acceptRingingCall()
        } catch (e: Exception) {
            Log.e(TAG, "Could not answer", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun rejectCall() {
        recognizer?.destroy()
        recognizer = null
        try {
            getSystemService(TelecomManager::class.java).endCall()
        } catch (e: Exception) {
            Log.e(TAG, "Could not cut the call", e)
        }
    }

    // ---------------- Contact name ----------------
    private fun lookupName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
            null
        }
    }
}
