package com.example.radargempa

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale
import kotlin.math.*

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val sharedPref = getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
        val isRadarEnabled = sharedPref.getBoolean("radar_active", true)

        if (isRadarEnabled) {
            val data = remoteMessage.data
            if (data.isNotEmpty()) {
                val title = data["title"] ?: "PERINGATAN GEMPA!"
                val body = data["body"] ?: "Terdeteksi aktivitas gempa terbaru."
                val coords = data["coords"]
                val magnitudeStr = data["magnitude"]
                val magnitude = magnitudeStr?.toDoubleOrNull() ?: 0.0

                handleGempaData(title, body, coords, magnitude)
            } else {

                val title = remoteMessage.notification?.title ?: "PERINGATAN GEMPA!"
                val message = remoteMessage.notification?.body ?: "Terdeteksi aktivitas gempa terbaru."
                showNotification(title, message)
            }
        }
    }

    private fun handleGempaData(title: String, body: String, coords: String?, magnitude: Double) {
        val userLoc = getCurrentLocation()
        var jarak = -1.0

        if (userLoc != null && coords != null) {
            jarak = hitungJarak(userLoc.latitude, userLoc.longitude, coords)
        }

        val pesanJarak = if (jarak >= 0) " (Jarak: ${String.format(Locale.getDefault(), "%.1f", jarak)} km)" else ""
        val finalTitle = if (jarak in 0.0..50.0) "⚠️ BAHAYA: GEMPA DEKAT!" else title
        val finalBody = "$body$pesanJarak"


        if (jarak in 0.0..30.0 && magnitude >= 4.0) {
            startAlarmActivity(finalTitle, finalBody)
        } else {
            showNotification(finalTitle, finalBody)
        }
    }

    private fun startAlarmActivity(title: String, message: String) {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("title", title)
            putExtra("message", message)
        }
        startActivity(intent)
    }

    private fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        return try {
            Tasks.await(fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null))
        } catch (e: Exception) {
            null
        }
    }

    private fun hitungJarak(lat1: Double, lon1: Double, coordsStr: String?): Double {
        if (coordsStr == null) return -1.0
        val parts = coordsStr.split(",")
        if (parts.size != 2) return -1.0
        
        val lat2 = parts[0].toDoubleOrNull() ?: return -1.0
        val lon2 = parts[1].toDoubleOrNull() ?: return -1.0

        val r = 6371
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun showNotification(title: String, message: String) {
        val sharedPref = getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
        val isEmergency = sharedPref.getBoolean("emergency_mode", false)
        val channelId = "radar_gempa_channel"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_notification", true)
            putExtra("notif_title", title)
            putExtra("notif_message", message)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringtoneUri = if (isEmergency) {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        } else {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.rg_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (isEmergency) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isEmergency) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setSound(ringtoneUri)
            .setVibrate(if (isEmergency) longArrayOf(0, 1000, 500, 1000, 500, 1000) else longArrayOf(0, 500))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
            }
        } else {
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
}
