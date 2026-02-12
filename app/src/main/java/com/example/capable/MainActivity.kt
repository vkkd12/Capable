package com.example.capable

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.capable.audio.TTSManager
import com.example.capable.sos.EmergencyContactStore
import com.example.capable.sos.EmergencyContactsScreen
import com.example.capable.sos.SOSManager
import com.example.capable.ui.CameraScreen
import com.example.capable.ui.theme.CapableTheme

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var hasAllPermissions by mutableStateOf(false)

    // Volume up double-tap tracking
    private var lastVolumeUpTime = 0L
    private val DOUBLE_TAP_THRESHOLD = 500L // ms

    private lateinit var contactStore: EmergencyContactStore
    private var sosManager: SOSManager? = null
    private var ttsManagerRef: TTSManager? = null

    // Navigation state
    private var currentScreen by mutableStateOf("camera") // "camera" or "contacts"

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasAllPermissions = permissions.all { it.value }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on for accessibility
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        contactStore = EmergencyContactStore(this)

        // Check permissions
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAllPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        setContent {
            CapableTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        !hasCameraPermission -> {
                            PermissionScreen(
                                onRequestPermission = {
                                    permissionLauncher.launch(requiredPermissions)
                                }
                            )
                        }
                        currentScreen == "contacts" -> {
                            EmergencyContactsScreen(
                                contactStore = contactStore,
                                onBack = { currentScreen = "camera" }
                            )
                        }
                        else -> {
                            CameraScreen(
                                modifier = Modifier.fillMaxSize(),
                                onNavigateToContacts = { currentScreen = "contacts" },
                                onTTSReady = { tts ->
                                    ttsManagerRef = tts
                                    sosManager = SOSManager(this@MainActivity, tts, contactStore)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeUpTime < DOUBLE_TAP_THRESHOLD) {
                // Double-tap detected!
                Log.i("MainActivity", "Volume Up double-tap detected - triggering SOS!")
                lastVolumeUpTime = 0L // Reset
                triggerSOS()
                return true // Consume the event
            }
            lastVolumeUpTime = now
            return true // Consume single press too (prevents volume change during SOS use)
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun triggerSOS() {
        val sos = sosManager
        if (sos == null) {
            ttsManagerRef?.speak(
                "SOS not ready. Please wait for app to initialize.",
                TTSManager.Priority.CRITICAL,
                skipQueue = true
            )
            return
        }

        // Check if SMS/Call permissions were granted
        if (!hasAllPermissions) {
            permissionLauncher.launch(requiredPermissions)
            ttsManagerRef?.speak(
                "Please grant SMS and call permissions for SOS.",
                TTSManager.Priority.CRITICAL,
                skipQueue = true
            )
            return
        }

        sos.triggerSOS { success, message ->
            Log.i("MainActivity", "SOS result: success=$success, message=$message")
        }
    }
}

@Composable
fun PermissionScreen(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Capable",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This app helps visually impaired users navigate safely by detecting obstacles and providing audio feedback.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Camera, Location, SMS, and Call permissions are required for full functionality.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Grant Permissions")
        }
    }
}