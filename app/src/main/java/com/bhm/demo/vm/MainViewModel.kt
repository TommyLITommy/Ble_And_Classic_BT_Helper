package com.bhm.demo.vm

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.bhm.ble.BleManager
import com.bhm.ble.attribute.BleOptions
import com.bhm.ble.callback.BleConnectCallback
import com.bhm.ble.callback.BleScanCallback
import com.bhm.ble.data.BleConnectFailType
import com.bhm.ble.data.BleScanFailType
import com.bhm.ble.device.BleDevice
import com.bhm.ble.log.BleLogger
import com.bhm.ble.utils.BleUtil
import com.bhm.demo.BaseActivity
import com.bhm.demo.R
import com.bhm.demo.constants.LOCATION_PERMISSION
import com.bhm.demo.entity.RefreshBleDevice
import com.bhm.demo.entity.ScanDeviceUpdate
import com.bhm.demo.entity.ScanFilterMode
import com.bhm.support.sdk.common.BaseViewModel
import com.bhm.support.sdk.entity.MessageEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * @author Buhuiming
 * @date 2023年05月18日 10时49分
 */
class MainViewModel(private val application: Application) : BaseViewModel(application) {

    companion object {
        /** 单次扫描窗口（毫秒），分段扫描可降低系统 SCAN_FAILED_SCANNING_TOO_FREQUENTLY 概率 */
        private const val SCAN_SEGMENT_MILLIS = 12_000L
        /** 分段重试次数，足够长；用户点「停止」会 cancelScan 结束整段会话 */
        private const val SCAN_CONTINUOUS_RETRY_COUNT = 99_999
        private const val SCAN_RETRY_INTERVAL_MILLIS = 400L
    }

    private val scanUpdateMutableStateFlow = MutableStateFlow<ScanDeviceUpdate?>(null)
    val scanUpdateStateFlow: StateFlow<ScanDeviceUpdate?> = scanUpdateMutableStateFlow

    val listDRData = mutableListOf<BleDevice>()

    private val scanStopMutableStateFlow = MutableStateFlow(true)
    val scanStopStateFlow: StateFlow<Boolean> = scanStopMutableStateFlow

    private val deviceCountMutableStateFlow = MutableStateFlow(0)
    val deviceCountStateFlow: StateFlow<Int> = deviceCountMutableStateFlow

    private val refreshMutableStateFlow = MutableStateFlow(
        RefreshBleDevice(null, null)
    )
    val refreshStateFlow: StateFlow<RefreshBleDevice?> = refreshMutableStateFlow

    var scanFilterMode: ScanFilterMode = ScanFilterMode.NAMED_ONLY
    var nameFilterKeyword: String = ""

    /**
     * 初始化蓝牙组件
     */
    fun initBle() {
        BleManager.get().init(application,
            BleOptions.Builder()
                .setScanMillisTimeOut(SCAN_SEGMENT_MILLIS)
                .setScanRetryCountAndInterval(
                    SCAN_CONTINUOUS_RETRY_COUNT,
                    SCAN_RETRY_INTERVAL_MILLIS
                )
                .setConnectMillisTimeOut(5000)
                .setMaxConnectNum(2)
                .setConnectRetryCountAndInterval(2, 1000)
                .setStopScanWhenStartConnect(true)
                .setNeedCheckGps(true)
                .build()
        )
        BleManager.get().registerBluetoothStateReceiver {
            onStateOff {
                refreshMutableStateFlow.value = RefreshBleDevice(null, System.currentTimeMillis())
            }
        }
    }

    fun matchesFilter(bleDevice: BleDevice): Boolean {
        return when (scanFilterMode) {
            ScanFilterMode.ALL -> true
            ScanFilterMode.NAMED_ONLY -> !bleDevice.deviceName.isNullOrBlank()
            ScanFilterMode.NAME_FILTER -> {
                val keyword = nameFilterKeyword.trim()
                if (keyword.isEmpty()) {
                    !bleDevice.deviceName.isNullOrBlank()
                } else {
                    bleDevice.deviceName?.contains(keyword, ignoreCase = true) == true ||
                        bleDevice.deviceAddress?.contains(keyword, ignoreCase = true) == true
                }
            }
        }
    }

    /**
     * 检查权限、检查GPS开关、检查蓝牙开关
     */
    private suspend fun hasScanPermission(activity: BaseActivity<*, *>): Boolean {
        val isBleSupport = BleManager.get().isBleSupport()
        BleLogger.e("设备是否支持蓝牙: $isBleSupport")
        if (!isBleSupport) {
            return false
        }
        var hasScanPermission = suspendCoroutine { continuation ->
            activity.requestPermission(
                LOCATION_PERMISSION,
                {
                    BleLogger.d("获取到了权限")
                    try {
                        continuation.resume(true)
                    } catch (e: Exception) {
                        BleLogger.e(e.message)
                    }
                }, {
                    BleLogger.w("缺少定位权限")
                    try {
                        continuation.resume(false)
                    } catch (e: Exception) {
                        BleLogger.e(e.message)
                    }
                }
            )
        }
        if (hasScanPermission && !BleUtil.isGpsOpen(application)) {
            hasScanPermission = suspendCoroutine {
                activity.startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                ) { _, _ ->
                    val enable = BleUtil.isGpsOpen(application)
                    BleLogger.i("是否打开了GPS: $enable")
                    try {
                        it.resume(enable)
                    } catch (e: Exception) {
                        BleLogger.e(e.message)
                    }
                }
            }
        }
        if (hasScanPermission && !BleManager.get().isBleEnable()) {
            hasScanPermission = suspendCoroutine {
                activity.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                ) { _, _ ->
                    viewModelScope.launch {
                        delay(1000)
                        val enable = BleManager.get().isBleEnable()
                        BleLogger.i("是否打开了蓝牙: $enable")
                        try {
                            it.resume(enable)
                        } catch (e: Exception) {
                            BleLogger.e(e.message)
                        }
                    }
                }
            }
        }
        return hasScanPermission
    }

    /**
     * 开始扫描
     */
    fun startScan(activity: BaseActivity<*, *>) {
        if (BleManager.get().isScanning()) {
            BleLogger.w("扫描已在进行中")
            return
        }
        launchScan(activity)
    }

    /**
     * 下拉刷新：先停止当前扫描（若有），再重新开始扫描
     */
    fun refreshScan(activity: BaseActivity<*, *>) {
        if (BleManager.get().isScanning()) {
            stopScan()
        }
        launchScan(activity)
    }

    private fun launchScan(activity: BaseActivity<*, *>) {
        viewModelScope.launch {
            val hasScanPermission = hasScanPermission(activity)
            if (hasScanPermission) {
                BleManager.get().startScan(getScanCallback(true))
            } else {
                BleLogger.e("请检查权限、检查GPS开关、检查蓝牙开关")
                scanStopMutableStateFlow.value = true
            }
        }
    }

    private fun getScanCallback(showData: Boolean): BleScanCallback.() -> Unit {
        return {
            onScanStart {
                BleLogger.d("onScanStart")
                scanStopMutableStateFlow.value = false
            }
            onLeScan { bleDevice, _ ->
                if (!showData) return@onLeScan
                val index = listDRData.indexOfFirst { it.deviceAddress == bleDevice.deviceAddress }
                if (index >= 0) {
                    val old = listDRData[index]
                    if (old.rssi != bleDevice.rssi ||
                        old.scanRecord?.contentEquals(bleDevice.scanRecord) != true
                    ) {
                        upsertDevice(bleDevice, isNew = false)
                    }
                }
            }
            onLeScanDuplicateRemoval { bleDevice, _ ->
                if (!showData || !matchesFilter(bleDevice)) return@onLeScanDuplicateRemoval
                val index = listDRData.indexOfFirst { it.deviceAddress == bleDevice.deviceAddress }
                if (index < 0) {
                    upsertDevice(bleDevice, isNew = true)
                }
            }
            onScanComplete { _, _ ->
                scanStopMutableStateFlow.value = true
                if (listDRData.isEmpty() && showData) {
                    Toast.makeText(application, R.string.scan_empty, Toast.LENGTH_SHORT).show()
                }
                BleLogger.d("扫描会话结束")
            }
            onScanFail {
                val msg: String = when (it) {
                    is BleScanFailType.UnSupportBle -> "设备不支持蓝牙"
                    is BleScanFailType.NoBlePermission -> "权限不足，请检查"
                    is BleScanFailType.GPSDisable -> "设备未打开GPS定位"
                    is BleScanFailType.BleDisable -> "蓝牙未打开"
                    is BleScanFailType.AlReadyScanning -> "正在扫描"
                    is BleScanFailType.ScanError -> "${it.throwable?.message}"
                }
                BleLogger.e(msg)
                Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                scanStopMutableStateFlow.value = true
            }
        }
    }

    fun clearScanResults() {
        listDRData.clear()
        deviceCountMutableStateFlow.value = 0
        scanUpdateMutableStateFlow.value = null
    }

    /**
     * 插入或更新设备，并按 RSSI 降序排序（信号越强越靠前）
     */
    private fun upsertDevice(bleDevice: BleDevice, isNew: Boolean) {
        val address = bleDevice.deviceAddress ?: return
        val oldIndex = listDRData.indexOfFirst { it.deviceAddress == address }
        if (oldIndex >= 0) {
            listDRData[oldIndex] = bleDevice
        } else {
            listDRData.add(bleDevice)
        }
        sortDevicesByRssi()
        val newIndex = listDRData.indexOfFirst { it.deviceAddress == address }
        deviceCountMutableStateFlow.value = listDRData.size
        val sortOrderChanged = oldIndex >= 0 && oldIndex != newIndex
        scanUpdateMutableStateFlow.value = ScanDeviceUpdate(
            bleDevice = bleDevice,
            isNew = isNew,
            index = newIndex,
            sortOrderChanged = sortOrderChanged
        )
    }

    private fun sortDevicesByRssi() {
        listDRData.sortWith(
            compareByDescending<BleDevice> { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.deviceAddress ?: "" }
        )
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!BleManager.get().isScanning()) {
            scanStopMutableStateFlow.value = true
            return
        }
        BleManager.get().stopScan()
        scanStopMutableStateFlow.value = true
    }

    fun isConnected(bleDevice: BleDevice?) = BleManager.get().isConnected(bleDevice)

    fun connect(address: String) {
        connect(BleManager.get().buildBleDeviceByDeviceAddress(address))
    }

    fun startScanAndConnect(activity: BaseActivity<*, *>) {
        viewModelScope.launch {
            val hasScanPermission = hasScanPermission(activity)
            if (hasScanPermission) {
                BleManager.get().startScanAndConnect(
                    false,
                    getScanCallback(false),
                    connectCallback
                )
            }
        }
    }

    fun connect(bleDevice: BleDevice?) {
        bleDevice?.let { device ->
            stopScan()
            BleManager.get().connect(device, false, connectCallback)
        }
    }

    private val connectCallback: BleConnectCallback.() -> Unit = {
        onConnectStart {
            BleLogger.e("-----onConnectStart")
        }
        onConnectFail { bleDevice, connectFailType ->
            val msg: String = when (connectFailType) {
                is BleConnectFailType.UnSupportBle -> "设备不支持蓝牙"
                is BleConnectFailType.NoBlePermission -> "权限不足，请检查"
                is BleConnectFailType.NullableBluetoothDevice -> "设备为空"
                is BleConnectFailType.BleDisable -> "蓝牙未打开"
                is BleConnectFailType.ConnectException -> "连接异常(${connectFailType.throwable.message})"
                is BleConnectFailType.ConnectTimeOut -> "连接超时"
                is BleConnectFailType.AlreadyConnecting -> "连接中"
                is BleConnectFailType.ScanNullableBluetoothDevice -> "连接失败，扫描数据为空"
            }
            BleLogger.e(msg)
            Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
            refreshMutableStateFlow.value = RefreshBleDevice(bleDevice, System.currentTimeMillis())
        }
        onDisConnecting { isActiveDisConnected, bleDevice, _, _ ->
            BleLogger.e("-----${bleDevice.deviceAddress} -> onDisConnecting: $isActiveDisConnected")
        }
        onDisConnected { isActiveDisConnected, bleDevice, _, _ ->
            Toast.makeText(
                application,
                "断开连接(${bleDevice.deviceAddress}，isActiveDisConnected: $isActiveDisConnected)",
                Toast.LENGTH_SHORT
            ).show()
            BleLogger.e("-----${bleDevice.deviceAddress} -> onDisConnected: $isActiveDisConnected")
            refreshMutableStateFlow.value = RefreshBleDevice(bleDevice, System.currentTimeMillis())
            val message = MessageEvent()
            message.data = bleDevice
            EventBus.getDefault().post(message)
        }
        onConnectSuccess { bleDevice, _ ->
            // 库内在 GATT_SUCCESS 的 onServicesDiscovered 之后才回调此处，已完成服务发现
            Toast.makeText(application, "连接成功(${bleDevice.deviceAddress})", Toast.LENGTH_SHORT).show()
            refreshMutableStateFlow.value = RefreshBleDevice(bleDevice, System.currentTimeMillis())
        }
    }

    fun disConnect(bleDevice: BleDevice?) {
        bleDevice?.let { device ->
            BleManager.get().disConnect(device)
        }
    }

    fun close() {
        BleManager.get().closeAll()
    }
}
