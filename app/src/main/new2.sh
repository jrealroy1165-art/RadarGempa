# Langkah-langkah untuk membuat repositori baru di GitHub (Cara 2)

# 1. Pastikan Anda sudah membuat repositori kosong bernama "RadarGempa" di GitHub Anda.

# 2. Putuskan hubungan dengan repositori lama (RadarBahaya)
git remote remove origin

# 3. Hubungkan ke repositori baru
git remote add origin https://github.com/jrealroy1165-art/RadarGempa.git

# 4. Pastikan branch utama bernama 'main'
git branch -M main

# 5. Tambahkan semua file dan kirim ke GitHub
git add .
git commit -m "Initial commit: Radar Gempa refactor complete"
git push -u origin main
