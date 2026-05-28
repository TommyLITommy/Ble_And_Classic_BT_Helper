package de.kai_morich.simple_bluetooth_terminal

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IMUUploadDataParser(data: ByteArray) {

    private data class OneSideIMUData(
        val leftOrRight: Byte,
        val temperature: Short,
        val accel: ShortArray,
        val gyro: ShortArray,
        val euler: ShortArray
    ) {
        fun printOneSideIMUData() {
            println("\nleft_or_right: " + if ((leftOrRight.toInt() and 0xFF) == 0) "left" else "right")
            println("Temprarture:$temperature")
            println("Temperature: ${(temperature.toInt() and 0xFFFF) / 128 + 25}")
            println("Accel: [${accel[0]}, ${accel[1]}, ${accel[2]}]")
            println("Gyro: [${gyro[0]}, ${gyro[1]}, ${gyro[2]}]")
            println("Euler: [${euler[0]}, ${euler[1]}, ${euler[2]}]")
        }

        fun toStringRepresentation(): String {
            val sb = StringBuilder()
            sb.append("\nleft_or_right: ").append(if ((leftOrRight.toInt() and 0xFF) == 0) "left" else "right").append("\n")
            sb.append("Temperature: ").append((temperature.toInt() and 0xFFFF) / 128 + 25).append("\n")
            sb.append("Accel: [").append(accel[0]).append(", ").append(accel[1]).append(", ").append(accel[2]).append("]\n")
            sb.append("Gyro: [").append(gyro[0]).append(", ").append(gyro[1]).append(", ").append(gyro[2]).append("]\n")
            sb.append("Euler: [").append(euler[0]).append(", ").append(euler[1]).append(", ").append(euler[2]).append("]\n")
            return sb.toString()
        }
    }

    private val oneSideIMUData01: OneSideIMUData?
    private val oneSideIMUData02: OneSideIMUData?

    init {
        Log.d("MyTag", "IMU, data.length:${data.size}")
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        oneSideIMUData01 = parseOneSide(buffer)
        oneSideIMUData02 = parseOneSide(buffer)
    }

    private fun parseOneSide(buffer: ByteBuffer): OneSideIMUData? {
        Log.d("MyTag", "IMU, buffer.remaining()::${buffer.remaining()}")
        if (buffer.remaining() < 15) return null

        val leftOrRight = buffer.get()
        val temperature = buffer.short
        val accel = ShortArray(3)
        val gyro = ShortArray(3)
        val euler = ShortArray(3)
        for (i in 0 until 3) accel[i] = buffer.short
        for (i in 0 until 3) gyro[i] = buffer.short
        for (i in 0 until 3) euler[i] = buffer.short

        return OneSideIMUData(leftOrRight, temperature, accel, gyro, euler)
    }

    fun printIMUData() {
        oneSideIMUData01?.printOneSideIMUData()
        oneSideIMUData02?.printOneSideIMUData()
    }

    fun toStringRepresentation(): String {
        val sb = StringBuilder()
        oneSideIMUData01?.let { sb.append(it.toStringRepresentation()) }
        oneSideIMUData02?.let { sb.append(it.toStringRepresentation()) }
        return sb.toString()
    }
}
