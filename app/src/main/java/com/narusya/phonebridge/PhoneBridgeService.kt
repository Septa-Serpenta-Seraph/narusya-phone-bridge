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
            "/notify" -> handleNotify(session)
            "/open" -> handleOpen(session)
            "/sms" -> handleSms(session)
            "/clipboard" -> handleClipboard(session)
            "/vibrate" -> handleVibrate(session)
            "/toast" -> handleToast(session)
            "/location" -> handleLocation()
            "/camera" -> handleCamera()
            "/call" -> handleCall(session)
            "/speak" -> handleSpeak(session)
            "/call_status" -> handleCallStatus()
            "/play_audio" -> handlePlayAudio(session)
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

    private fun handleNotify(session: IHTTPSession): Response {
        // Parse POST body
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = JSONObject(body.ifEmpty { "{}" })
        val title = json.optString("title", "Narusya")
        val message = json.optString("body", "Hello from Narusya")

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "narusya_notify"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Narusya Notifications", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }

        val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)

        val resp = JSONObject()
        resp.put("status", "sent")
        resp.put("title", title)
        return jsonResponse(resp)
    }

    private fun handleOpen(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = JSONObject(body.ifEmpty { "{}" })
        val pkg = json.optString("package", "")
        val resp = JSONObject()

        if (pkg.isEmpty()) {
            resp.put("error", "package name required")
            return jsonResponse(resp)
        }

        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                resp.put("status", "opened")
                resp.put("package", pkg)
            } else {
                resp.put("error", "app not found or not launchable")
            }
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleSms(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = JSONObject(body.ifEmpty { "{}" })
        val to = json.optString("to", "")
        val message = json.optString("message", "")
        val resp = JSONObject()

        if (to.isEmpty() || message.isEmpty()) {
            resp.put("error", "to and message required")
            return jsonResponse(resp)
        }

        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            smsManager.sendTextMessage(to, null, message, null, null)
            resp.put("status", "sent")
            resp.put("to", to)
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleClipboard(session: IHTTPSession): Response {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val resp = JSONObject()

        if (session.method == Method.GET) {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                resp.put("text", clip.getItemAt(0).text?.toString() ?: "")
            } else {
                resp.put("text", "")
            }
        } else {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            val json = JSONObject(body.ifEmpty { "{}" })
            val text = json.optString("text", "")
            val clip = android.content.ClipData.newPlainText("Narusya", text)
            cm.setPrimaryClip(clip)
            resp.put("status", "set")
            resp.put("text", text)
        }
        return jsonResponse(resp)
    }

    private fun handleVibrate(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = JSONObject(body.ifEmpty { "{}" })
        val duration = json.optInt("duration", 500)
        val resp = JSONObject()

        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration.toLong())
            }
            resp.put("status", "vibrated")
            resp.put("duration", duration)
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleToast(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = JSONObject(body.ifEmpty { "{}" })
        val message = json.optString("message", "")
        val resp = JSONObject()

        if (message.isEmpty()) {
            resp.put("error", "message required")
            return jsonResponse(resp)
        }

        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
            resp.put("status", "shown")
            resp.put("message", message)
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleLocation(): Response {
        val resp = JSONObject()
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = lm.getProviders(true)
            
            // Try to get last known location first
            var location: android.location.Location? = null
            for (provider in providers) {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null && (location == null || loc.accuracy < location.accuracy)) {
                    location = loc
                }
            }

            // If no cached location, request a fresh one
            if (location == null) {
                val latch = java.util.concurrent.CountDownLatch(1)
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
                        location = loc
                        latch.countDown()
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }

                // Request from GPS or network
                val provider = if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    android.location.LocationManager.GPS_PROVIDER
                } else if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    android.location.LocationManager.NETWORK_PROVIDER
                } else {
                    null
                }

                if (provider != null) {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    lm.removeUpdates(listener)
                }
            }

            val finalLocation = location
            if (finalLocation != null) {
                resp.put("latitude", finalLocation.latitude)
                resp.put("longitude", finalLocation.longitude)
                resp.put("accuracy", finalLocation.accuracy.toDouble())
                resp.put("provider", finalLocation.provider)
                resp.put("timestamp", finalLocation.time)
            } else {
                resp.put("error", "no location available - GPS may need more time")
            }
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleCall(session: IHTTPSession): Response {
        val resp = JSONObject()
        try {
            val body = parseBody(session)
            val number = body.optString("number", "")
            if (number.isEmpty()) {
                resp.put("error", "number required")
                return jsonResponse(resp)
            }
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = android.net.Uri.parse("tel:$number")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            resp.put("status", "calling")
            resp.put("number", number)
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleSpeak(session: IHTTPSession): Response {
        val resp = JSONObject()
        try {
            val body = parseBody(session)
            val text = body.optString("text", "")
            if (text.isEmpty()) {
                resp.put("error", "text required")
                return jsonResponse(resp)
            }
            // Use a thread to init TTS and speak
            Thread {
                try {
                    val tts = android.speech.tts.TextToSpeech(context) { status ->
                        // init callback
                    }
                    // Wait for init
                    var ready = false
                    var attempts = 0
                    while (!ready && attempts < 50) {
                        Thread.sleep(100)
                        attempts++
                        // Check if TTS is ready by trying to speak
                        if (tts.voices != null) ready = true
                    }
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.mode = android.media.AudioManager.MODE_IN_CALL
                    am.isSpeakerphoneOn = true
                    tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "narusya_tts")
                    ttsMap["active"] = tts
                } catch (e: Exception) {
                    Log.e("PhoneBridge", "TTS error: ${e.message}")
                }
            }.start()
            resp.put("status", "speaking")
            resp.put("text", text.take(100))
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleCallStatus(): Response {
        val resp = JSONObject()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val state = when (tm.callState) {
                android.telephony.TelephonyManager.CALL_STATE_IDLE -> "idle"
                android.telephony.TelephonyManager.CALL_STATE_RINGING -> "ringing"
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                else -> "unknown"
            }
            resp.put("call_state", state)
            resp.put("in_call", state != "idle")
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private val ttsMap = mutableMapOf<String, android.speech.tts.TextToSpeech>()

    private fun parseBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val body = map["postData"] ?: ""
        return try { JSONObject(body) } catch (e: Exception) { JSONObject() }
    }

    private fun handlePlayAudio(session: IHTTPSession): Response {
        val resp = JSONObject()
        try {
            val body = parseBody(session)
            val audioBase64 = body.optString("audio", "")
            val format = body.optString("format", "wav")
            if (audioBase64.isEmpty()) {
                resp.put("error", "audio (base64) required")
                return jsonResponse(resp)
            }
            val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
            val tempFile = File(context.cacheDir, "play_audio.$format")
            tempFile.writeBytes(audioBytes)

            Thread {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.mode = android.media.AudioManager.MODE_IN_CALL
                    am.isSpeakerphoneOn = true
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(tempFile.absolutePath)
                    mp.setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                    mp.prepare()
                    mp.start()
                    mp.setOnCompletionListener { mp.release() }
                } catch (e: Exception) {
                    Log.e("PhoneBridge", "Play audio error: ${e.message}")
                }
            }.start()

            resp.put("status", "playing")
            resp.put("bytes", audioBytes.size)
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun handleCamera(): Response {
        val resp = JSONObject()
        try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            resp.put("status", "camera launched")
            resp.put("note", "Take a photo, then I'll pull it from DCIM/Camera")
        } catch (e: Exception) {
            resp.put("error", e.message)
        }
        return jsonResponse(resp)
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
    }
}