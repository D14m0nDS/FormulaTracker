package com.formulatracker.drivers

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DriverPhotos(private val directory: File) {
    private val memory = HashMap<String, ImageBitmap>()

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[url] }?.let { return@withContext it }
        val file = File(directory, fileName(url))
        val bytes = if (file.isFile && file.length() > 0) {
            file.readBytes()
        } else {
            download(url, file)
        } ?: return@withContext null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        bitmap.asImageBitmap().also { image ->
            synchronized(memory) { memory[url] = image }
        }
    }

    private fun download(url: String, file: File): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        return try {
            if (connection.responseCode !in 200..299) {
                return null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                return null
            }
            directory.mkdirs()
            file.writeBytes(bytes)
            bytes
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }
}
