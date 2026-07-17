package de.kai_morich.simple_bluetooth_terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class SleepUploadDataParser(data: ByteArray) {

    companion object {
        /** packed sleep_upload_data_t: 66 + sleep_record_len(1) + sleep_record(20) */
        const val PAYLOAD_SIZE = 87
        private const val SLEEP_RECORD_BYTES = 20
        private const val SLEEP_RECORD_ENTRY_SIZE = 4
        private const val SLEEP_RECORD_MAX_COUNT = SLEEP_RECORD_BYTES / SLEEP_RECORD_ENTRY_SIZE
    }

    /** Valid sleep_record_len values from algorithm: 0,4,8,12,16,20 (bytes). */
    private fun validEntryCount(sleepRecordLen: Int): Int {
        if (sleepRecordLen !in 0..SLEEP_RECORD_BYTES) return 0
        if (sleepRecordLen % SLEEP_RECORD_ENTRY_SIZE != 0) return 0
        return sleepRecordLen / SLEEP_RECORD_ENTRY_SIZE
    }

    private data class SleepRecordEntry(
        val stage: Int,
        val posture: Int,
        val headerValid: Boolean,
        val footerValid: Boolean,
    )

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
        val sleepRecordLen: Int,
        val sleepRecords: List<SleepRecordEntry>,
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
        val sleepRecordLen = buffer.get().toInt() and 0xFF
        val sleepRecords = parseSleepRecords(buffer, validEntryCount(sleepRecordLen))
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
            sleepRecordLen,
            sleepRecords,
        )
    }

    /** Each entry: 'D' + stage + posture + '\\n'. Always consume full 20-byte array. */
    private fun parseSleepRecords(buffer: ByteBuffer, validCount: Int): List<SleepRecordEntry> {
        val all = List(SLEEP_RECORD_MAX_COUNT) {
            val header = buffer.get().toInt() and 0xFF
            val stage = buffer.get().toInt() and 0xFF
            val posture = buffer.get().toInt() and 0xFF
            val footer = buffer.get().toInt() and 0xFF
            SleepRecordEntry(
                stage = stage,
                posture = posture,
                headerValid = header == 'D'.code,
                footerValid = footer == '\n'.code,
            )
        }
        return all.take(validCount)
    }

    fun printData() {
        println(toStringRepresentation())
    }

    /** Non-null only when sleep_record_len maps to at least one valid group. */
    fun sleepRecordsContentOrNull(): String? {
        val data = payload ?: return null
        if (data.sleepRecords.isEmpty()) return null
        return buildString {
            append("len=").append(data.sleepRecordLen).append('\n')
            data.sleepRecords.forEachIndexed { index, entry ->
                append('[').append(index).append("] ")
                append(formatSleepRecordCompact(entry)).append('\n')
            }
        }.trimEnd()
    }

    fun toStringRepresentation(): String {
        val data = payload ?: return "sleep_upload_data_t size mismatch (expect $PAYLOAD_SIZE)"
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
            appendField("sleep_record_len", data.sleepRecordLen)
            if (data.sleepRecords.isNotEmpty()) {
                append("sleep_record:\n")
                data.sleepRecords.forEachIndexed { index, entry ->
                    append("  [$index] ").append(formatSleepRecord(entry)).append('\n')
                }
            }
        }
    }

    private fun formatSleepRecordCompact(entry: SleepRecordEntry): String {
        val marker = if (entry.headerValid && entry.footerValid) "" else "(无效)"
        return "stage=${formatSleepStageZh(entry.stage)},pos=${formatSleepPostureZh(entry.posture)}$marker"
    }

    private fun formatSleepRecord(entry: SleepRecordEntry): String {
        val marker = if (entry.headerValid && entry.footerValid) "" else " (invalid frame)"
        return "stage=${formatSleepStage(entry.stage)}, posture=${formatSleepPosture(entry.posture)}$marker"
    }

    private fun formatSleepStageZh(stage: Int): String = when (stage) {
        0 -> "清醒"
        1 -> "核心睡眠"
        2 -> "深层睡眠"
        3 -> "快速眼动期"
        else -> "未知($stage)"
    }

    private fun formatSleepPostureZh(posture: Int): String = when (posture) {
        1 -> "面上"
        2 -> "侧左"
        3 -> "侧右"
        4 -> "伏"
        else -> "未知($posture)"
    }

    private fun formatSleepStage(stage: Int): String = when (stage) {
        0 -> "0(Awake/清醒)"
        1 -> "1(Core/核心睡眠)"
        2 -> "2(Deep/深层睡眠)"
        3 -> "3(REM/快速眼动期)"
        else -> "$stage(Unknown)"
    }

    private fun formatSleepPosture(posture: Int): String = when (posture) {
        1 -> "1(Supine/面上)"
        2 -> "2(Left/侧左)"
        3 -> "3(Right/侧右)"
        4 -> "4(Prone/伏)"
        else -> "$posture(Unknown)"
    }

    private fun formatEarBudState(state: Int): String =
        if (state == 2) "In Ear" else "Out Ear"

    private fun StringBuilder.appendField(name: String, value: Any) {
        append(String.format(Locale.US, "%-18s: %s\n", name, value))
    }
}
