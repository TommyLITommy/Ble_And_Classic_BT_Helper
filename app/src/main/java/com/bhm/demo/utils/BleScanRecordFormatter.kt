package com.bhm.demo.utils

import com.bhm.ble.utils.BleUtil
import java.nio.charset.StandardCharsets

/**
 * BLE 广播数据（Scan Record）格式化与解析
 */
object BleScanRecordFormatter {

    data class AdStructure(
        val type: Int,
        val typeName: String,
        val dataHex: String,
        val description: String
    )

    fun formatHex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        return BleUtil.bytesToHex(bytes, true).trim().uppercase()
    }

    fun parseAdStructures(bytes: ByteArray?): List<AdStructure> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        val result = mutableListOf<AdStructure>()
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xFF
            if (length == 0) break
            if (index + length >= bytes.size) break
            val type = bytes[index + 1].toInt() and 0xFF
            val dataStart = index + 2
            val dataEnd = index + 1 + length
            val data = bytes.copyOfRange(dataStart, dataEnd)
            result.add(
                AdStructure(
                    type = type,
                    typeName = adTypeName(type),
                    dataHex = BleUtil.bytesToHex(data, true).trim().uppercase(),
                    description = describeAdData(type, data)
                )
            )
            index += length + 1
        }
        return result
    }

    fun formatParsedSummary(bytes: ByteArray?): String {
        val structures = parseAdStructures(bytes)
        if (structures.isEmpty()) return "无广播数据"
        return structures.joinToString(separator = "\n") { ad ->
            buildString {
                append("0x")
                append(ad.type.toString(16).uppercase().padStart(2, '0'))
                append(" ")
                append(ad.typeName)
                if (ad.description.isNotEmpty()) {
                    append(": ")
                    append(ad.description)
                }
            }
        }
    }

    private fun adTypeName(type: Int): String = when (type) {
        0x01 -> "Flags"
        0x02 -> "Incomplete 16-bit UUIDs"
        0x03 -> "Complete 16-bit UUIDs"
        0x04 -> "Incomplete 32-bit UUIDs"
        0x05 -> "Complete 32-bit UUIDs"
        0x06 -> "Incomplete 128-bit UUIDs"
        0x07 -> "Complete 128-bit UUIDs"
        0x08 -> "Shortened Local Name"
        0x09 -> "Complete Local Name"
        0x0A -> "Tx Power Level"
        0x16 -> "Service Data - 16-bit UUID"
        0x20 -> "Service Data - 32-bit UUID"
        0x21 -> "Service Data - 128-bit UUID"
        0xFF -> "Manufacturer Specific Data"
        else -> "Unknown"
    }

    private fun describeAdData(type: Int, data: ByteArray): String = when (type) {
        0x01 -> "0x${data.firstOrNull()?.toInt()?.and(0xFF)?.toString(16)?.uppercase() ?: ""}"
        0x08, 0x09 -> decodeUtf8(data)
        0x0A -> {
            val dbm = data.firstOrNull()?.toInt() ?: 0
            "${if (dbm > 127) dbm - 256 else dbm} dBm"
        }
        0xFF -> {
            if (data.size >= 2) {
                val companyId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                "Company ID: 0x${companyId.toString(16).uppercase().padStart(4, '0')}"
            } else {
                ""
            }
        }
        else -> if (data.size <= 24) formatHex(data) else "${formatHex(data.take(24).toByteArray())}..."
    }

    private fun decodeUtf8(data: ByteArray): String =
        runCatching { String(data, StandardCharsets.UTF_8) }.getOrDefault("")
}
