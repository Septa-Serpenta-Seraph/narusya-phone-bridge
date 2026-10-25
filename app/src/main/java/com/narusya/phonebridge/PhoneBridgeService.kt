package com.narusya.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneBridgeService : Service() {
    private var server: BridgeServer? = null
    private val port = 8765
    private val authToken = "narusya-bridge-2026"

    companion object {
        const val CHANNEL_ID = "phone_bridge_channel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification())
        server = BridgeServer(this, port, authToken)
        server?.start()
        Log.i("PhoneBridge", "Server started on port $port")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Narusya Phone Bridge", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Narusya Phone Bridge")
            .setContentText("Running on port $port")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}

class BridgeServer(
    private val context: Context,
    port: Int,
    private val authToken: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters

        // Auth check
        val token = session.headers["x-auth-token"] ?: params["token"]?.firstOrNull()
        if (token != authToken) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                """{"error":"unauthorized"}""")
        }

        return when (uri) {
            "/device" -> handleDevice()
            "/battery" -> handleBattery()
            "/texts" -> handleTexts(params)
            "/files" -> handleFiles(params)
            "/apps" -> handleApps()
            "/screenshot" -> handleScreenshot()
            "/notifications" -> handleNotifications()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                """{"error":"not found","uri":"$uri"}""")
        }
    }

    private fun handleDevice(): Response {
        val json = JSONObject()
        json.put("model", android.os.Build.MODEL)
        json.put("brand", android.os.Build.BRAND)
        json.put("android_version", android.os.Build.VERSION.RELEASE)
        json.put("sdk", android.os.Build.VERSION.SDK_INT)
        json.put("manufacturer", android.os.Build.MANUFACTURER)
        return jsonResponse(json)
    }

    private fun handleBattery(): Response {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val json = JSONObject()
        json.put("level", bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY))
        json.put("charging", bm.isCharging)
        return jsonResponse(json)
    }

    private fun handleTexts(params: Map<String, List<String>>): Response {
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 20
        val json = JSONObject()
        val arr = JSONArray()

        try {
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date", "read"),
                null, null, "date DESC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val obj = JSONObject()
                    obj.put("from", it.getString(0) ?: "")
                    obj.put("body", it.getString(1) ?: "")
                    obj.put("date", it.getLong(2))
                    obj.put("read", it.getInt(3) == 1)
                    arr.put(obj)
                    count++
                }
            }
        } catch (e: Exception) {
            json.put("error", e.message)
        }

        json.put("messages", arr)
        json.put("count", arr.length())
        return jsonResponse(json)
    }

    private fun handleFiles(params: Map<String, List<String>>): Response {
        val path = params["path"]?.firstOrNull() ?: "/sdcard"
        val json = JSONObject()
        val arr = JSONArray()

        try {
            val dir = File(path)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    val obj = JSONObject()
                    obj.put("name", f.name)
                    obj.put("path", f.absolutePath)
                    obj.put("is_dir", f.isDirectory)
                    obj.put("size", f.length())
                    arr.put(obj)
                }
            } else if (dir.isFile) {
                // Return file content if small enough
                if (dir.length() < 100000) {
                    json.put("content", dir.readText())
                } else {
                    json.put("size", dir.length())
                    json.put("note", "file too large, use pull")
                }
            }
        } catch (e: Exception) {
            json.put("error", e.message)
        }

        json.put("entries", arr)
        json.put("path", path)
        return jsonResponse(json)
    }

    private fun handleApps(): Response {
        val json = JSONObject()
        val arr = JSONArray()
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        for (app in packages) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                val obj = JSONObject()
                obj.put("package", app.packageName)
                obj.put("name", pm.getApplicationLabel(app).toString())
                arr.put(obj)
            }
        }
        json.put("apps", arr)
        json.put("count", arr.length())
        return jsonResponse(json)
    }

    private fun handleScreenshot(): Response {
        // Screenshot requires MediaProjection which needs user consent
        // Return a message explaining this limitation
        val json = JSONObject()
        json.put("note", "Screenshot capture requires MediaProjection consent. Use ADB screencap as fallback, or grant screen capture permission in app settings.")
        json.put("fallback", "adb shell screencap -p /sdcard/screen.png")
        return jsonResponse(json)
    }

    private fun handleNotifications(): Response {
        val json = JSONObject()
        json.put("note", "Notification access requires NotificationListenerService. Not yet implemented. Use: adb shell dumpsys notification")
        return jsonResponse(json)
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
    }
}