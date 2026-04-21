const admin = require('firebase-admin');
const axios = require('axios');
const express = require('express'); // Tambahan untuk Web Server
const app = express();
const port = 3000;

// 1. KONEKSI KE FIREBASE
const serviceAccount = require("./service-account.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

console.log("✅ Server Radar Gempa Aktif & Terhubung ke Firebase");

let lastGempaId = "";
let lastStatus = "Memulai pemantauan...";

// --- FITUR WEB SERVER (Agar bisa dibuka di browser) ---
app.get('/', (req, res) => {
  res.send(`
    <html>
      <body style="font-family: sans-serif; text-align: center; padding-top: 50px;">
        <h1 style="color: #2E7D32;">✅ Radar Gempa Server Aktif</h1>
        <p>Status: <strong>${lastStatus}</strong></p>
        <p>Terakhir dicek: ${new Date().toLocaleTimeString()}</p>
        <hr>
        <p>Server ini memantau BMKG dan mengirim push notification otomatis.</p>
      </body>
    </html>
  `);
});

app.listen(port, () => {
  console.log(`🌐 Dashboard server bisa dibuka di http://localhost:${port}`);
});

// 2. FUNGSI UNTUK CEK BMKG
async function checkBMKG() {
  try {
    const response = await axios.get('https://data.bmkg.go.id/DataMKG/TEWS/autogempa.json');
    const gempa = response.data.Infogempa.gempa;
    const currentId = gempa.Jam + gempa.Tanggal;

    if (currentId !== lastGempaId) {
      lastStatus = `Gempa Baru Terdeteksi: ${gempa.Wilayah}`;
      console.log(`🔔 ${lastStatus}`);
      lastGempaId = currentId;

      const message = {
        data: {
          title: '⚠️ PERINGATAN GEMPA TERBARU!',
          body: `Magnitudo: ${gempa.Magnitude} SR di ${gempa.Wilayah}.`,
          magnitude: gempa.Magnitude,
          wilayah: gempa.Wilayah,
          coords: gempa.Coordinates,
          tanggal: gempa.Tanggal,
          jam: gempa.Jam
        },
        topic: 'gempa'
      };

      await admin.messaging().send(message);
      console.log("🚀 Notifikasi Terkirim!");
    } else {
      lastStatus = "Memantau BMKG... (Tidak ada gempa baru)";
      console.log("☁️ " + lastStatus);
    }
  } catch (error) {
    lastStatus = "Error saat mengecek BMKG";
    console.error("❌ " + lastStatus);
  }
}

checkBMKG();
setInterval(checkBMKG, 60000);
