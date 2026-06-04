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
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

class TerminalFragment : Fragment(), ServiceConnection, SerialListener,
    TapParameterDialogFragment.OnParameterSetListener {

    private enum class Connected { False, Pending, True }

    private var deviceAddress: String? = null
    private var service: SerialService? = null

    private lateinit var receiveText: TextView
    private lateinit var sendText: TextView
    private lateinit var hexWatcher: TextUtil.HexWatcher

    private var connected = Connected.False
    private var initialStart = true
    private var hexEnabled = false
    private var pendingNewline = false
    private var newline = TextUtil.newline_crlf
    private var pauseRx = false

    private lateinit var filenameEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var statusTextView: TextView
    private var lastSavedFile: File? = null
    private var parser = TapParameterParser()
    private val protocolDecoder = PrivateProtocolStreamDecoder()
    private val presetCommands = LinkedHashMap<String, String>()
    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                saveFileToDownloads()
            }
        }
    private val writeStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveFileToDownloads()
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
        if (initialStart && isResumed) {
            initialStart = false
            activity?.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    private fun initPresetCommands() {
        presetCommands["A5001_FPC_MIC"] = "3A 5E 20 05 0a 09 00 00 06 00 00 00 00 00 00 00 00"
        presetCommands["A5001_板载_MIC"] = "3A 5E 20 05 09 09 00 00 69 00 00 00 00 00 00 00 00"
        presetCommands["A5001_关机"] = "3A 5E 20 02 13 09 00 00 90 00 00 00 00 00 00 00 00"
        presetCommands["A5001_重启"] = "3A 5E 20 02 14 09 00 00 AE 00 00 00 00 00 00 00 00"
        presetCommands["A5001_船运"] = "3A 5E 20 02 15 09 00 00 4E 00 00 00 00 00 00 00 00"
        presetCommands["A5001_获取固件版本号"] = "3A 5E 20 01 0E 09 00 00 F3 00 00 00 00 00 00 00 00"
        presetCommands["A5001_获取耳机电量"] = "3A 5E 20 01 0F 09 00 00 13 00 00 00 00 00 00 00 00"
        presetCommands["A5001_获取耳机MAC"] = "3A 5E 20 01 10 09 00 00 84 00 00 00 00 00 00 00 00"
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
            sendText.text = hexString.toString().trim()
        } catch (_: IOException) {
            sendText.text = "Error converting parameters to binary"
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
                sendText.text = selectedCommand
                Toast.makeText(activity, "已选择命令：$selectedName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun saveFileToDownloads() {
        var filename = filenameEditText.text.toString().trim()
        if (filename.isEmpty()) {
            statusTextView.text = "请输入文件名"
            return
        }
        if (!filename.endsWith(".txt")) filename += ".txt"

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                statusTextView.text = "无法创建Download目录"
                return
            }
            var file = File(downloadsDir, filename)
            var counter = 1
            while (file.exists()) {
                file = File(downloadsDir, filename.replace(".txt", "($counter).txt"))
                counter++
            }
            FileOutputStream(file).use { fos ->
                fos.write(receiveText.text.toString().toByteArray())
                activity?.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                lastSavedFile = file
                val successMsg = "文件保存成功: ${file.absolutePath}"
                statusTextView.text = successMsg
                Toast.makeText(activity, successMsg, Toast.LENGTH_LONG).show()
                receiveText.text = ""
            }
        } catch (e: IOException) {
            val errorMsg = "保存失败: ${e.message}"
            statusTextView.text = errorMsg
            Log.e("FileSave", errorMsg, e)
            Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndSave() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) saveFileToDownloads() else requestManageStoragePermission()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (activity?.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    saveFileToDownloads()
                } else {
                    writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            else -> saveFileToDownloads()
        }
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
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText = view.findViewById(R.id.receive_text)
        receiveText.setTextColor(resources.getColor(R.color.colorRecieveText))
        receiveText.movementMethod = ScrollingMovementMethod.getInstance()
        sendText = view.findViewById(R.id.send_text)
        hexWatcher = TextUtil.HexWatcher(sendText).also { it.enable(hexEnabled) }
        sendText.addTextChangedListener(hexWatcher)
        sendText.hint = if (hexEnabled) "HEX mode" else ""

        view.findViewById<View>(R.id.dropdown_arrow).setOnClickListener { if (hexEnabled) showPresetDialog() }
        view.findViewById<View>(R.id.tapParam).setOnClickListener {
            if (hexEnabled) TapParameterDialogFragment.newInstance(parser)
                .show(childFragmentManager, "TapParametersDialog")
        }

        filenameEditText = view.findViewById(R.id.filenameEditText)
        saveButton = view.findViewById(R.id.saveButton)
        shareButton = view.findViewById(R.id.shareButton)
        statusTextView = view.findViewById(R.id.statusTextView)
        saveButton.setOnClickListener { checkPermissionsAndSave() }
        shareButton.setOnClickListener { showAppChooser() }
        view.findViewById<View>(R.id.send_btn).setOnClickListener { send(sendText.text.toString()) }
        return view
    }

    private fun showAppChooser() {
        val file = lastSavedFile
        if (file == null || !file.exists()) {
            Toast.makeText(activity, "请先保存有效的文件", Toast.LENGTH_SHORT).show()
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
                receiveText.text = ""
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
                sendText.text = ""
                hexWatcher.enable(hexEnabled)
                sendText.hint = if (hexEnabled) "HEX mode" else ""
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
            val socket = SerialSocket(requireActivity().applicationContext, device)
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
            service?.write(data)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()
        for (data in datas) {
            if (hexEnabled) {
                if (pauseRx) return
                val waitingForPrivateFrame = protocolDecoder.hasPendingData()
                val frames = protocolDecoder.append(data)
                if (frames.isEmpty() && !waitingForPrivateFrame && !PrivateProtocol.mayContainHeader(data)) {
                    spn.append(TextUtil.toHexString(data)).append("\n")
                }
                frames.forEach { appendPrivateProtocolFrame(spn, it) }
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
        spn.append("\n")
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        receiveText.append("---RX---$timestamp---\n")
        receiveText.append(spn)
    }

    private fun appendPrivateProtocolFrame(spn: SpannableStringBuilder, frame: PrivateProtocol.Frame) {
        val data = frame.bytes
        val payload = frame.payload()
        val payloadLength = payload.size
        val subId = frame.subId()
        Log.d(
            "MyTag",
            String.format(
                Locale.US,
                "receive frame, rx_len:%d, cmd_len:%d, cmd:0x%02X, group:0x%02X, subId:0x%02X, payload_len:%d, res:0x%02X",
                data.size, frame.commandLength, frame.cmd(), frame.group(), subId, payloadLength, frame.responseCode()
            )
        )
        spn.append(TextUtil.toHexString(data)).append("\n")
        when {
            subId == 0x24 && (payloadLength == 21 || payloadLength == 42) -> {
                val parser = IMUUploadDataParser(payload)
                spn.append(parser.toStringRepresentation()).append("\n\n")
                parser.printIMUData()
            }
            subId == 0x22 -> {
                if (payloadLength == 48 || payloadLength == 96) {
                    val parser = TouchUploadDataParser(payload)
                    spn.append(parser.toStringRepresentation()).append("\n\n")
                    parser.printData()
                } else if (payloadLength >= 2) {
                    if ((payload[0].toInt() == 0x69 && payload[1].toInt() == 0x64) ||
                        (payload[0].toInt() == 0x48 && payload[1].toInt() == 0x58)
                    ) {
                        spn.append(String(payload, StandardCharsets.UTF_8)).append("\n\n")
                    }
                }
            }
            subId == 0x21 && payloadLength == 1 -> spn.append(WearTest.showWearStatus(payload[0]))
            subId == 0x23 && payloadLength == 1 -> spn.append(ImuKeyPressTest.showImuKeyPress(payload[0]))
            subId == 0x06 && payloadLength == 10 -> {
                parser = TapParameterParser(payload)
                spn.append(parser.toString())
            }
            subId == 0x85 && payloadLength >= 10 -> {
                val sequence = (payload[6].toInt() and 0xFF) + ((payload[7].toInt() and 0xFF) shl 8)
                val length = (payload[8].toInt() and 0xFF) + ((payload[9].toInt() and 0xFF) shl 8)
                spn.append("seq:$sequence, audio data length:$length")
            }
            (subId == 0x28 || subId == 0x2B) && payloadLength >= 12 -> {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val accel = ShortArray(3) { buffer.short }
                val gyro = ShortArray(3) { buffer.short }
                spn.append("accel_x:${accel[0]}, accel_y:${accel[1]}, accel_z:${accel[2]}\n")
                spn.append("gyro_x:${gyro[0]}, gyro_y:${gyro[1]}, gyro_z:${gyro[2]}\n")
            }
            frame.group() == 0x04 && subId == 0x26 && payloadLength >= 8 -> {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val offset = ShortArray(2) { buffer.short }
                val nvBaseData = ShortArray(2) { buffer.short }
                spn.append("offset[0]:${offset[0]}, acce:${offset[1]}\n")
                spn.append("nv_base_data[0]:${nvBaseData[0]}, nv_base_data[1]:${nvBaseData[1]}\n")
            }
        }
    }

    private fun status(str: String) {
        val spn = SpannableStringBuilder("$str\n")
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText.append(spn)
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
    }
}
