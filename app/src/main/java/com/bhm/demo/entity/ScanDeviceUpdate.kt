package com.bhm.demo.entity

import com.bhm.ble.device.BleDevice

/**
 * 扫描列表更新事件
 */
data class ScanDeviceUpdate(
    val bleDevice: BleDevice,
    val isNew: Boolean,
    val index: Int,
    /** RSSI 排序导致条目位置变化，需整表刷新 */
    val sortOrderChanged: Boolean = false
)
