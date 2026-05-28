package de.kai_morich.simple_bluetooth_terminal

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchUploadDataParser(data: ByteArray) {

    private data class OneSideTouchData(
        val leftOrRight: Byte,
        val macAddr: ByteArray,
        val chipId: Byte,
        val isOffsetManual: Byte,
        val offset: ShortArray,
        val nvBaseData: ShortArray,
        val proxStatus: Byte,
        val wearStatus: Byte,
        val touchStatus: Byte,
        val diff: IntArray,
        val lp: ShortArray,
        val bl: ShortArray
    ) {
        fun print(prefix: String) {
            println(prefix + "left_or_right: " + if ((leftOrRight.toInt() and 0xFF) == 0) "left" else "right")
            println(prefix + "mac_addr:" + byteArrayToMacString(macAddr))
            println(prefix + "chip_id: " + (chipId.toInt() and 0xFF))
            println(prefix + "is_offset_manual: " + (isOffsetManual.toInt() and 0xFF))
            println(prefix + "offset: " + shortArrayToString(offset))
            println(prefix + "nv_base_data: " + shortArrayToString(nvBaseData))
            println(prefix + "prox_status: " + (proxStatus.toInt() and 0xFF))
            println(prefix + "wear_status: " + (wearStatus.toInt() and 0xFF))
            println(prefix + "touch_status: " + (touchStatus.toInt() and 0xFF))
            println(prefix + "diff: " + intArrayToString(diff))
            println(prefix + "lp: " + shortArrayToString(lp))
            println(prefix + "bl: " + shortArrayToString(bl))
        }

        fun toStringRepresentation(prefix: String): String {
            val side = if ((leftOrRight.toInt() and 0xFF) == 0) "l->" else "r->"
            return buildString {
                append(prefix).append("left_or_right: ").append(if ((leftOrRight.toInt() and 0xFF) == 0) "left" else "right").append("\n")
                append(prefix).append(side).append("mac_addr:").append(byteArrayToMacString(macAddr)).append("\n")
                append(prefix).append(side).append("chip_id: 0x").append(String.format("%02X", (chipId.toInt() and 0xFF))).append("\n")
                append(prefix).append(side).append("is_offset_manual:").append(isOffsetManual.toInt() and 0xFF).append("\n")
                append(prefix).append(side).append("offset:").append(shortArrayToString(offset)).append("\n")
                append(prefix).append(side).append("nv_base_data:").append(shortArrayToString(nvBaseData)).append("\n")
                append(prefix).append(side).append("wear_status: ").append(wearStatus.toInt() and 0xFF).append("\n")
                append(prefix).append(side).append("prox_status: ").append(proxStatus.toInt() and 0xFF).append("\n")
                append(prefix).append(side).append("touch_status: ").append(touchStatus.toInt() and 0xFF).append("\n")
                append(prefix).append(side).append("diff: ").append(intArrayToString(diff)).append("\n")
                append(prefix).append(side).append("lp: ").append(shortArrayToString(lp)).append("\n")
                append(prefix).append(side).append("bl: ").append(shortArrayToString(bl)).append("\n")
            }
        }

        private fun byteArrayToMacString(mac: ByteArray): String {
            return mac.joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
        }

        private fun intArrayToString(arr: IntArray): String = arr.joinToString(prefix = "[", postfix = "]", separator = ", ")
        private fun shortArrayToString(arr: ShortArray): String = arr.joinToString(prefix = "[", postfix = "]", separator = ", ")
    }

    private val touch1: OneSideTouchData?
    private val touch2: OneSideTouchData?

    init {
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        touch1 = parseOneSide(buffer)
        touch2 = parseOneSide(buffer)
    }

    private fun parseOneSide(buffer: ByteBuffer): OneSideTouchData? {
        Log.d("MyTag", "Touch, buffer.remaining():${buffer.remaining()}")
        if (buffer.remaining() < 48) return null

        val leftOrRight = buffer.get()
        val macAddr = ByteArray(6) { buffer.get() }
        val chipId = buffer.get()
        val isOffsetManual = buffer.get()
        val offset = ShortArray(2) { buffer.short }
        val nvBaseData = ShortArray(2) { buffer.short }
        val proxStatus = buffer.get()
        val wearStatus = buffer.get()
        val touchStatus = buffer.get()
        val diff = IntArray(3) { buffer.int }
        val lp = ShortArray(5) { buffer.short }
        val bl = ShortArray(3) { buffer.short }

        return OneSideTouchData(
            leftOrRight, macAddr, chipId, isOffsetManual, offset, nvBaseData,
            proxStatus, wearStatus, touchStatus, diff, lp, bl
        )
    }

    fun printData() {
        touch1?.print("  ")
        touch2?.print("  ")
    }

    fun toStringRepresentation(): String {
        val sb = StringBuilder()
        touch1?.let { sb.append(it.toStringRepresentation("  ")) }
        touch2?.let { sb.append(it.toStringRepresentation("  ")) }
        return sb.toString()
    }
}
