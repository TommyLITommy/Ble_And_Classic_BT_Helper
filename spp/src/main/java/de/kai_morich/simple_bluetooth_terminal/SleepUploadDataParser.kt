package de.kai_morich.simple_bluetooth_terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class SleepUploadDataParser(data: ByteArray) {

    companion object {
        const val PAYLOAD_SIZE = 66
    }

    private data class SleepUploadData(
        val sequence: Int,
        val valid: Int,
        val timestamp: Int,
        val earBudState: Int,
        val heartRate: Int,
        val stepRate: Int,
        val spo2: Int,
        val brightLevel: IntArray,
        val totalRrCnt: Long,
        val windowRrCnt: Int,
        val validRrCnt: Int,
        val resultTimestamp: Int,
        val resultConfLevel: Int,
        val stressIndex: Int,
        val pnn50: Int,
        val sdnn180: Float,
        val hrvScore: Float,
    )

    private val payload: SleepUploadData?

    init {
        payload = if (data.size == PAYLOAD_SIZE) parse(data) else null
    }

    private fun parse(data: ByteArray): SleepUploadData {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.short.toInt() and 0xFFFF
        val valid = buffer.int
        val timestamp = buffer.int
        val earBudState = buffer.int
        val heartRate = buffer.int
        val stepRate = buffer.int
        val spo2 = buffer.int
        val brightLevel = IntArray(8) { buffer.short.toInt() and 0xFFFF }
        val totalRrCnt = buffer.int.toLong() and 0xFFFFFFFFL
        val windowRrCnt = buffer.short.toInt() and 0xFFFF
        val validRrCnt = buffer.short.toInt() and 0xFFFF
        val resultTimestamp = buffer.int
        val resultConfLevel = buffer.get().toInt()
        val stressIndex = buffer.short.toInt()
        val pnn50 = buffer.get().toInt()
        val sdnn180 = buffer.float
        val hrvScore = buffer.float
        return SleepUploadData(
            sequence,
            valid,
            timestamp,
            earBudState,
            heartRate,
            stepRate,
            spo2,
            brightLevel,
            totalRrCnt,
            windowRrCnt,
            validRrCnt,
            resultTimestamp,
            resultConfLevel,
            stressIndex,
            pnn50,
            sdnn180,
            hrvScore,
        )
    }

    fun printData() {
        println(toStringRepresentation())
    }

    fun toStringRepresentation(): String {
        val data = payload ?: return "sleep_upload_data_t size mismatch"
        return buildString {
            append("=== MM Payload (sleep_upload_data_t) ===\n")
            appendField("sequence", data.sequence)
            appendField("valid", data.valid)
            appendField("timestamp", data.timestamp)
            appendField("ear_bud_state", formatEarBudState(data.earBudState))
            appendField("heart_rate", data.heartRate)
            appendField("step_rate", data.stepRate)
            appendField("SpO2", data.spo2)
            appendField("bright_level", data.brightLevel.joinToString(";"))
            appendField("total_rr_cnt", data.totalRrCnt)
            appendField("window_rr_cnt", data.windowRrCnt)
            appendField("valid_rr_cnt", data.validRrCnt)
            appendField("result_timestamp", data.resultTimestamp)
            appendField("result_conf_level", data.resultConfLevel)
            appendField("stress_index", data.stressIndex)
            appendField("pNN50", data.pnn50)
            appendField("SDNN180", String.format(Locale.US, "%.6f", data.sdnn180))
            appendField("HRV_Score", String.format(Locale.US, "%.6f", data.hrvScore))
        }
    }

    private fun formatEarBudState(state: Int): String =
        if (state == 2) "In Ear" else "Out Ear"

    private fun StringBuilder.appendField(name: String, value: Any) {
        append(String.format(Locale.US, "%-18s: %s\n", name, value))
    }
}
