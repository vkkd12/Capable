package com.example.capable.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Text-to-Speech manager for audio feedback
 * Prioritizes urgent warnings over regular announcements
 */
class TTSManager(
    context: Context,
    private val onInitialized: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingMessages = ConcurrentLinkedQueue<TTSMessage>()
    private var isSpeaking = false

    // Cooldown to prevent spam
    private var lastSpokenMessages = mutableMapOf<String, Long>()
    private val messageCooldownMs = 5000L // Don't repeat same message within 5 seconds
    
    // Track last announcement time for any object to prevent rapid-fire announcements
    private var lastAnyAnnouncementTime = 0L
    private val minAnnouncementIntervalMs = 3000L // Minimum 3 seconds between any announcements

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
            } else {
                isInitialized = true
                setupUtteranceListener()
                onInitialized()
                Log.i(TAG, "TTS initialized successfully")

                // Process any pending messages
                processPendingMessages()
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                processPendingMessages()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                processPendingMessages()
            }
        })
    }

    /**
     * Speak a message with given priority
     * @param message The text to speak
     * @param priority Priority level (higher = more urgent)
     * @param skipQueue If true, interrupts current speech for urgent warnings
     */
    fun speak(message: String, priority: Priority = Priority.NORMAL, skipQueue: Boolean = false) {
        if (!isInitialized) {
            pendingMessages.add(TTSMessage(message, priority))
            return
        }

        val now = System.currentTimeMillis()
        
        // Check global announcement interval (except for CRITICAL)
        if (priority != Priority.CRITICAL && now - lastAnyAnnouncementTime < minAnnouncementIntervalMs) {
            return // Too soon since last announcement
        }

        // Check cooldown for this specific message
        val messageKey = message.lowercase()
        val lastSpoken = lastSpokenMessages[messageKey] ?: 0

        if (now - lastSpoken < messageCooldownMs && priority != Priority.CRITICAL) {
            return // Skip duplicate message
        }

        lastSpokenMessages[messageKey] = now
        lastAnyAnnouncementTime = now

        // Clean up old entries
        lastSpokenMessages = lastSpokenMessages.filter { now - it.value < 30000 }.toMutableMap()

        if (skipQueue || priority == Priority.CRITICAL) {
            // Interrupt current speech for urgent messages only
            tts?.stop()
            speakImmediate(message, priority)
        } else if (!isSpeaking) {
            speakImmediate(message, priority)
        }
        // Drop non-critical messages while speaking to prevent stutter/buildup
        // Don't queue - this prevents the audio glitching from rapid detections
    }

    private fun speakImmediate(message: String, priority: Priority) {
        val params = android.os.Bundle()

        // Adjust speech rate based on priority
        val rate = when (priority) {
            Priority.CRITICAL -> 1.3f
            Priority.HIGH -> 1.2f
            Priority.NORMAL -> 1.0f
            Priority.LOW -> 0.9f
        }
        tts?.setSpeechRate(rate)

        val utteranceId = "msg_${System.currentTimeMillis()}"
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        isSpeaking = true
    }

    private fun processPendingMessages() {
        if (!isInitialized || isSpeaking) return

        // Get highest priority message
        val message = pendingMessages
            .sortedByDescending { it.priority.ordinal }
            .firstOrNull()

        if (message != null) {
            pendingMessages.remove(message)
            speakImmediate(message.text, message.priority)
        }
    }

    /**
     * Announce detected object with distance
     */
    fun announceObject(objectName: String, distance: Distance, direction: Direction) {
        val directionText = when (direction) {
            Direction.LEFT -> "on your left"
            Direction.CENTER -> "ahead"
            Direction.RIGHT -> "on your right"
        }

        val (message, priority) = when (distance) {
            Distance.VERY_CLOSE -> "$objectName very close $directionText" to Priority.CRITICAL
            Distance.CLOSE -> "$objectName nearby $directionText" to Priority.HIGH
            Distance.MEDIUM -> "$objectName $directionText" to Priority.NORMAL
            Distance.FAR -> "$objectName far $directionText" to Priority.LOW
        }

        speak(message, priority, skipQueue = distance == Distance.VERY_CLOSE)
    }

    fun stop() {
        tts?.stop()
        pendingMessages.clear()
        isSpeaking = false
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    enum class Priority {
        LOW, NORMAL, HIGH, CRITICAL
    }

    enum class Distance {
        FAR, MEDIUM, CLOSE, VERY_CLOSE
    }

    enum class Direction {
        LEFT, CENTER, RIGHT
    }

    private data class TTSMessage(
        val text: String,
        val priority: Priority
    )

    companion object {
        private const val TAG = "TTSManager"
    }
}
