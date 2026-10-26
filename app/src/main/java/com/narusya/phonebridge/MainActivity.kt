package com.narusya.phonebridge

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var batteryText: TextView
    private lateinit var lastMessage: TextView
    private lateinit var petView: ImageView
    private var isServiceRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.VIBRATE
    )

    private val PERMISSION_REQUEST_CODE = 1001
    private val token = "narusya-bridge-2026"
    enum class PetState { IDLE, EXCITED, THINKING, SLEEPING }

    private var currentPetState = PetState.SLEEPING
    private var petEnabled = true
    private var reactToEvents = true
    private var animationSpeed = 1.0f // 1.0 = normal, 0.5 = slow, 2.0 = fast

    private fun setPetState(state: PetState) {
        if (!petEnabled) return
        currentPetState = state
        when (state) {
            PetState.IDLE -> {
                petView.setBackgroundResource(R.drawable.pet_idle)
                (petView.background as? AnimationDrawable)?.start()
            }
            PetState.EXCITED -> {
                petView.setBackgroundResource(R.drawable.pet_excited_anim)
                (petView.background as? AnimationDrawable)?.start()
                // Return to idle after 3 seconds
                handler.postDelayed({
                    if (currentPetState == PetState.EXCITED) {
                        setPetState(PetState.IDLE)
                    }
                }, 3000)
            }
            PetState.THINKING -> {
                petView.setBackgroundResource(R.drawable.pet_thinking_anim)
                (petView.background as? AnimationDrawable)?.start()
            }
            PetState.SLEEPING -> {
                petView.setBackgroundResource(R.drawable.pet_sleeping)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.toList().zip(grantResults.toList()).filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isEmpty()) {
                lastMessage.text = "All permissions granted! 🐍"
                updateStatus("Ready to start", false)
            } else {
                lastMessage.text = "Some permissions denied: ${denied.map { it.first.split(".").last() }.joinToString()}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        batteryText = findViewById(R.id.batteryText)
        lastMessage = findViewById(R.id.lastMessage)
        petView = findViewById(R.id.petView)

        handler.postDelayed({
            requestPermissionsIfNeeded()
        }, 500)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnToast = findViewById<Button>(R.id.btnToast)
        val btnPing = findViewById<Button>(R.id.btnPing)
        val btnLocation = findViewById<Button>(R.id.btnLocation)
        val btnCamera = findViewById<Button>(R.id.btnCamera)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnCall = findViewById<Button>(R.id.btnCall)
        val btnSpeak = findViewById<Button>(R.id.btnSpeak)

        btnCall.setOnClickListener {
            setPetState(PetState.THINKING)
            // Use a hardcoded test number or ask
            lastMessage.text = "Call: enter number via API /call"
            setPetState(PetState.IDLE)
        }

        btnSpeak.setOnClickListener {
            setPetState(PetState.THINKING)
            sendCommand("/speak", """{"text":"Hello from Narusya"}""") { result ->
                lastMessage.text = "Speak: $result"
                setPetState(PetState.IDLE)
            }
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Start pet animation
        startPetAnimation()

        btnStart.setOnClickListener {
            val intent = Intent(this, PhoneBridgeService::class.java)
            startForegroundService(intent)
            isServiceRunning = true
            updateStatus("Service running on port 8765", true)
            setPetState(PetState.IDLE)
            startPolling()
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, PhoneBridgeService::class.java)
            stopService(intent)
            isServiceRunning = false
            updateStatus("Service stopped", false)
            setPetState(PetState.SLEEPING)
            stopPolling()
        }

        btnToast.setOnClickListener {
            setPetState(PetState.THINKING)
            sendCommand("/toast", """{"message":"Hello from Narusya! 🐍"}""") {
                setPetState(PetState.IDLE)
            }
        }

        btnPing.setOnClickListener {
            setPetState(PetState.THINKING)
            sendCommand("/notify", """{"title":"Narusya","message":"Ping from phone!"}""") {
                setPetState(PetState.EXCITED)
            }
            lastMessage.text = "Ping sent!"
        }

        btnLocation.setOnClickListener {
            setPetState(PetState.THINKING)
            sendCommand("/location", null) { result ->
                lastMessage.text = "Location: $result"
                setPetState(PetState.IDLE)
            }
        }

        btnCamera.setOnClickListener {
            setPetState(PetState.THINKING)
            sendCommand("/camera", null) { result ->
                lastMessage.text = "Camera: $result"
                setPetState(PetState.IDLE)
            }
        }

        // Check if service is already running
        try {
            val url = URL("http://127.0.0.1:8765/device")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("X-Auth-Token", token)
            conn.connectTimeout = 2000
            if (conn.responseCode == 200) {
                isServiceRunning = true
                updateStatus("Service running on port 8765", true)
                startPolling()
            }
        } catch (e: Exception) {
            isServiceRunning = false
            updateStatus("Service not running", false)
        }
    }

    private fun startPetAnimation() {
        petView.setBackgroundResource(R.drawable.pet_idle)
        val anim = petView.background as? AnimationDrawable
        anim?.start()
    }

    private fun updateStatus(text: String, running: Boolean) {
        statusText.text = text
        statusDot.setBackgroundResource(if (running) R.drawable.status_dot_on else R.drawable.status_dot_off)
        if (running) {
            petView.setBackgroundResource(R.drawable.pet_idle)
            (petView.background as? AnimationDrawable)?.start()
        } else {
            petView.setBackgroundResource(R.drawable.pet_sleeping)
        }
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isServiceRunning) {
                    pollBattery()
                    handler.postDelayed(this, 10000)
                }
            }
        }, 5000)
    }

    private fun stopPolling() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun pollBattery() {
        Thread {
            try {
                val url = URL("http://127.0.0.1:8765/battery")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("X-Auth-Token", token)
                conn.connectTimeout = 3000
                val response = conn.inputStream.bufferedReader().readText()
                handler.post {
                    try {
                        val json = org.json.JSONObject(response)
                        val level = json.optInt("level", -1)
                        val charging = json.optBoolean("charging", false)
                        batteryText.text = "🔋 $level%${if (charging) " ⚡" else ""}"
                    } catch (e: Exception) {
                        batteryText.text = "🔋 --%"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    batteryText.text = "🔋 --%"
                }
            }
        }.start()
    }

    private fun sendCommand(endpoint: String, body: String?, callback: ((String) -> Unit)? = null) {
        Thread {
            try {
                val url = URL("http://127.0.0.1:8765$endpoint")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("X-Auth-Token", token)
                conn.connectTimeout = 5000
                conn.readTimeout = 15000
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(body.toByteArray())
                }
                val response = conn.inputStream.bufferedReader().readText()
                handler.post {
                    callback?.invoke(response)
                    if (callback == null) {
                        lastMessage.text = "Response: ${response.take(100)}"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    lastMessage.text = "Error: ${e.message}"
                }
            }
        }.start()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("pet_settings", MODE_PRIVATE)
        petEnabled = prefs.getBoolean("pet_enabled", true)
        reactToEvents = prefs.getBoolean("react_to_events", true)

        val options = arrayOf(
            "Pet: ${if (petEnabled) "ON" else "OFF"}",
            "React to events: ${if (reactToEvents) "ON" else "OFF"}",
            "Reset pet state"
        )

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Pet Settings 🐍")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        petEnabled = !petEnabled
                        prefs.edit().putBoolean("pet_enabled", petEnabled).apply()
                        if (petEnabled) {
                            petView.visibility = android.view.View.VISIBLE
                            setPetState(PetState.IDLE)
                            lastMessage.text = "Pet enabled! 🐍"
                        } else {
                            petView.visibility = android.view.View.INVISIBLE
                            lastMessage.text = "Pet disabled"
                        }
                        showSettingsDialog()
                    }
                    1 -> {
                        reactToEvents = !reactToEvents
                        prefs.edit().putBoolean("react_to_events", reactToEvents).apply()
                        lastMessage.text = "React to events: ${if (reactToEvents) "ON" else "OFF"}"
                        showSettingsDialog()
                    }
                    2 -> {
                        setPetState(PetState.IDLE)
                        lastMessage.text = "Pet state reset"
                    }
                }
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }
}