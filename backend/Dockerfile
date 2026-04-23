# Gunakan Node.js versi terbaru
FROM node:20

# Buat folder kerja
WORKDIR /app

# Copy file dependency
COPY package*.json ./

# Install dependency
RUN npm install

# Copy semua kode ke dalam container
COPY . .

# Berikan port standar Hugging Face
EXPOSE 7860

# Jalankan server
CMD [ "node", "index.js" ]
