package de.kai_morich.simple_bluetooth_terminal

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TapParameterParser {
    var tap_tmax: Short = 0
    var tap_tmin: Byte = 0
    var tap_max: Byte = 0
    var tap_min: Byte = 0
    var tap_max_peak_tol: Byte = 0
    var tap_tavg: Byte = 0
    var tap_min_jerk_threshold: Short = 0
    var tap_smudge_rejection: Byte = 0

    constructor(data: ByteArray) {
        Log.d("MyTag", "TapParameterParser")
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        tap_tmax = buffer.short
        tap_tmin = buffer.get()
        tap_max = buffer.get()
        tap_min = buffer.get()
        tap_max_peak_tol = buffer.get()
        tap_tavg = buffer.get()
        tap_min_jerk_threshold = buffer.short
        tap_smudge_rejection = buffer.get()
    }

    constructor() {
        tap_tmax = 450
        tap_tmin = 60
        tap_max = 3
        tap_min = 1
        tap_max_peak_tol = 4
        tap_tavg = 8
        tap_min_jerk_threshold = 2048
        tap_smudge_rejection = 34
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("tap_tmax:").append(tap_tmax).append("\n")
        sb.append("tap_tmin:").append(tap_tmin).append("\n")
        sb.append("tap_max:").append(tap_max).append("\n")
        sb.append("tap_min:").append(tap_min).append("\n")
        sb.append("tap_max_peak_tol:").append(tap_max_peak_tol).append("\n")
        sb.append("tap_tavg:").append(tap_tavg).append("\n")
        sb.append("tap_max_jerk_threshold:").append(tap_min_jerk_threshold).append("\n")
        sb.append("tap_smudge_rejection:").append(tap_smudge_rejection).append("\n")
        Log.d("MyTag", "Tap Parameters:$sb")
        return sb.toString()
    }
}
