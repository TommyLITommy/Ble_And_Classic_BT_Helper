/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.bhm.ble.BleManager
import com.bhm.ble.device.BleDevice
import com.bhm.ble.utils.BleUtil
import com.bhm.demo.BaseActivity
import com.bhm.demo.R
import com.bhm.demo.adapter.DetailsExpandAdapter
import com.bhm.demo.adapter.LoggerListAdapter
import com.bhm.demo.databinding.ActivityDetailBinding
import com.bhm.demo.databinding.DialogWriteDataBinding
import com.bhm.demo.entity.CharacteristicNode
import com.bhm.demo.entity.LogEntity
import com.bhm.demo.entity.OperateType
import com.bhm.demo.entity.PresetWriteCommand
import com.bhm.demo.utils.BleReceiveDataSaver
import com.bhm.demo.utils.PresetWriteCommandStore
import com.bhm.demo.vm.DetailViewModel
import com.bhm.support.sdk.core.AppTheme
import com.bhm.support.sdk.entity.MessageEvent
import com.bhm.support.sdk.utils.ViewUtil
import kotlinx.coroutines.launch
import java.util.logging.Level


/**
 * 服务，特征 操作页面
 *
 * @author Buhuiming
 * @date 2023年06月01日 09时17分
 */
class DetailOperateActivity : BaseActivity<DetailViewModel, ActivityDetailBinding>() {

    override fun createViewModel() = DetailViewModel(application)

    private var bleDevice: BleDevice? = null

    private var expandAdapter: DetailsExpandAdapter? = null

    private var loggerListAdapter: LoggerListAdapter? = null

    private var disConnectWhileClose = false // 关闭页面后是否断开连接

    private var connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED

    private val receiveText = StringBuilder()

    private val receiveDataSaver = BleReceiveDataSaver(this)

    private var ignoreSaveSwitchCallback = false

    private var lastSavedFilePath: String? = null

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                startSaveReceive()
            } else {
                setSaveSwitchChecked(false)
            }
        }

    private val writeStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSaveReceive()
            } else {
                setSaveSwitchChecked(false)
            }
        }

    private var operateCallback: ((checkBox: CheckBox?,
                                   operateType: OperateType,
                                   isChecked: Boolean,
                                   node: CharacteristicNode) -> Unit)? = null

    override fun initData() {
        super.initData()
        initBackHandling()
        val controller = WindowCompat.getInsetsController(
            window,
            window.decorView
        )
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
        viewBinding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        updateLogPanelToggleIcon()
        bleDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", BleDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        disConnectWhileClose = intent.getBooleanExtra("disConnectWhileClose", false)

        if (bleDevice == null) {
            finish()
            return
        }
        viewBinding.tvName.text = buildString {
            append("设备广播名：")
            append(getBleDevice().deviceName)
            append("\r\n")
            append("地址：${getBleDevice().deviceAddress}")
            append("\r\n")
            append("连接成功时已自动完成服务/特征发现，以下为当前 GATT 树")
        }
        initList()
    }

    private fun initBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (disConnectWhileClose) {
                        BleManager.get().close(getBleDevice())
                        setResult(0, Intent())
                    } else {
                        BleManager.get().removeAllCharacterCallback(getBleDevice())
                        setResult(0, null)
                    }
                    finish()
                }
            }
        )
    }

    private fun getBleDevice(): BleDevice {
        return bleDevice ?: run {
            finish()
            BleDevice(null, "", "", 0, 0, null, null)
        }
    }

    private fun initList() {
        val layoutManager = LinearLayoutManager(applicationContext)
        layoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.recyclerView.layoutManager = layoutManager
        operateCallback = { _, operateType, isChecked, node ->
            when (operateType) {
                is OperateType.Write -> showWriteDataDialog(node)
                is OperateType.Read -> {
                    viewModel.readData(getBleDevice(), node)
                }
                is OperateType.Notify -> {
                    if (isChecked) {
                        viewModel.notify(getBleDevice(), node)
                    } else {
                        viewModel.stopNotify(getBleDevice(), node)
                    }
                }
                is OperateType.Indicate -> {
                    if (isChecked) {
                        viewModel.indicate(getBleDevice(), node)
                    } else {
                        viewModel.stopIndicate(getBleDevice(), node)
                    }
                }
            }
        }
        expandAdapter = DetailsExpandAdapter(
            viewModel.getListData(getBleDevice()),
            operateCallback
        )
        viewBinding.recyclerView.adapter = expandAdapter
        // 默认不展开任何服务；用户点击服务 item 才展开/收起

        val logLayoutManager = LinearLayoutManager(applicationContext)
        logLayoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.logRecyclerView.setHasFixedSize(true)
        viewBinding.logRecyclerView.layoutManager = logLayoutManager
        (viewBinding.recyclerView.itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
        loggerListAdapter = LoggerListAdapter(viewModel.listLogData)
        viewBinding.logRecyclerView.adapter = loggerListAdapter

    }

    @SuppressLint("NotifyDataSetChanged")
    override fun initEvent() {
        super.initEvent()

        lifecycleScope.launch {
            viewModel.listLogStateFlow.collect {
                viewModel.listLogData.add(it)
                val position = viewModel.listLogData.size - 1
                loggerListAdapter?.notifyItemInserted(position)
                viewBinding.logRecyclerView.smoothScrollToPosition(position)
            }
        }
        lifecycleScope.launch {
            viewModel.receiveChunkFlow.collect { chunk ->
                receiveText.append(chunk)
                viewBinding.tvReceiveData.text = receiveText
                viewBinding.scrollReceive.post {
                    viewBinding.scrollReceive.fullScroll(View.FOCUS_DOWN)
                }
                if (receiveDataSaver.isRecording) {
                    receiveDataSaver.append(chunk)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.listRefreshStateFlow.collect {
                if (it.isNotEmpty()) {
                    expandAdapter?.notifyDataSetChanged()
                }
            }
        }

        viewBinding.btnConnectionPriority.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            when (connectionPriority) {
                BluetoothGatt.CONNECTION_PRIORITY_BALANCED -> connectionPriority =
                    BluetoothGatt.CONNECTION_PRIORITY_HIGH
                BluetoothGatt.CONNECTION_PRIORITY_HIGH -> connectionPriority =
                    BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER -> connectionPriority =
                    BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            }
            viewModel.setConnectionPriority(getBleDevice(), connectionPriority)
        }

        viewBinding.btnClear.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            loggerListAdapter?.notifyItemRangeRemoved(0, viewModel.listLogData.size)
            viewModel.listLogData.clear()
        }

        viewBinding.btnClearReceive.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            receiveText.clear()
            viewBinding.tvReceiveData.text = ""
            viewModel.clearReceiveData()
        }

        viewBinding.switchSaveReceive.setOnCheckedChangeListener { _: CompoundButton, isChecked ->
            if (ignoreSaveSwitchCallback) return@setOnCheckedChangeListener
            if (isChecked) {
                checkPermissionsAndStartSave()
            } else {
                stopSaveReceive(showToast = true)
            }
        }

        viewBinding.btnSetMtu.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            viewModel.setMtu(getBleDevice())
        }

        viewBinding.btnReadRssi.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) {
                return@setOnClickListener
            }
            viewModel.readRssi(getBleDevice())
        }

    }

    fun showContent(@Suppress("UNUSED_PARAMETER") view: View) {
        if (viewBinding.llContent.visibility == View.VISIBLE) {
            viewBinding.llContent.visibility = View.GONE
        } else {
            viewBinding.llContent.visibility = View.VISIBLE
        }
        updateLogPanelToggleIcon()
    }

    private fun updateLogPanelToggleIcon() {
        val expanded = viewBinding.llContent.visibility == View.VISIBLE
        viewBinding.ivToggleLog.setImageResource(
            if (expanded) R.drawable.icon_down else R.drawable.icon_right
        )
    }

    override fun onResume() {
        super.onResume()
        refreshGattTreeIfEmpty()
    }

    private fun refreshGattTreeIfEmpty() {
        val dev = bleDevice ?: return
        if (!BleManager.get().isConnected(dev)) return
        if ((expandAdapter?.data?.size ?: 0) > 0) return
        val newData = viewModel.getListData(dev)
        if (newData.isEmpty()) return
        expandAdapter = DetailsExpandAdapter(newData, operateCallback)
        viewBinding.recyclerView.adapter = expandAdapter
        // 默认不展开任何服务；用户点击服务 item 才展开/收起
        viewModel.addLogMsg(LogEntity(Level.INFO, "已刷新 GATT 服务列表（${newData.size}）"))
    }

    private fun showWriteDataDialog(node: CharacteristicNode) {
        if (!BleManager.get().isConnected(getBleDevice())) {
            Toast.makeText(applicationContext, "设备未连接，无法写数据", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogWriteDataBinding.inflate(LayoutInflater.from(this))
        dialogBinding.rgWriteFormat.setOnCheckedChangeListener { _, checkedId ->
            dialogBinding.etWriteContent.hint = if (checkedId == dialogBinding.rbWriteHex.id) {
                getString(R.string.hint_write_hex)
            } else {
                getString(R.string.hint_write_utf8)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("写数据")
            .setMessage(node.characteristicUUID)
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送", null)
            .create()

        fun bindPresetCommands() {
            bindPresetButtons(
                dialogBinding.llPresetCommands,
                dialogBinding.tvPresetEmpty,
            ) { preset ->
                performWrite(node, preset.payload, preset.hexMode)
            }
        }
        bindPresetCommands()

        dialogBinding.btnManagePreset.setOnClickListener {
            startActivity(Intent(this, PresetWriteCommandActivity::class.java)) { _, _ ->
                bindPresetCommands()
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val content = dialogBinding.etWriteContent.text.toString()
                val hexMode = dialogBinding.rbWriteHex.isChecked
                performWrite(node, content, hexMode)
            }
        }
        dialog.show()
    }

    private fun bindPresetButtons(
        container: ViewGroup,
        emptyView: View,
        onPresetClick: (PresetWriteCommand) -> Unit,
    ) {
        container.removeAllViews()
        val presets = PresetWriteCommandStore.loadAll(applicationContext)
        emptyView.visibility = if (presets.isEmpty()) View.VISIBLE else View.GONE
        val marginVertical = dp(4)
        presets.forEach { preset ->
            val button = Button(this).apply {
                text = preset.name
                isAllCaps = false
                textSize = 13f
                background = ContextCompat.getDrawable(context, R.drawable.bg_preset_command_button)
                backgroundTintList = null
                setTextColor(ContextCompat.getColor(context, R.color.preset_btn_text))
                val paddingH = dp(12)
                val paddingV = dp(10)
                setPadding(paddingH, paddingV, paddingH, paddingV)
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = marginVertical
                    bottomMargin = marginVertical
                }
                setOnClickListener {
                    if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
                    onPresetClick(preset)
                }
            }
            container.addView(button)
        }
    }

    private fun performWrite(node: CharacteristicNode, content: String, hexMode: Boolean) {
        if (content.isBlank()) {
            Toast.makeText(applicationContext, "请输入数据", Toast.LENGTH_SHORT).show()
            return
        }
        if (hexMode && BleUtil.hexStringToByteArray(content) == null) {
            Toast.makeText(applicationContext, getString(R.string.write_hex_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.writeData(getBleDevice(), node, content, hexMode)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun setSaveSwitchChecked(checked: Boolean) {
        ignoreSaveSwitchCallback = true
        viewBinding.switchSaveReceive.isChecked = checked
        ignoreSaveSwitchCallback = false
    }

    private fun updateSaveStatusIdle() {
        viewBinding.tvReceiveSaveStatus.text = getString(R.string.detail_receive_save_idle)
    }

    private fun updateSaveStatusRecording(path: String) {
        viewBinding.tvReceiveSaveStatus.text = getString(R.string.detail_receive_save_recording, path)
    }

    private fun checkPermissionsAndStartSave() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    startSaveReceive()
                } else {
                    requestManageStoragePermission()
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    startSaveReceive()
                } else {
                    writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            else -> startSaveReceive()
        }
    }

    private fun requestManageStoragePermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_receive_save_permission_title)
            .setMessage(R.string.detail_receive_save_permission_message)
            .setPositiveButton(R.string.detail_receive_save_go_settings) { _, _ ->
                try {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    manageStorageLauncher.launch(
                        Intent().setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                setSaveSwitchChecked(false)
            }
            .setOnCancelListener {
                setSaveSwitchChecked(false)
            }
            .show()
    }

    private fun startSaveReceive() {
        val result = receiveDataSaver.start(getBleDevice().deviceAddress.orEmpty())
        result.onSuccess { file ->
            lastSavedFilePath = file.absolutePath
            updateSaveStatusRecording(file.absolutePath)
            Toast.makeText(applicationContext, getString(R.string.detail_receive_save_recording, file.absolutePath), Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            setSaveSwitchChecked(false)
            val message = error.message ?: error.toString()
            viewBinding.tvReceiveSaveStatus.text = getString(R.string.detail_receive_save_failed, message)
            Toast.makeText(applicationContext, getString(R.string.detail_receive_save_failed, message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSaveReceive(showToast: Boolean) {
        if (!receiveDataSaver.isRecording && lastSavedFilePath == null) {
            updateSaveStatusIdle()
            return
        }
        val path = receiveDataSaver.currentFilePath ?: lastSavedFilePath
        receiveDataSaver.stop()
        if (path != null) {
            lastSavedFilePath = path
            viewBinding.tvReceiveSaveStatus.text = getString(R.string.detail_receive_save_stopped, path)
            if (showToast) {
                Toast.makeText(applicationContext, getString(R.string.detail_receive_save_stopped, path), Toast.LENGTH_SHORT).show()
            }
        } else {
            updateSaveStatusIdle()
        }
    }

    /**
     * 接收到断开通知
     */
    override fun onMessageEvent(event: MessageEvent?) {
        super.onMessageEvent(event)
        event?.let {
            val device = it.data as? BleDevice ?: return
            if (getBleDevice() == device) {
                BleManager.get().close(getBleDevice())
                setResult(0, null)
                finish()
            }
        }
    }

    override fun onDestroy() {
        stopSaveReceive(showToast = false)
        super.onDestroy()
        expandAdapter = null
        operateCallback = null
    }
}
