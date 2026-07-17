package de.kai_morich.simple_bluetooth_terminal

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.min

class TerminalFragment : Fragment(), ServiceConnection, SerialListener,
    TapParameterDialogFragment.OnParameterSetListener {

    private enum class Connected { False, Pending, True }

    private var deviceAddress: String? = null
    private var service: SerialService? = null

    private lateinit var receiveText: TextView
    private lateinit var receiveScroll: ScrollView
    private lateinit var sendText: EditText
    private lateinit var clearSendBtn: View
    private lateinit var editBytesBtn: View
    private lateinit var hexWatcher: TextUtil.HexWatcher

    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = false
    private var pendingNewline = false
    private var newline = TextUtil.newline_crlf
    private var pauseRx = false

    private lateinit var filenameEditText: AutoCompleteTextView
    private lateinit var updateFilenameBtn: Button
    private lateinit var shareButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var saveRxCheckBox: CheckBox
    private lateinit var payloadStringCheckBox: CheckBox
    private var lastSavedFile: File? = null
    private var pendingStorageAction: (() -> Unit)? = null
    private var lastAutoScrollMs = 0L
    private var suppressSaveRxCallback = false
    private var payloadStringEnabled = false
    private var filenameHistoryAdapter: ArrayAdapter<String>? = null
    private var parser = TapParameterParser()
    private val protocolDecoder = PrivateProtocolStreamDecoder()
    private val presetCommands = LinkedHashMap<String, String>()
    private val logStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logStatusRunnable = object : Runnable {
        override fun run() {
            updateLoggingStatusText()
            if (service?.isRxLogging() == true) {
                logStatusHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                pendingStorageAction?.invoke()
            } else {
                onStoragePermissionDenied()
            }
            pendingStorageAction = null
        }
    private val writeStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingStorageAction?.invoke()
            } else {
                onStoragePermissionDenied()
            }
            pendingStorageAction = null
        }
    private val postNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && service?.areNotificationsEnabled() == false) {
                showNotificationSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        deviceAddress = arguments?.getString("device")
        initPresetCommands()
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        activity?.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val act = activity ?: return
        service?.attach(this) ?: act.startService(Intent(act, SerialService::class.java))
        syncSaveRxCheckBoxFromService()
    }

    override fun onStop() {
        if (service != null && activity?.isChangingConfigurations == false) service?.detach()
        super.onStop()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        context.bindService(Intent(context, SerialService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onDetach() {
        try {
            activity?.unbindService(this)
        } catch (_: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        if (initialStart && service != null) {
            initialStart = false
            activity?.runOnUiThread { connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialService.SerialBinder).getService()
        service?.attach(this)
        service?.setLogHexEnabled(hexEnabled)
        service?.setPayloadStringEnabled(payloadStringEnabled)
        syncSaveRxCheckBoxFromService()
        if (initialStart && isResumed) {
            initialStart = false
            activity?.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    private fun initPresetCommands() {
        presetCommands["打开WBD原始数据上报"] = "3A 5E 20 01 01 08 00 3E 00 00 00 00 00 00 00 00"
        presetCommands["关闭WBD原始数据上报"] = "3A 5E 20 01 02 08 00 A1 00 00 00 00 00 00 00 00"
        presetCommands["打开WBD数据上报"] = "3A 5E 10 FF 08 09 00 01 41 00 00 00 00 00 00 00 00"
        presetCommands["关闭WBD数据上报"] = "3A 5E 10 FF 08 09 00 00 41 00 00 00 00 00 00 00 00"
        presetCommands["A5001_FPC_MIC"] = "3A 5E 20 05 0a 09 00 00 06 00 00 00 00 00 00 00 00"
        presetCommands["A5001_板载_MIC"] = "3A 5E 20 05 09 09 00 00 69 00 00 00 00 00 00 00 00"
        presetCommands["A5001_关机"] = "3A 5E 20 02 13 09 00 00 90 00 00 00 00 00 00 00 00"
        presetCommands["A5001_重启"] = "3A 5E 20 02 14 09 00 00 AE 00 00 00 00 00 00 00 00"
        presetCommands["A5001_船运"] = "3A 5E 20 02 15 09 00 00 4E 00 00 00 00 00 00 00 00"
        presetCommands["A5001_获取固件版本号"] = "3A 5E 20 01 0E 09 00 00 F3 00 00 00 00 00 00 00 00"
        presetCommands["A5001_获取耳机电量"] = "3A 5E 20 01 0F 09 00 00 13 00 00 00 00 00 00 00 00"
        presetCommands["A5001_获取耳机MAC"] = "3A 5E 20 01 10 09 00 00 84 00 00 00 00 00 00 00 00"
        presetCommands["A5001_打开imu数据上传"] = "3A 5E 20 04 24 09 00 01 28 11 22 33 44 55 66 77 88"
        presetCommands["A5001_关闭imu数据上传"] = "3A 5E 20 04 24 09 00 00 45 11 22 33 44 55 66 77 88"
        presetCommands["开启IMU_Tap上传"] = "3A 5E 10 FF 07 09 00 01 AA 11 22 33 44 55 66 77 88"
        presetCommands["关闭IMU_Tap上传"] = "3A 5E 10 FF 07 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取SN"] = "3A 5E 20 01 01 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取PN"] = "3A 5E 20 01 02 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取设备颜色"] = "3A 5E 20 01 03 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["写入设备颜色"] = "3A 5E 20 01 03 0A 00 01 1D AA 11 22 33 44 55 66 77 88"
        presetCommands["读取蓝牙耳机名称"] = "3A 5E 20 01 0c 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取NTC温度"] = "3A 5E 20 01 0d 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["双耳变单耳模式（single)"] = "3A 5E 20 01 12 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取耳机固件版本号0104"] = "3A 5E 20 01 04 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取耳机固件版本号"] = "3A 5E 20 01 0e 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取耳机电量"] = "3A 5E 20 01 0f 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取耳机MAC地址"] = "3A 5E 20 01 10 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取Peer耳机MAC地址"] = "3A 5E 20 01 14 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["读取硬件版本号"] = "3A 5E 20 01 11 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["写入硬件版本号"] = "3A 5E 20 01 11 0E 00 01 31 2E 30 2E 30 AA 11 22 33 44 55 66 77 88"
        presetCommands["进入DUT模式"] = "3A 5E 20 02 12 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["关机"] = "3A 5E 20 02 13 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["重启"] = "3A 5E 20 02 14 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["进入船运模式"] = "3A 5E 20 02 15 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["恢复出厂设置"] = "3A 5E 20 02 21 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["断开蓝牙连接"] = "3A 5E 20 03 30 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["进入配对模式"] = "3A 5E 20 03 31 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["进入TWS组队模式"] = "3A 5E 20 03 33 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["打开imu数据上传"] = "3A 5E 20 04 24 09 00 01 28 11 22 33 44 55 66 77 88"
        presetCommands["关闭imu数据上传"] = "3A 5E 20 04 24 09 00 00 45 11 22 33 44 55 66 77 88"
        presetCommands["imu校准值读取"] = "3A 5E 20 04 28 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["启动IMU校准"] = "3A 5E 20 04 2b 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["开启IMU校准Bypass"] = "3A 5E 20 04 2c 09 00 01 AA 11 22 33 44 55 66 77 88"
        presetCommands["关闭IMU校准Bypass"] = "3A 5E 20 04 2c 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["IMU测试"] = "3A 5E 20 04 2f 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["打开ENC算法"] = "3A 5E 20 05 01 09 00 01 AA 11 22 33 44 55 66 77 88"
        presetCommands["关闭ENC算法"] = "3A 5E 20 05 01 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["切换Talk"] = "3A 5E 20 05 08 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["切换FF"] = "3A 5E 20 05 09 09 00 00 AA 11 22 33 44 55 66 77 88"
        presetCommands["切换VPU"] = "3A 5E 20 05 0a 09 00 00 AA 11 22 33 44 55 66 77 88"
    }

    override fun onParametersSet(params: AppInvImuTapParameters) {
        val byteStream = ByteArrayOutputStream()
        val dataStream = DataOutputStream(byteStream)
        try {
            dataStream.writeByte(params.tap_tmax.toInt() and 0xFF)
            dataStream.writeByte((params.tap_tmax.toInt() shr 8) and 0xFF)
            dataStream.writeByte(params.tap_tmin.toInt())
            dataStream.writeByte(params.tap_max.toInt())
            dataStream.writeByte(params.tap_min.toInt())
            dataStream.writeByte(params.tap_max_peak_tol.toInt())
            dataStream.writeByte(params.tap_tavg.toInt())
            dataStream.writeByte(params.tap_min_jerk_threshold.toInt() and 0xFF)
            dataStream.writeByte((params.tap_min_jerk_threshold.toInt() shr 8) and 0xFF)
            dataStream.writeByte(params.tap_smudge_rejection.toInt())

            val binaryData = byteStream.toByteArray()
            val prefix = byteArrayOf(0x3A, 0x5E, 0x10, 0xFF.toByte(), 0x05, 0x12, 0x00)
            val suffix = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
            val hexString = StringBuilder()
            prefix.forEach { hexString.append(String.format("%02X ", it)) }
            binaryData.forEach { hexString.append(String.format("%02X ", it)) }
            suffix.forEach { hexString.append(String.format("%02X ", it)) }
            val normalized = PrivateProtocol.normalizeOutgoingFrame(TextUtil.fromHexString(hexString))
            hexString.setLength(0)
            TextUtil.toHexString(hexString, normalized)
            Log.d("MyTag", "Set Tap parameter:$hexString")
            sendText.setText(hexString.toString().trim())
        } catch (_: IOException) {
            sendText.setText("Error converting parameters to binary")
        }
    }

    private fun showPresetDialog() {
        val ctx = activity ?: return
        val commandNames = ArrayList(presetCommands.keys)
        val adapter = ArrayAdapter(ctx, R.layout.dialog_single_choice, android.R.id.text1, commandNames)
        AlertDialog.Builder(ctx)
            .setTitle("选择预设命令")
            .setAdapter(adapter) { dialog, which ->
                val selectedName = commandNames[which]
                var selectedCommand = presetCommands[selectedName]
                selectedCommand = selectedCommand?.trim()
                    ?.replace("\\s*0D\\s*0A$".toRegex(), "")
                    ?.replace("\\s*0d\\s*0a$".toRegex(), "")
                sendText.setText(selectedCommand)
                Toast.makeText(activity, "已选择命令：$selectedName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun runWithStoragePermission(action: () -> Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    action()
                } else {
                    pendingStorageAction = action
                    requestManageStoragePermission()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (activity?.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    action()
                } else {
                    pendingStorageAction = action
                    writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            else -> action()
        }
    }

    private fun onStoragePermissionDenied() {
        if (::saveRxCheckBox.isInitialized && saveRxCheckBox.isChecked) {
            suppressSaveRxCallback = true
            saveRxCheckBox.isChecked = false
            suppressSaveRxCallback = false
        }
        Toast.makeText(activity, "未获得存储权限", Toast.LENGTH_SHORT).show()
    }

    private fun requestManageStoragePermission() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("需要文件管理权限")
            .setMessage("为了保存文件到Download目录，请允许管理所有文件的访问权限")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    manageStorageLauncher.launch(
                        Intent().setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
            .setNegativeButton("取消") { _, _ -> onStoragePermissionDenied() }
            .setOnCancelListener { onStoragePermissionDenied() }
            .show()
    }

    private fun startRxFileLogging() {
        if (!::saveRxCheckBox.isInitialized || !saveRxCheckBox.isChecked) return
        val svc = service
        if (svc == null) {
            Toast.makeText(activity, "服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            saveRxCheckBox.isChecked = false
            return
        }
        if (svc.isRxLogging()) {
            lastSavedFile = svc.currentLogFile()
            updateLoggingStatusText()
            startLogStatusUpdates()
            return
        }
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, "SPP_RX")
            val prefix = filenameEditText.text.toString()
            SppPreferences.rememberFilename(requireContext(), prefix)
            refreshFilenameHistory()
            val file = svc.startRxLogging(logDir, prefix, hexEnabled)
            lastSavedFile = file
            updateFilenameBtn.isEnabled = true
            updateLoggingStatusText()
            startLogStatusUpdates()
            Toast.makeText(activity, "开始同步保存: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (::saveRxCheckBox.isInitialized) {
                suppressSaveRxCallback = true
                saveRxCheckBox.isChecked = false
                suppressSaveRxCallback = false
            }
            updateFilenameBtn.isEnabled = false
            val errorMsg = "无法开始保存: ${e.message}"
            statusTextView.text = errorMsg
            Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRxFileLogging() {
        stopLogStatusUpdates()
        val file = service?.stopRxLogging()
        updateFilenameBtn.isEnabled = false
        if (file != null) {
            lastSavedFile = file
            activity?.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
            statusTextView.text = "已停止: ${file.name} (${formatFileSize(file.length())})"
        }
    }

    private fun updateRxLogFilename() {
        if (service?.isRxLogging() != true) {
            Toast.makeText(activity, "请先勾选同步保存", Toast.LENGTH_SHORT).show()
            return
        }
        runWithStoragePermission {
            try {
                val prefix = filenameEditText.text.toString()
                SppPreferences.rememberFilename(requireContext(), prefix)
                refreshFilenameHistory()
                val file = service?.updateRxLoggingName(prefix)
                    ?: throw IOException("服务未就绪")
                lastSavedFile = file
                updateLoggingStatusText()
                Toast.makeText(activity, "已切换到新文件: ${file.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "Update失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLoggingStatusText() {
        if (!::statusTextView.isInitialized) return
        val file = service?.currentLogFile() ?: lastSavedFile
        if (service?.isRxLogging() == true && file != null) {
            val size = service?.currentLogSize() ?: file.length()
            statusTextView.text = "保存中: ${file.name} (${formatFileSize(size)})"
        }
    }

    private fun startLogStatusUpdates() {
        stopLogStatusUpdates()
        logStatusHandler.post(logStatusRunnable)
    }

    private fun stopLogStatusUpdates() {
        logStatusHandler.removeCallbacks(logStatusRunnable)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2fMB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun syncSaveRxCheckBoxFromService() {
        if (!::saveRxCheckBox.isInitialized) return
        val logging = service?.isRxLogging() == true
        updateFilenameBtn.isEnabled = logging
        if (saveRxCheckBox.isChecked == logging) {
            if (logging) {
                lastSavedFile = service?.currentLogFile()
                updateLoggingStatusText()
                startLogStatusUpdates()
            }
            return
        }
        suppressSaveRxCallback = true
        saveRxCheckBox.isChecked = logging
        suppressSaveRxCallback = false
        if (logging) {
            lastSavedFile = service?.currentLogFile()
            updateLoggingStatusText()
            startLogStatusUpdates()
        } else {
            stopLogStatusUpdates()
        }
    }

    override fun onDestroyView() {
        stopLogStatusUpdates()
        super.onDestroyView()
    }

    private fun refreshFilenameHistory() {
        val ctx = context ?: return
        val history = SppPreferences.getFilenameHistory(ctx)
        val adapter = filenameHistoryAdapter
            ?: ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, ArrayList(history)).also {
                filenameHistoryAdapter = it
                filenameEditText.setAdapter(it)
            }
        adapter.clear()
        adapter.addAll(history)
        adapter.notifyDataSetChanged()
    }

    private fun setupFilenameInput() {
        val ctx = requireContext()
        filenameEditText.threshold = 0
        val last = SppPreferences.getLastFilename(ctx)
        if (last.isNotEmpty() && filenameEditText.text.isNullOrEmpty()) {
            filenameEditText.setText(last)
            filenameEditText.setSelection(filenameEditText.text.length)
        }
        refreshFilenameHistory()
        filenameEditText.setOnClickListener { filenameEditText.showDropDown() }
        filenameEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) filenameEditText.showDropDown()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText = view.findViewById(R.id.receive_text)
        receiveScroll = view.findViewById(R.id.receive_scroll)
        receiveText.setTextColor(resources.getColor(R.color.colorRecieveText))
        sendText = view.findViewById(R.id.send_text)
        clearSendBtn = view.findViewById(R.id.clear_send_btn)
        editBytesBtn = view.findViewById(R.id.edit_bytes_btn)
        hexWatcher = TextUtil.HexWatcher(sendText).also { it.enable(hexEnabled) }
        sendText.addTextChangedListener(hexWatcher)
        updateSendInputUi()

        clearSendBtn.setOnClickListener {
            sendText.setText("")
            sendText.requestFocus()
        }
        editBytesBtn.setOnClickListener { showHexByteEditor() }
        view.findViewById<View>(R.id.dropdown_arrow).setOnClickListener { if (hexEnabled) showPresetDialog() }

        filenameEditText = view.findViewById(R.id.filenameEditText)
        updateFilenameBtn = view.findViewById(R.id.updateFilenameBtn)
        shareButton = view.findViewById(R.id.shareButton)
        statusTextView = view.findViewById(R.id.statusTextView)
        saveRxCheckBox = view.findViewById(R.id.save_rx_checkbox)
        payloadStringCheckBox = view.findViewById(R.id.payload_string_checkbox)
        setupFilenameInput()
        updateFilenameBtn.isEnabled = false
        updateFilenameBtn.setOnClickListener { updateRxLogFilename() }
        shareButton.setOnClickListener { showAppChooser() }
        payloadStringCheckBox.isChecked = payloadStringEnabled
        payloadStringCheckBox.setOnCheckedChangeListener { _, isChecked ->
            payloadStringEnabled = isChecked
            service?.setPayloadStringEnabled(isChecked)
        }
        saveRxCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSaveRxCallback) return@setOnCheckedChangeListener
            if (isChecked) {
                runWithStoragePermission { startRxFileLogging() }
            } else {
                pendingStorageAction = null
                stopRxFileLogging()
            }
        }
        view.findViewById<View>(R.id.send_btn).setOnClickListener { send(sendText.text.toString()) }
        syncSaveRxCheckBoxFromService()
        return view
    }

    private fun updateSendInputUi() {
        sendText.hint = if (hexEnabled) "HEX，可点铅笔按字节改" else "文本"
        editBytesBtn.visibility = if (hexEnabled) View.VISIBLE else View.GONE
        sendText.typeface = if (hexEnabled) {
            android.graphics.Typeface.MONOSPACE
        } else {
            android.graphics.Typeface.DEFAULT
        }
    }

    private fun showHexByteEditor() {
        if (!hexEnabled) return
        val bytes = TextUtil.fromHexString(sendText.text).copyOf()
        if (bytes.isEmpty()) {
            Toast.makeText(activity, "请先输入十六进制数据", Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val scroll = ScrollView(ctx)
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
            setPadding(pad, pad, pad, pad / 2)
        }
        scroll.addView(grid)

        fun refreshGrid() {
            grid.removeAllViews()
            bytes.forEachIndexed { index, value ->
                val cell = Button(ctx).apply {
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(pad / 2, pad, pad / 2, pad)
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    text = String.format(Locale.US, "[%d]\n%02X", index, value)
                    setOnClickListener {
                        val input = EditText(ctx).apply {
                            setText(String.format(Locale.US, "%02X", value))
                            setSelectAllOnFocus(true)
                            selectAll()
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            hint = "00-FF"
                            filters = arrayOf(android.text.InputFilter.LengthFilter(2))
                            setPadding(pad * 2, pad, pad * 2, pad)
                        }
                        val editDialog = AlertDialog.Builder(ctx)
                            .setTitle("修改第 $index 字节")
                            .setView(input)
                            .setPositiveButton("确定", null)
                            .setNegativeButton("取消", null)
                            .create()
                        editDialog.setOnShowListener {
                            editDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val raw = input.text.toString().trim()
                                if (raw.isEmpty() || raw.any {
                                        it !in '0'..'9' && it !in 'A'..'F' && it !in 'a'..'f'
                                    }) {
                                    Toast.makeText(ctx, "请输入 1~2 位十六进制", Toast.LENGTH_SHORT).show()
                                    return@setOnClickListener
                                }
                                bytes[index] = TextUtil.fromHexString(raw).firstOrNull() ?: 0
                                refreshGrid()
                                editDialog.dismiss()
                            }
                            input.requestFocus()
                        }
                        editDialog.show()
                    }
                }
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
                grid.addView(cell, lp)
            }
        }
        refreshGrid()

        AlertDialog.Builder(ctx)
            .setTitle("按字节编辑（点选修改）")
            .setView(scroll)
            .setPositiveButton("应用") { _, _ ->
                sendText.setText(TextUtil.toHexString(bytes))
                sendText.setSelection(sendText.text.length)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAppChooser() {
        val file = service?.currentLogFile() ?: lastSavedFile
        if (file == null || !file.exists()) {
            Toast.makeText(activity, "请先勾选同步保存并产生日志文件", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val act = activity ?: return
            val fileUri = FileProvider.getUriForFile(act, "${act.packageName}.spp.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, fileUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (shareIntent.resolveActivity(act.packageManager) != null) {
                startActivity(Intent.createChooser(shareIntent, "选择分享方式"))
            } else {
                Toast.makeText(act, "没有找到可以处理此分享的应用", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("FileShare", "分享失败", e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.hex).isChecked = hexEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.backgroundNotification).isChecked = service?.areNotificationsEnabled() == true
        } else {
            menu.findItem(R.id.backgroundNotification).isChecked = true
            menu.findItem(R.id.backgroundNotification).isEnabled = false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clear -> {
                clearReceiveDisplay()
                true
            }
            R.id.newline -> {
                val newlineNames = resources.getStringArray(R.array.newline_names)
                val newlineValues = resources.getStringArray(R.array.newline_values)
                val pos = newlineValues.indexOf(newline)
                AlertDialog.Builder(requireContext())
                    .setTitle("Newline")
                    .setSingleChoiceItems(newlineNames, pos) { dialog, which ->
                        newline = newlineValues[which]
                        dialog.dismiss()
                    }.create().show()
                true
            }
            R.id.hex -> {
                hexEnabled = !hexEnabled
                protocolDecoder.reset()
                sendText.setText("")
                hexWatcher.enable(hexEnabled)
                service?.setLogHexEnabled(hexEnabled)
                updateSendInputUi()
                item.isChecked = hexEnabled
                true
            }
            R.id.backgroundNotification -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (service?.areNotificationsEnabled() == false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        showNotificationSettings()
                    }
                }
                true
            }
            R.id.pause_receive -> {
                pauseRx = !pauseRx
                item.isChecked = pauseRx
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun connect() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
            status("connecting...")
            connected = Connected.Pending
            val sppUuid = SppPreferences.getServiceUuid(requireContext())
            val socket = SerialSocket(requireActivity().applicationContext, device, sppUuid)
            service?.connect(socket)
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        protocolDecoder.reset()
        service?.disconnect()
    }

    private fun send(str: String) {
        statusTextView.text = ""
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            if (hexEnabled) {
                val sb = StringBuilder()
                TextUtil.toHexString(sb, PrivateProtocol.normalizeOutgoingFrame(TextUtil.fromHexString(str)))
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val spn = SpannableStringBuilder("[$timestamp] $msg\n")
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText.append(spn)
            trimReceiveDisplayIfNeeded()
            scrollReceiveToBottom()
            service?.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (data in datas) {
            if (hexEnabled) {
                if (pauseRx) continue
                val frames = protocolDecoder.append(data)
                if (frames.isEmpty()) {
                    if (!protocolDecoder.hasPendingData() && !PrivateProtocol.mayContainHeader(data)) {
                        spn.append("---RX---").append(timeFmt.format(Date())).append("---\n")
                        spn.append(TextUtil.toHexString(data)).append("\n\n")
                    }
                } else {
                    frames.forEach { appendPrivateProtocolFrame(spn, it, timeFmt) }
                }
            } else {
                var msg = String(data)
                if (newline == TextUtil.newline_crlf && msg.isNotEmpty()) {
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                    if (pendingNewline && msg[0] == '\n') {
                        if (spn.length >= 2) {
                            spn.delete(spn.length - 2, spn.length)
                        } else {
                            val edt: Editable? = receiveText.editableText
                            if (edt != null && edt.length >= 2) edt.delete(edt.length - 2, edt.length)
                        }
                    }
                    pendingNewline = msg.last() == '\r'
                }
                spn.append(TextUtil.toCaretString(msg, newline.isNotEmpty()))
            }
        }
        if (spn.isEmpty()) return
        appendReceiveDisplay(spn)
        scrollReceiveToBottom(throttled = true)
    }

    private fun appendReceiveDisplay(text: CharSequence) {
        receiveText.append(text)
        trimReceiveDisplayIfNeeded()
    }

    private fun clearReceiveDisplay() {
        receiveText.text = ""
    }

    private fun trimReceiveDisplayIfNeeded() {
        val editable = receiveText.editableText ?: return
        val overflow = editable.length - MAX_RECEIVE_DISPLAY_CHARS
        if (overflow <= 0) return
        val deleteCount = min(editable.length, overflow + MAX_RECEIVE_DISPLAY_CHARS / 5)
        editable.delete(0, deleteCount)
    }

    private fun appendPayloadAsString(spn: SpannableStringBuilder, payload: ByteArray) {
        val start = spn.length
        spn.append("Payload String: ")
        spn.append(String(payload, StandardCharsets.UTF_8))
        spn.append('\n')
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            start,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendPrivateProtocolFrame(
        spn: SpannableStringBuilder,
        frame: PrivateProtocol.Frame,
        timeFmt: SimpleDateFormat,
    ) {
        val data = frame.bytes
        val payload = frame.payload()
        val payloadLength = payload.size
        val subId = frame.subId()
        val wantPayloadString = payloadStringEnabled ||
            (::payloadStringCheckBox.isInitialized && payloadStringCheckBox.isChecked)

        Log.d(
            "MyTag",
            String.format(
                Locale.US,
                "receive frame, rx_len:%d, cmd_len:%d, cmd:0x%02X, group:0x%02X, subId:0x%02X, payload_len:%d, res:0x%02X, payloadString=%s",
                data.size, frame.commandLength, frame.cmd(), frame.group(), subId, payloadLength,
                frame.responseCode(), wantPayloadString
            )
        )

        // Timestamp + one complete packet per block, blank line as separator.
        spn.append("---RX---").append(timeFmt.format(Date())).append("---\n")
        spn.append(TextUtil.toHexString(data)).append('\n')

        if (wantPayloadString && payloadLength > 0) {
            appendPayloadAsString(spn, payload)
            spn.append('\n')
            return
        }

        when {
            subId == 0x24 && (payloadLength == 21 || payloadLength == 42) -> {
                val imuParser = IMUUploadDataParser(payload)
                spn.append(imuParser.toStringRepresentation()).append('\n')
                imuParser.printIMUData()
            }
            subId == 0x22 -> {
                if (payloadLength == 48 || payloadLength == 96) {
                    val touchParser = TouchUploadDataParser(payload)
                    spn.append(touchParser.toStringRepresentation()).append('\n')
                    touchParser.printData()
                } else if (payloadLength >= 2) {
                    if ((payload[0].toInt() == 0x69 && payload[1].toInt() == 0x64) ||
                        (payload[0].toInt() == 0x48 && payload[1].toInt() == 0x58)
                    ) {
                        spn.append(String(payload, StandardCharsets.UTF_8)).append('\n')
                    }
                }
            }
            subId == 0x21 && payloadLength == 1 -> {
                spn.append(WearTest.showWearStatus(payload[0]))
                if (spn.isNotEmpty() && spn[spn.length - 1] != '\n') spn.append('\n')
            }
            subId == 0x23 && payloadLength == 1 -> {
                spn.append(ImuKeyPressTest.showImuKeyPress(payload[0]))
                if (spn.isNotEmpty() && spn[spn.length - 1] != '\n') spn.append('\n')
            }
            subId == 0x06 && payloadLength == 10 -> {
                parser = TapParameterParser(payload)
                spn.append(parser.toString())
                if (spn.isNotEmpty() && spn[spn.length - 1] != '\n') spn.append('\n')
            }
            subId == 0x85 && payloadLength >= 10 -> {
                val sequence = (payload[6].toInt() and 0xFF) + ((payload[7].toInt() and 0xFF) shl 8)
                val length = (payload[8].toInt() and 0xFF) + ((payload[9].toInt() and 0xFF) shl 8)
                spn.append("seq:$sequence, audio data length:$length\n")
            }
            (subId == 0x28 || subId == 0x2B) && payloadLength >= 12 -> {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val accel = ShortArray(3) { buffer.short }
                val gyro = ShortArray(3) { buffer.short }
                spn.append("accel_x:${accel[0]}, accel_y:${accel[1]}, accel_z:${accel[2]}\n")
                spn.append("gyro_x:${gyro[0]}, gyro_y:${gyro[1]}, gyro_z:${gyro[2]}\n")
            }
            frame.group() == 0xFF && subId == 0x08 &&
                payloadLength == SleepUploadDataParser.PAYLOAD_SIZE -> {
                spn.append(SleepUploadDataParser(payload).toStringRepresentation()).append('\n')
            }
            frame.group() == 0x04 && subId == 0x26 && payloadLength >= 8 -> {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val offset = ShortArray(2) { buffer.short }
                val nvBaseData = ShortArray(2) { buffer.short }
                spn.append("offset[0]:${offset[0]}, acce:${offset[1]}\n")
                spn.append("nv_base_data[0]:${nvBaseData[0]}, nv_base_data[1]:${nvBaseData[1]}\n")
            }
        }
        spn.append('\n')
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder("$str\n")
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        appendReceiveDisplay(spn)
        scrollReceiveToBottom()
    }

    private fun scrollReceiveToBottom(throttled: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttled && now - lastAutoScrollMs < AUTO_SCROLL_MIN_INTERVAL_MS) return
        lastAutoScrollMs = now
        receiveScroll.post { receiveScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showNotificationSettings() {
        startActivity(Intent().setAction("android.settings.APP_NOTIFICATION_SETTINGS")
            .putExtra("android.provider.extra.APP_PACKAGE", activity?.packageName))
    }

    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception) {
        status("connection failed: ${e.message}")
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        val datas = ArrayDeque<ByteArray>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        receive(datas)
    }

    override fun onSerialIoError(e: Exception) {
        status("connection lost: ${e.message}")
        disconnect()
    }

    companion object {
        /** Soft cap for on-screen RX buffer; older text is discarded. */
        private const val MAX_RECEIVE_DISPLAY_CHARS = 200_000
        private const val AUTO_SCROLL_MIN_INTERVAL_MS = 200L
    }
}
