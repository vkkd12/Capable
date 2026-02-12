package com.example.capable.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.capable.audio.TTSManager

/**
 * Manages SOS functionality:
 * - Gets current location
 * - Sends SMS with location + "Help me"
 * - Announces contact name via TTS
 * - Places phone call to top priority contact
 */
class SOSManager(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val contactStore: EmergencyContactStore
) {

    private var isSosActive = false

    /**
     * Trigger SOS sequence:
     * 1. Get location
     * 2. Send SMS to priority contact
     * 3. Announce "Calling <name>"
     * 4. Place call
     */
    fun triggerSOS(onComplete: (Boolean, String) -> Unit) {
        if (isSosActive) {
            Log.w(TAG, "SOS already active, ignoring duplicate trigger")
            return
        }

        val contact = contactStore.getTopPriorityContact()
        if (contact == null) {
            ttsManager.speak("No emergency contacts saved. Please add contacts first.", TTSManager.Priority.CRITICAL, skipQueue = true)
            onComplete(false, "No emergency contacts")
            return
        }

        isSosActive = true
        val allContacts = contactStore.getAllContacts()
        Log.i(TAG, "SOS triggered - ${allContacts.size} contacts, calling ${contact.name}")

        // Step 1: Get location
        val location = getLastKnownLocation()
        val locationText = if (location != null) {
            "https://maps.google.com/maps?q=${location.latitude},${location.longitude}"
        } else {
            "Location unavailable"
        }

        // Step 2: Send SMS to ALL contacts
        val smsMessage = "HELP ME! This is an emergency SOS from Capable app. $locationText"
        var smsSentCount = 0
        for (c in allContacts) {
            val sent = sendSMS(c.phone, smsMessage)
            if (sent) {
                smsSentCount++
                Log.i(TAG, "SMS sent to ${c.name}")
            } else {
                Log.w(TAG, "SMS failed to ${c.name}")
            }
        }
        Log.i(TAG, "SMS sent to $smsSentCount/${allContacts.size} contacts")

        // Step 3: Announce and call #1 priority contact only
        ttsManager.speak(
            "SOS activated. Message sent to ${allContacts.size} contacts. Calling ${contact.name}.",
            TTSManager.Priority.CRITICAL,
            skipQueue = true
        )

        // Step 4: Place call to #1 priority contact after TTS announcement
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            placeCall(contact.phone)
            isSosActive = false
            onComplete(true, "SOS sent to ${allContacts.size} contacts, calling ${contact.name}")
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted")
            return null
        }

        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try GPS first, then network
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLocation != null) return gpsLocation

            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (networkLocation != null) return networkLocation

            // Try fused provider
            locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location: ${e.message}")
            null
        }
    }

    private fun sendSMS(phoneNumber: String, message: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SMS permission not granted")
            return false
        }

        return try {
            val smsManager = SmsManager.getDefault()
            // Split message if too long
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
            false
        }
    }

    private fun placeCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Call permission not granted, using dial intent")
            // Fallback to dial intent (doesn't need permission)
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            return
        }

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
            Log.i(TAG, "Call placed to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call: ${e.message}")
        }
    }

    fun cancelSOS() {
        isSosActive = false
        ttsManager.speak("SOS cancelled", TTSManager.Priority.HIGH, skipQueue = true)
    }

    companion object {
        private const val TAG = "SOSManager"
    }
}
