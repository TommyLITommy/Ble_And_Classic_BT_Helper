package com.bhm.demo.utils

import com.bhm.ble.utils.BleUtil
import de.kai_morich.simple_bluetooth_terminal.PrivateProtocol
import de.kai_morich.simple_bluetooth_terminal.PrivateProtocolStreamDecoder
import de.kai_morich.simple_bluetooth_terminal.SleepUploadDataParser
import de.kai_morich.simple_bluetooth_terminal.TextUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BleReceiveFormatter {

    private val decoder = PrivateProtocolStreamDecoder()

    fun reset() {
        decoder.reset()
    }

    fun format(data: ByteArray, sourceUuid: String): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        sb.append("---RX---").append(timestamp).append("---\n")
        sb.append('[').append(shortUuid(sourceUuid)).append("] ")

        val waiting = decoder.hasPendingData()
        val frames = decoder.append(data)
        if (frames.isEmpty() && !waiting && !PrivateProtocol.mayContainHeader(data)) {
            sb.append(BleUtil.bytesToHex(data)).append('\n')
            return sb.toString()
        }
        frames.forEach { frame ->
            appendFrame(sb, frame)
        }
        return sb.toString()
    }

    private fun shortUuid(uuid: String): String {
        val normalized = uuid.lowercase(Locale.US)
        return if (normalized.length >= 8) normalized.substring(4, 8) else uuid
    }

    private fun appendFrame(sb: StringBuilder, frame: PrivateProtocol.Frame) {
        sb.append(TextUtil.toHexString(frame.bytes)).append('\n')
        val payload = frame.payload()
        if (frame.group() == 0xFF && frame.subId() == 0x08 &&
            payload.size == SleepUploadDataParser.PAYLOAD_SIZE
        ) {
            sb.append(SleepUploadDataParser(payload).toStringRepresentation()).append('\n')
        }
        sb.append('\n')
    }
}
