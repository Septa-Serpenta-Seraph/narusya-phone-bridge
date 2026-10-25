package com.narusya.phonebridge

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        statusText.text = "Service not running"

        startButton.setOnClickListener {
            val intent = Intent(this, PhoneBridgeService::class.java)
            startForegroundService(intent)
            statusText.text = "Service running on port 8765"
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, PhoneBridgeService::class.java)
            stopService(intent)
            statusText.text = "Service stopped"
        }
    }
}