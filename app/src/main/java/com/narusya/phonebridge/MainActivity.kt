package com.narusya.phonebridge

import android.app.Activity
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
    private val token = "narusya-bridge-2026"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        batteryText = findViewById(R.id.batteryText)
        lastMessage = findViewById(R.id.lastMessage)
        petView = findViewById(R.id.petView)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnToast = findViewById<Button>(R.id.btnToast)
        val btnPing = findViewById<Button>(R.id.btnPing)
        val btnLocation = findViewById<Button>(R.id.btnLocation)
        val btnCamera = findViewById<Button>(R.id.btnCamera)

        // Start pet animation
        startPetAnimation()

        btnStart.setOnClickListener {
            val intent = Intent(this, PhoneBridgeService::class.java)
            startForegroundService(intent)
            isServiceRunning = true
            updateStatus("Service running on port 8765", true)
            startPolling()
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, PhoneBridgeService::class.java)
            stopService(intent)
            isServiceRunning = false
            updateStatus("Service stopped", false)
            stopPolling()
        }

        btnToast.setOnClickListener {
            sendCommand("/toast", """{"message":"Hello from Narusya! 🐍"}""")
        }

        btnPing.setOnClickListener {
            sendCommand("/notify", """{"title":"Narusya","message":"Ping from phone!"}""")
            lastMessage.text = "Ping sent!"
        }

        btnLocation.setOnClickListener {
            sendCommand("/location", null) { result ->
                lastMessage.text = "Location: $result"
            }
        }

        btnCamera.setOnClickListener {
            sendCommand("/camera", null) { result ->
                lastMessage.text = "Camera: $result"
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
                batteryText.text = "🔋 --%"
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
}