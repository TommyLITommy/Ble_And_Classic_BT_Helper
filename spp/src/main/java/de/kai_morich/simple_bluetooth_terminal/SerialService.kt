package de.kai_morich.simple_bluetooth_terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
class SerialService : Service(), SerialListener {

    inner class SerialBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }

    private enum class QueueType { Connect, ConnectError, Read, IoError }

    private class QueueItem {
        var type: QueueType
        var datas: ArrayDeque<ByteArray>? = null
        var e: Exception? = null

        constructor(type: QueueType) {
            this.type = type
            if (type == QueueType.Read) init()
        }

        constructor(type: QueueType, e: Exception) {
            this.type = type
            this.e = e
        }

        constructor(type: QueueType, datas: ArrayDeque<ByteArray>) {
            this.type = type
            this.datas = datas
        }

        fun init() {
            datas = ArrayDeque()
        }

        fun add(data: ByteArray) {
            datas?.add(data)
        }
    }

    private val mainLooper = Handler(Looper.getMainLooper())
    private val binder: IBinder = SerialBinder()
    private val queue1 = ArrayDeque<QueueItem>()
    private val queue2 = ArrayDeque<QueueItem>()
    private val lastRead = QueueItem(QueueType.Read)

    private var socket: SerialSocket? = null
    private var listener: SerialListener? = null
    private var connected = false

    @Volatile
    private var receiveLogWriter: ReceiveLogWriter? = null

    @Volatile
    private var logHexEnabled = true

    @Volatile
    private var payloadStringEnabled = false

    private val logProtocolDecoder = PrivateProtocolStreamDecoder()

    override fun onDestroy() {
        disconnect()
        stopRxLogging()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * Api
     */
    @Throws(IOException::class)
    fun connect(socket: SerialSocket) {
        socket.connect(this)
        this.socket = socket
        connected = true
    }

    fun disconnect() {
        connected = false // ignore data,errors while disconnecting
        cancelNotification()
        socket?.let {
            it.disconnect()
            socket = null
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (!connected) throw IOException("not connected")
        socket?.write(data)
    }

    fun setLogHexEnabled(enabled: Boolean) {
        logHexEnabled = enabled
        logProtocolDecoder.reset()
    }

    fun setPayloadStringEnabled(enabled: Boolean) {
        payloadStringEnabled = enabled
    }

    fun isRxLogging(): Boolean = receiveLogWriter != null

    fun currentLogFile(): File? = receiveLogWriter?.currentFile

    fun currentLogSize(): Long = receiveLogWriter?.currentSize() ?: 0L

    @Throws(IOException::class)
    fun startRxLogging(logDir: File, namePrefix: String, hexEnabled: Boolean): File {
        logHexEnabled = hexEnabled
        stopRxLogging()
        logProtocolDecoder.reset()
        val writer = ReceiveLogWriter(logDir, namePrefix = namePrefix) { file ->
            scanLogFile(file)
        }
        val file = writer.start()
        receiveLogWriter = writer
        scanLogFile(file)
        Log.i(TAG, "startRxLogging: ${file.absolutePath}, hex=$hexEnabled")
        return file
    }

    @Throws(IOException::class)
    fun updateRxLoggingName(namePrefix: String): File {
        val writer = receiveLogWriter ?: throw IOException("请先勾选同步保存")
        val file = writer.rotate(namePrefix)
        scanLogFile(file)
        Log.i(TAG, "updateRxLoggingName: ${file.absolutePath}")
        return file
    }

    fun stopRxLogging(): File? {
        val writer = receiveLogWriter ?: return null
        receiveLogWriter = null
        logProtocolDecoder.reset()
        val file = writer.stop()
        scanLogFile(file)
        Log.i(TAG, "stopRxLogging: ${file?.absolutePath}, size=${file?.length() ?: -1}")
        return file
    }

    /**
     * Prefer writing the exact UI display text (from TerminalFragment) so file content
     * matches what the user sees. Used whenever the UI is attached and rendering RX/TX.
     */
    fun appendRxLogText(text: CharSequence) {
        if (text.isEmpty()) return
        receiveLogWriter?.append(text.toString())
    }

    /**
     * Fallback when UI is detached (e.g. left Terminal) but sync-save is still on.
     * Not a full mirror of every TerminalFragment branch; UI-attached path is authoritative.
     */
    private fun appendRxLogFallback(data: ByteArray) {
        val writer = receiveLogWriter ?: return
        if (!logHexEnabled) {
            writer.appendBytes(data, asHex = false)
            return
        }
        val frames = logProtocolDecoder.append(data)
        if (frames.isEmpty()) {
            if (!logProtocolDecoder.hasPendingData() && !PrivateProtocol.mayContainHeader(data)) {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                writer.append("---RX---$ts---\n${TextUtil.toHexString(data)}\n\n")
            }
            return
        }
        val sb = StringBuilder()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (frame in frames) {
            sb.append("---RX---").append(timeFmt.format(Date())).append("---\n")
            TextUtil.toHexString(sb, frame.bytes)
            sb.append('\n')
            val payload = frame.payload()
            if (payloadStringEnabled && payload.isNotEmpty()) {
                sb.append("Payload String: ")
                sb.append(String(payload, StandardCharsets.UTF_8))
                sb.append('\n')
            }
            if (frame.group() == 0xFF && frame.subId() == 0x08 &&
                payload.size == SleepUploadDataParser.PAYLOAD_SIZE
            ) {
                val parser = SleepUploadDataParser(payload)
                sb.append(parser.toStringRepresentation()).append('\n')
                SleepRecordHistoryStore.addFromParser(parser)
            }
            sb.append('\n')
        }
        writer.append(sb.toString())
    }

    private fun scanLogFile(file: File?) {
        if (file == null) return
        try {
            MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("text/plain")
            ) { path, uri ->
                Log.d(TAG, "media scanned path=$path uri=$uri size=${file.length()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanLogFile failed", e)
        }
        // Also poke the legacy scanner used by some OEM MTP stacks.
        try {
            sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).setData(
                    android.net.Uri.fromFile(file)
                )
            )
        } catch (_: Exception) {
        }
    }

    fun attach(listener: SerialListener) {
        if (Looper.getMainLooper().thread != Thread.currentThread()) {
            throw IllegalArgumentException("not in main thread")
        }
        initNotification()
        cancelNotification()
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized(this) {
            this.listener = listener
        }
        for (item in queue1) {
            when (item.type) {
                QueueType.Connect -> listener.onSerialConnect()
                QueueType.ConnectError -> listener.onSerialConnectError(requireNotNull(item.e))
                QueueType.Read -> listener.onSerialRead(requireNotNull(item.datas))
                QueueType.IoError -> listener.onSerialIoError(requireNotNull(item.e))
            }
        }
        for (item in queue2) {
            when (item.type) {
                QueueType.Connect -> listener.onSerialConnect()
                QueueType.ConnectError -> listener.onSerialConnectError(requireNotNull(item.e))
                QueueType.Read -> listener.onSerialRead(requireNotNull(item.datas))
                QueueType.IoError -> listener.onSerialIoError(requireNotNull(item.e))
            }
        }
        queue1.clear()
        queue2.clear()
    }

    fun detach() {
        if (connected) createNotification()
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null
    }

    private fun initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nc = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL,
                "Background service",
                NotificationManager.IMPORTANCE_LOW
            )
            nc.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(nc)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun areNotificationsEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() && nc != null && nc.importance > NotificationManager.IMPORTANCE_NONE
    }

    private fun createNotification() {
        val disconnectIntent = Intent()
            .setPackage(packageName)
            .setAction(Constants.INTENT_ACTION_DISCONNECT)
        val restartIntent = Intent()
            .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags)
        val restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent, flags)
        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(resources.getColor(R.color.colorPrimary))
            .setContentTitle(resources.getString(R.string.spp_app_name))
            .setContentText(if (socket != null) "Connected to ${socket?.getName()}" else "Background Service")
            .setContentIntent(restartPendingIntent)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_clear_white_24dp,
                    "Disconnect",
                    disconnectPendingIntent
                )
            )
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        val notification: Notification = builder.build()
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification)
    }

    private fun cancelNotification() {
        stopForeground(true)
    }

    /**
     * SerialListener
     */
    override fun onSerialConnect() {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialConnect()
                        } else {
                            queue1.add(QueueItem(QueueType.Connect))
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.Connect))
                }
            }
        }
    }

    override fun onSerialConnectError(e: Exception) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialConnectError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.ConnectError, e))
                            disconnect()
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.ConnectError, e))
                    disconnect()
                }
            }
        }
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        throw UnsupportedOperationException()
    }

    /**
     * reduce number of UI updates by merging data chunks.
     * Data can arrive at hundred chunks per second, but the UI can only
     * perform a dozen updates if receiveText already contains much text.
     *
     * On new data inform UI thread once (1).
     * While not consumed (2), add more data (3).
     */
    override fun onSerialRead(data: ByteArray) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    // UI attached: TerminalFragment writes the exact display text to the log file.
                    val first: Boolean
                    synchronized(lastRead) {
                        first = lastRead.datas?.isEmpty() == true // (1)
                        lastRead.add(data) // (3)
                    }
                    if (first) {
                        mainLooper.post {
                            val datas: ArrayDeque<ByteArray>
                            synchronized(lastRead) {
                                datas = requireNotNull(lastRead.datas)
                                lastRead.init() // (2)
                            }
                            if (listener != null) {
                                listener?.onSerialRead(datas)
                            } else {
                                // UI vanished before consume; fall back to service-side log.
                                for (chunk in datas) {
                                    appendRxLogFallback(chunk)
                                }
                                queue1.add(QueueItem(QueueType.Read, datas))
                            }
                        }
                    }
                } else {
                    // UI detached: keep writing while sync-save is on (best-effort format).
                    if (receiveLogWriter != null) {
                        appendRxLogFallback(data)
                    } else {
                        // Only buffer for UI when not file-logging; otherwise RAM would grow unbounded.
                        if (queue2.isEmpty() || queue2.last.type != QueueType.Read) {
                            queue2.add(QueueItem(QueueType.Read))
                        }
                        queue2.last.add(data)
                    }
                }
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialIoError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.IoError, e))
                            disconnect()
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.IoError, e))
                    disconnect()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SerialService"
    }
}
