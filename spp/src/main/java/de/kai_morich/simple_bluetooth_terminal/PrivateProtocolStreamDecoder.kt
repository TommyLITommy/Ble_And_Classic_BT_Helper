package de.kai_morich.simple_bluetooth_terminal

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Arrays

class PrivateProtocolStreamDecoder {
    private val buffer = ByteArrayOutputStream()

    fun append(data: ByteArray): List<PrivateProtocol.Frame> {
        buffer.write(data, 0, data.size)
        return drainFrames()
    }

    fun reset() {
        buffer.reset()
    }

    fun hasPendingData(): Boolean = buffer.size() > 0

    private fun drainFrames(): List<PrivateProtocol.Frame> {
        val frames = ArrayList<PrivateProtocol.Frame>()
        val bytes = buffer.toByteArray()
        var offset = 0

        while (offset < bytes.size) {
            val headerOffset = findHeader(bytes, offset)
            if (headerOffset < 0) {
                offset = keepPossibleHeaderPrefix(bytes)
                break
            }
            if (headerOffset > offset) {
                Log.w(TAG, "drop ${headerOffset - offset} bytes before private protocol header")
                offset = headerOffset
            }

            val direction = PrivateProtocol.directionOf(bytes, offset, bytes.size - offset)
            if (direction == null) {
                if (bytes.size - offset < 2) break
                offset++
                continue
            }

            if (bytes.size - offset < direction.lengthOffset + 2) break

            val commandLength = PrivateProtocol.littleEndianUInt16(bytes, offset + direction.lengthOffset)
            if (commandLength < direction.minCommandLength || commandLength > PrivateProtocol.MAX_COMMAND_LENGTH) {
                Log.w(TAG, "drop invalid frame header, commandLength=$commandLength")
                offset++
                continue
            }

            val frameLength = commandLength + PrivateProtocol.TIMESTAMP_LENGTH
            if (bytes.size - offset < frameLength) break

            val frameBytes = Arrays.copyOfRange(bytes, offset, offset + frameLength)
            try {
                val frame = PrivateProtocol.parse(frameBytes)
                if (frame.isCrcValid()) {
                    frames.add(frame)
                } else {
                    Log.w(
                        TAG,
                        String.format("drop frame with bad crc, actual=0x%02X expected=0x%02X", frame.actualCrc, frame.expectedCrc)
                    )
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "drop invalid private protocol frame: ${e.message}")
            }
            offset += frameLength
        }

        keepRemainder(bytes, offset)
        return frames
    }

    private fun findHeader(bytes: ByteArray, offset: Int): Int {
        for (i in offset until bytes.size) {
            if (PrivateProtocol.directionOf(bytes, i, bytes.size - i) != null) return i
            if (i == bytes.size - 1 && PrivateProtocol.isHeaderStart(bytes[i])) return i
        }
        return -1
    }

    private fun keepPossibleHeaderPrefix(bytes: ByteArray): Int {
        return if (bytes.isNotEmpty() && PrivateProtocol.isHeaderStart(bytes[bytes.size - 1])) {
            bytes.size - 1
        } else {
            bytes.size
        }
    }

    private fun keepRemainder(bytes: ByteArray, offset: Int) {
        buffer.reset()
        if (offset < bytes.size) {
            var start = offset
            if (bytes.size - start > MAX_BUFFER_SIZE) {
                start = bytes.size - MAX_BUFFER_SIZE
            }
            buffer.write(bytes, start, bytes.size - start)
        }
    }

    companion object {
        private const val TAG = "PrivateProtocolDecoder"
        private const val MAX_BUFFER_SIZE = PrivateProtocol.MAX_COMMAND_LENGTH + PrivateProtocol.TIMESTAMP_LENGTH
    }
}
