package de.kai_morich.simple_bluetooth_terminal

import java.util.Arrays

object PrivateProtocol {
    const val TIMESTAMP_LENGTH = 8
    const val MAX_COMMAND_LENGTH = 4096

    private const val CRC8_POLYNOMIAL = 0xA7
    private const val DEVICE_HEAD_1 = 0x3A
    private const val DEVICE_HEAD_2 = 0x5E
    private const val HEADPHONE_HEAD_1 = 0x3B
    private const val HEADPHONE_HEAD_2 = 0x5F

    enum class Direction(
        val head1: Int,
        val head2: Int,
        val lengthOffset: Int,
        val payloadOffset: Int,
        val minCommandLength: Int
    ) {
        DEVICE_TO_HEADPHONE(DEVICE_HEAD_1, DEVICE_HEAD_2, 5, 7, 8),
        HEADPHONE_TO_DEVICE(HEADPHONE_HEAD_1, HEADPHONE_HEAD_2, 6, 8, 9)
    }

    fun isHeaderStart(value: Byte): Boolean {
        val b = value.toInt() and 0xFF
        return b == DEVICE_HEAD_1 || b == HEADPHONE_HEAD_1
    }

    fun mayContainHeader(data: ByteArray): Boolean {
        for (i in data.indices) {
            if (directionOf(data, i, data.size - i) != null || (i == data.size - 1 && isHeaderStart(data[i]))) {
                return true
            }
        }
        return false
    }

    fun directionOf(data: ByteArray, offset: Int, available: Int): Direction? {
        if (available < 2) return null
        val head1 = data[offset].toInt() and 0xFF
        val head2 = data[offset + 1].toInt() and 0xFF
        return Direction.entries.firstOrNull { head1 == it.head1 && head2 == it.head2 }
    }

    fun readLength(data: ByteArray, direction: Direction): Int =
        littleEndianUInt16(data, direction.lengthOffset)

    fun littleEndianUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun normalizeOutgoingFrame(data: ByteArray): ByteArray {
        val direction = directionOf(data, 0, data.size)
        if (direction != Direction.DEVICE_TO_HEADPHONE || data.size < direction.minCommandLength) {
            return data
        }

        val commandLength = if (data.size >= direction.minCommandLength + TIMESTAMP_LENGTH) {
            data.size - TIMESTAMP_LENGTH
        } else {
            data.size
        }
        if (commandLength < direction.minCommandLength || commandLength > MAX_COMMAND_LENGTH) {
            return data
        }

        val normalized = ByteArray(commandLength + TIMESTAMP_LENGTH)
        System.arraycopy(data, 0, normalized, 0, minOf(data.size, normalized.size))
        normalized[direction.lengthOffset] = (commandLength and 0xFF).toByte()
        normalized[direction.lengthOffset + 1] = ((commandLength shr 8) and 0xFF).toByte()
        val crcIndex = commandLength - 1
        normalized[crcIndex] = crc8(normalized, 0, crcIndex).toByte()
        return normalized
    }

    fun parse(bytes: ByteArray): Frame {
        val direction = directionOf(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("unknown private protocol header")
        if (bytes.size < direction.lengthOffset + 2) {
            throw IllegalArgumentException("private protocol frame is too short")
        }

        val commandLength = readLength(bytes, direction)
        val expectedFrameLength = commandLength + TIMESTAMP_LENGTH
        if (commandLength < direction.minCommandLength || commandLength > MAX_COMMAND_LENGTH) {
            throw IllegalArgumentException("invalid private protocol command length: $commandLength")
        }
        if (bytes.size != expectedFrameLength) {
            throw IllegalArgumentException("private protocol length mismatch, expected $expectedFrameLength bytes but got ${bytes.size}")
        }

        val crcIndex = commandLength - 1
        val expectedCrc = crc8(bytes, 0, crcIndex)
        var alternateExpectedCrc = -1
        if (direction == Direction.HEADPHONE_TO_DEVICE) {
            val crcBytes = Arrays.copyOf(bytes, bytes.size)
            crcBytes[0] = DEVICE_HEAD_1.toByte()
            crcBytes[1] = DEVICE_HEAD_2.toByte()
            alternateExpectedCrc = crc8(crcBytes, 0, crcIndex)
        }
        val actualCrc = bytes[crcIndex].toInt() and 0xFF
        return Frame(bytes, direction, commandLength, actualCrc, expectedCrc, alternateExpectedCrc)
    }

    fun crc8(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until (offset + length)) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x01) != 0) {
                    ((crc ushr 1) xor CRC8_POLYNOMIAL) and 0xFF
                } else {
                    (crc ushr 1) and 0xFF
                }
            }
        }
        return crc and 0xFF
    }

    class Frame(
        val bytes: ByteArray,
        val direction: Direction,
        val commandLength: Int,
        val actualCrc: Int,
        val expectedCrc: Int,
        val alternateExpectedCrc: Int
    ) {
        fun isCrcValid(): Boolean = actualCrc == expectedCrc || actualCrc == alternateExpectedCrc
        fun cmd(): Int = bytes[2].toInt() and 0xFF
        fun group(): Int = bytes[3].toInt() and 0xFF
        fun subId(): Int = bytes[4].toInt() and 0xFF
        fun responseCode(): Int = if (direction != Direction.HEADPHONE_TO_DEVICE) -1 else bytes[5].toInt() and 0xFF
        fun payload(): ByteArray = Arrays.copyOfRange(bytes, direction.payloadOffset, commandLength - 1)
        fun timestamp(): ByteArray = Arrays.copyOfRange(bytes, commandLength, commandLength + TIMESTAMP_LENGTH)
    }
}
