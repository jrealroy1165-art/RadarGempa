package com.example.radargempa

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.messaging.FirebaseMessaging
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.gson.annotations.SerializedName
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.Locale
import java.util.concurrent.TimeUnit

// --- MODEL DATA BMKG ---
data class GempaResponse(
    @SerializedName("Infogempa") val infoGempa: InfoGempa
)
data class InfoGempa(
    @SerializedName("gempa") val gempa: List<DetailGempa>
)
data class DetailGempa(
    @SerializedName("Tanggal") val tanggal: String,
    @SerializedName("Jam") val jam: String,
    @SerializedName("Magnitude") val magnitude: String,
    @SerializedName("Kedalaman") val kedalaman: String,
    @SerializedName("Wilayah") val wilayah: String,
    @SerializedName("Potensi") val potensi: String? = null,
    @SerializedName("Dirasakan") val dirasakan: String? = null,
    @SerializedName("Coordinates") val coordinates: String? = null,
    var distance: Float = -1f // Jarak dalam KM
)

// --- INTERFACE API BMKG ---
interface BmkgApi {
    @GET("DataMKG/TEWS/gempadirasakan.json")
    suspend fun getDaftarGempa(): GempaResponse
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        val sharedPref = getSharedPreferences("radar_prefs", MODE_PRIVATE)
        val isRadarEnabled = sharedPref.getBoolean("radar_active", true)
        
        if (isRadarEnabled) {
            setupPeriodicWork()
        }

        setContent {
            MaterialTheme {
                RadarScreen()
            }
        }
    }

    private fun setupPeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<RadarWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RadarGempaUpdates",
            ExistingPeriodicWorkPolicy.REPLACE,
            periodicWorkRequest
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Peringatan Radar Gempa"
            val channel = NotificationChannel("radar_gempa_channel", name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Saluran untuk peringatan gempa BMKG"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

@Composable
fun RadarScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val sharedPref = remember { context.getSharedPreferences("radar_prefs", Context.MODE_PRIVATE) }
    var isRadarEnabled by remember { mutableStateOf(sharedPref.getBoolean("radar_active", true)) }
    var isEmergencyMode by remember { mutableStateOf(sharedPref.getBoolean("emergency_mode", false)) }

    var userLocationStr by remember { mutableStateOf("Mencari Lokasi...") }
    var currentUserLatLng by remember { mutableStateOf<LatLng?>(null) }
    var daftarGempa by remember { mutableStateOf<List<DetailGempa>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedGempa by remember { mutableStateOf<DetailGempa?>(null) }
    var showMapDialog by remember { mutableStateOf(false) }

    val backgroundColor by animateColorAsState(
        targetValue = if (isEmergencyMode) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.background,
        label = "backgroundColor"
    )

    val retrofit = remember {
        Retrofit.Builder()
            .baseUrl("https://data.bmkg.go.id/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BmkgApi::class.java)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            userLocationStr = "Izin diberikan"
        }
    }

    // Fungsi Hitung Jarak
    fun calculateDistance(gempaCoords: String?, userLat: Double, userLng: Double): Float {
        if (gempaCoords == null) return -1f
        val parts = gempaCoords.split(",")
        if (parts.size != 2) return -1f
        
        val gLat = parts[0].toDoubleOrNull() ?: return -1f
        val gLng = parts[1].toDoubleOrNull() ?: return -1f
        
        val results = FloatArray(1)
        Location.distanceBetween(userLat, userLng, gLat, gLng, results)
        return results[0] / 1000 // Konversi ke KM
    }

    // Fungsi Refresh
    val refreshData = {
        isLoading = true
        coroutineScope.launch {
            try {
                // 1. Ambil Lokasi User
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            currentUserLatLng = LatLng(loc.latitude, loc.longitude)
                            userLocationStr = "Lokasi: ${String.format(Locale.getDefault(), "%.2f", loc.latitude)}, ${String.format(Locale.getDefault(), "%.2f", loc.longitude)}"
                        } else {
                            userLocationStr = "GPS aktifkan!"
                        }
                    }
                } else {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }

                // 2. Ambil Data Gempa
                val response = retrofit.getDaftarGempa()
                val list = response.infoGempa.gempa
                
                // 3. Hitung Jarak jika lokasi tersedia
                currentUserLatLng?.let { user ->
                    list.forEach { gempa ->
                        gempa.distance = calculateDistance(gempa.coordinates, user.latitude, user.longitude)
                    }
                }

                // 4. Urutkan: Terdekat di paling atas
                if (list.isNotEmpty() && currentUserLatLng != null) {
                    val nearest = list.filter { it.distance > 0 }.minByOrNull { it.distance }
                    if (nearest != null) {
                        val sortedList = list.toMutableList()
                        sortedList.remove(nearest)
                        sortedList.add(0, nearest)
                        daftarGempa = sortedList
                    } else {
                        daftarGempa = list
                    }
                } else {
                    daftarGempa = list
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
        FirebaseMessaging.getInstance().subscribeToTopic("gempa")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("RADAR GEMPA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            
            Text(userLocationStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(12.dp))

            // Saklar Radar & Mode Siaga
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = if (isRadarEnabled) Color(0xFFE8F5E9) else Color(0xFFF5F5F5))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Switch(checked = isRadarEnabled, onCheckedChange = { active ->
                            isRadarEnabled = active
                            sharedPref.edit().putBoolean("radar_active", active).apply()
                        })
                        Text("Radar", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = if (isEmergencyMode) Color(0xFFFFEBEE) else Color(0xFFF5F5F5))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Switch(checked = isEmergencyMode, enabled = isRadarEnabled, onCheckedChange = { emergency ->
                            isEmergencyMode = emergency
                            sharedPref.edit().putBoolean("emergency_mode", emergency).apply()
                        })
                        Text("Siaga", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Informasi Gempa Indonesia:", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
            
            Spacer(modifier = Modifier.height(8.dp))

            // DAFTAR GEMPA
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading && daftarGempa.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(daftarGempa) { index, gempa ->
                            GempaItem(gempa, isNearest = (index == 0 && gempa.distance > 0)) {
                                selectedGempa = gempa
                                showMapDialog = true
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { refreshData() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("PERBARUI DATA")
            }
        }
    }

    // Dialog Peta (Solusi 2: Membuka Aplikasi Google Maps)
    if (showMapDialog && selectedGempa != null) {
        AlertDialog(
            onDismissRequest = { showMapDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val coords = selectedGempa?.coordinates
                        if (coords != null) {
                            val gmmIntentUri = Uri.parse("geo:$coords?q=$coords(${selectedGempa?.wilayah})")
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                            mapIntent.setPackage("com.google.android.apps.maps")
                            context.startActivity(mapIntent)
                        }
                        showMapDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buka di Google Maps")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMapDialog = false }) { Text("Tutup") }
            },
            title = { 
                Text("Detail Lokasi Gempa", fontWeight = FontWeight.Bold) 
            },
            text = {
                Column {
                    Text(selectedGempa?.wilayah ?: "", fontSize = 16.sp)
                    if (selectedGempa?.coordinates != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Koordinat: ${selectedGempa?.coordinates}", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        )
    }
}

@Composable
fun GempaItem(gempa: DetailGempa, isNearest: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = if (isNearest) Color(0xFFFFEBEE) else Color.White),
        elevation = CardDefaults.cardElevation(if (isNearest) 4.dp else 2.dp),
        border = if (isNearest) androidx.compose.foundation.BorderStroke(1.dp, Color.Red) else null
    ) {
        Column {
            if (isNearest) {
                Box(modifier = Modifier.fillMaxWidth().background(Color.Red).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Paling Dekat dengan Anda", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                    Text(gempa.magnitude, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Color.Red)
                    Text("SR", fontSize = 10.sp, color = Color.Gray)
                }
                
                Box(modifier = Modifier.height(40.dp).width(1.dp).background(Color.LightGray))
                
                Spacer(Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(gempa.wilayah, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
                    Text("${gempa.tanggal} | ${gempa.jam}", fontSize = 11.sp, color = Color.Gray)
                    
                    if (gempa.distance > 0) {
                        Text("Jarak: ±${String.format(Locale.getDefault(), "%.1f", gempa.distance)} KM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF388E3C))
                    }
                    
                    if (gempa.dirasakan != null) {
                        Text("Dirasakan: ${gempa.dirasakan}", fontSize = 11.sp, color = Color(0xFF1976D2), maxLines = 1)
                    }
                }
                
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
            }
        }
    }
}
