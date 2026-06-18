package com.bhm.demo.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BleReceiveDataSaver(private val context: Context) {

    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null

    val isRecording: Boolean
        get() = outputStream != null

    val currentFilePath: String?
        get() = outputFile?.absolutePath

    @Synchronized
    fun start(deviceAddress: String): Result<File> {
        stop()
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                return Result.failure(IOException("无法创建 Download 目录"))
            }
            val safeMac = deviceAddress.replace(":", "").uppercase(Locale.US)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(downloadsDir, "ble_rx_${safeMac}_$timestamp.txt")
            outputFile = file
            outputStream = FileOutputStream(file, true)
            Result.success(file)
        } catch (e: IOException) {
            stop()
            Result.failure(e)
        }
    }

    @Synchronized
    fun append(text: String) {
        val stream = outputStream ?: return
        stream.write(text.toByteArray(StandardCharsets.UTF_8))
        stream.flush()
    }

    @Synchronized
    fun stop() {
        try {
            outputStream?.close()
        } catch (_: IOException) {
        } finally {
            outputStream = null
        }
        outputFile?.let { file ->
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
        }
    }
}
