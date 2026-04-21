package com.example.radargempa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class AlarmActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var originalVolume: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Memastikan aktivitas muncul di atas lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
        
        // Paksa Volume Maksimal
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
        audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        playAlarmSound()

        val title = intent.getStringExtra("title") ?: "PERINGATAN GEMPA!"
        val message = intent.getStringExtra("message") ?: "Gempa terdeteksi sangat dekat dengan lokasi Anda!"

        setContent {
            MaterialTheme {
                // Background Merah Berkedip
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Red)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(100.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = message,
                        color = Color.White,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(
                        onClick = { finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                    ) {
                        Text("AKHIRI ALARM", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    
                    // Terus paksa volume maksimal selama alarm aktif
                    LaunchedEffect(Unit) {
                        while(true) {
                            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                            delay(1000)
                        }
                    }
                }
            }
        }
    }

    private fun playAlarmSound() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@AlarmActivity, alarmUri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            prepare()
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        // Kembalikan volume ke awal jika diinginkan (opsional)
        // audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
    }
}
