package de.kai_morich.simple_bluetooth_terminal

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Appends RX data to timestamped files under [logDir].
 *
 * Important for USB/MTP (PC viewing phone storage): while a file handle stays open,
 * many hosts keep showing size=0. This writer periodically **closes + MediaStore-publishes
 * + reopens append**, so PC-side size updates without unchecking the save checkbox.
 */
class ReceiveLogWriter(
    private val logDir: File,
    namePrefix: String = "",
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val onFilePublished: ((File) -> Unit)? = null,
) {
    private val lock = Any()
    private val running = AtomicBoolean(false)

    @Volatile
    var currentFile: File? = null
        private set

    private var fos: FileOutputStream? = null
    private var currentSize = 0L
    private var partIndex = 1
    private var baseName: String = ""
    private var namePrefix: String = namePrefix
    private var bytesSincePublish = 0
    private var lastPublishElapsed = 0L

    fun start(): File {
        synchronized(lock) {
            closeStreamQuietly()
            ensureLogDir()
            beginSessionLocked(namePrefix)
            openNewFileLocked(createNew = true)
            running.set(true)
            val file = currentFile ?: throw IOException("无法创建日志文件")
            publishClosedSnapshotLocked()
            reopenAppendLocked()
            Log.i(TAG, "RX log started: ${file.absolutePath}")
            return file
        }
    }

    /** Close current file and open a new one named with [newPrefix]_timestamp. */
    fun rotate(newPrefix: String): File {
        synchronized(lock) {
            if (!running.get()) throw IOException("当前未在保存")
            publishClosedSnapshotLocked()
            beginSessionLocked(newPrefix)
            openNewFileLocked(createNew = true)
            publishClosedSnapshotLocked()
            reopenAppendLocked()
            val file = currentFile ?: throw IOException("无法创建新日志文件")
            Log.i(TAG, "RX log rotated: ${file.absolutePath}")
            return file
        }
    }

    fun append(text: String) {
        if (!running.get() || text.isEmpty()) return
        synchronized(lock) {
            if (!running.get()) return
            try {
                writeChunkLocked(text.toByteArray(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "RX log append failed", e)
            }
        }
    }

    fun appendBytes(data: ByteArray, asHex: Boolean) {
        if (!running.get() || data.isEmpty()) return
        val bytes = if (asHex) {
            (TextUtil.toHexString(data) + "\n").toByteArray(StandardCharsets.UTF_8)
        } else {
            data
        }
        synchronized(lock) {
            if (!running.get()) return
            try {
                writeChunkLocked(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "RX log appendBytes failed", e)
            }
        }
    }

    fun stop(): File? {
        synchronized(lock) {
            running.set(false)
            val file = currentFile
            publishClosedSnapshotLocked()
            Log.i(TAG, "RX log stopped: ${file?.absolutePath}, size=${file?.length() ?: -1}")
            return file
        }
    }

    fun currentSize(): Long = synchronized(lock) {
        // Prefer on-disk length when handle is closed between publish cycles.
        currentFile?.length()?.takeIf { it > 0 } ?: currentSize
    }

    private fun ensureLogDir() {
        if (!logDir.exists() && !logDir.mkdirs()) {
            throw IOException("无法创建日志目录: ${logDir.absolutePath}")
        }
    }

    private fun beginSessionLocked(prefixRaw: String) {
        namePrefix = prefixRaw
        val sessionStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val prefix = sanitizePrefix(prefixRaw)
        baseName = if (prefix.isEmpty()) "spp_rx_$sessionStamp" else "${prefix}_$sessionStamp"
        partIndex = 1
    }

    private fun openNewFileLocked(createNew: Boolean) {
        closeStreamQuietly()
        val name = if (partIndex == 1) {
            "$baseName.txt"
        } else {
            "${baseName}_$partIndex.txt"
        }
        val file = File(logDir, name)
        if (createNew && !file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        currentFile = file
        currentSize = if (file.exists()) file.length() else 0L
        bytesSincePublish = 0
        lastPublishElapsed = android.os.SystemClock.elapsedRealtime()
        if (createNew) partIndex++
        Log.i(TAG, "RX log file ready: ${file.absolutePath} size=$currentSize")
    }

    private fun reopenAppendLocked() {
        val file = currentFile ?: return
        fos = FileOutputStream(file, /* append */ true)
        currentSize = file.length()
    }

    private fun writeChunkLocked(bytes: ByteArray) {
        if (currentSize > 0 && currentSize + bytes.size > maxFileBytes) {
            publishClosedSnapshotLocked()
            openNewFileLocked(createNew = true)
            reopenAppendLocked()
        }
        if (fos == null) {
            reopenAppendLocked()
        }
        val out = fos
        if (out == null) {
            Log.e(TAG, "RX log stream is null, drop ${bytes.size} bytes")
            return
        }
        out.write(bytes)
        out.flush()
        try {
            out.fd.sync()
        } catch (_: Exception) {
        }
        currentSize += bytes.size
        bytesSincePublish += bytes.size

        val now = android.os.SystemClock.elapsedRealtime()
        if (bytesSincePublish >= PUBLISH_EVERY_BYTES || now - lastPublishElapsed >= PUBLISH_EVERY_MS) {
            // Close so MTP/PC can observe the real size, then continue appending.
            publishClosedSnapshotLocked()
            reopenAppendLocked()
        }
        if (currentSize >= maxFileBytes) {
            publishClosedSnapshotLocked()
            openNewFileLocked(createNew = true)
            reopenAppendLocked()
        }
    }

    /**
     * Flush + close the open handle and notify listeners (MediaScanner).
     * After this, [fos] is null until [reopenAppendLocked].
     */
    private fun publishClosedSnapshotLocked() {
        val file = currentFile
        try {
            fos?.flush()
            fos?.fd?.sync()
        } catch (_: Exception) {
        }
        closeStreamQuietly()
        if (file != null) {
            currentSize = file.length()
            lastPublishElapsed = android.os.SystemClock.elapsedRealtime()
            bytesSincePublish = 0
            try {
                onFilePublished?.invoke(file)
            } catch (e: Exception) {
                Log.w(TAG, "onFilePublished failed", e)
            }
            Log.d(TAG, "published ${file.name} size=$currentSize")
        }
    }

    private fun closeStreamQuietly() {
        try {
            fos?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close stream failed", e)
        }
        fos = null
    }

    companion object {
        private const val TAG = "ReceiveLogWriter"
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        /** Close/reopen often enough for USB MTP hosts to refresh size. */
        private const val PUBLISH_EVERY_BYTES = 64 * 1024
        private const val PUBLISH_EVERY_MS = 2000L

        fun sanitizePrefix(raw: String): String {
            var name = raw.trim()
            if (name.endsWith(".txt", ignoreCase = true)) {
                name = name.dropLast(4)
            }
            name = name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
                .trim('_')
            return name.take(64)
        }
    }
}
