package com.example.radargempa

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import kotlin.math.*

class RadarWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://data.bmkg.go.id/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(BmkgApi::class.java)

            // Mengambil daftar gempa terbaru
            val response = retrofit.getDaftarGempa()
            val listGempa = response.infoGempa.gempa

            if (listGempa.isEmpty()) return Result.success()

            // Ambil gempa terbaru (paling atas dari BMKG)
            val gempaTerbaru = listGempa[0]

            // Ambil lokasi user di latar belakang
            val userLoc = getCurrentLocation()
            
            var jarakTerdekat = -1.0
            var gempaPalingRelevan = gempaTerbaru

            // Cek apakah ada gempa yang sangat dekat (< 100km) di daftar 15 gempa terakhir
            if (userLoc != null) {
                for (g in listGempa) {
                    val d = hitungJarak(userLoc.latitude, userLoc.longitude, g.coordinates)
                    if (d in 0.0..100.0) {
                        gempaPalingRelevan = g
                        jarakTerdekat = d
                        break
                    }
                }
                
                // Jika tidak ada yang < 100km, hitung jarak untuk gempa terbaru saja
                if (jarakTerdekat < 0) {
                    jarakTerdekat = hitungJarak(userLoc.latitude, userLoc.longitude, gempaTerbaru.coordinates)
                }
            }

            val pesanJarak = if (jarakTerdekat >= 0) " (Jarak: ${String.format(Locale.getDefault(), "%.1f", jarakTerdekat)} km)" else ""
            
            // Logika Notifikasi: Hanya jika Magnitude >= 4.5 atau sangat dekat
            val mag = gempaPalingRelevan.magnitude.toDoubleOrNull() ?: 0.0
            if (mag >= 4.5 || (jarakTerdekat in 0.0..50.0)) {
                
                if (jarakTerdekat in 0.0..20.0 && mag >= 4.0) {
                    // Gempa sangat dekat & berbahaya -> Buka Activity Alarm
                    startAlarmActivity(
                        "BAHAYA GEMPA DEKAT!",
                        "Gempa ${gempaPalingRelevan.magnitude} SR terdeteksi di ${gempaPalingRelevan.wilayah}. $pesanJarak"
                    )
                } else {
                    // Kirim notifikasi biasa
                    showNotification(
                        "Peringatan Gempa Terkini",
                        "Gempa ${gempaPalingRelevan.magnitude} SR - ${gempaPalingRelevan.wilayah}.$pesanJarak"
                    )
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun startAlarmActivity(title: String, message: String) {
        val intent = Intent(applicationContext, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("title", title)
            putExtra("message", message)
        }
        applicationContext.startActivity(intent)
    }

    private fun getCurrentLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
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

        val r = 6371 // Radius bumi dalam KM
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun showNotification(title: String, message: String) {
        val sharedPref = applicationContext.getSharedPreferences("radar_prefs", Context.MODE_PRIVATE)
        val isEmergency = sharedPref.getBoolean("emergency_mode", false)
        
        val channelId = "radar_gempa_channel"
        
        val ringtoneUri = if (isEmergency) {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        } else {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.rg_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (isEmergency) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isEmergency) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setSound(ringtoneUri)
            .setVibrate(if (isEmergency) longArrayOf(0, 1000, 500, 1000, 500, 1000) else longArrayOf(0, 500))
            .setAutoCancel(true)
            .build()

        try {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(applicationContext).notify(System.currentTimeMillis().toInt(), notification)
            }
        } catch (e: Exception) { }
    }
}
