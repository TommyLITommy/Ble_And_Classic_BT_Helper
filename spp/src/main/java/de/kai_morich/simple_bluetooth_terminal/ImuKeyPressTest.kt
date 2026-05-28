package de.kai_morich.simple_bluetooth_terminal

object ImuKeyPressTest {
    fun showImuKeyPress(data: Byte): String {
        val msg = when (data.toInt()) {
            0x01 -> "单击"
            0x02 -> "双击"
            0x03 -> "三击"
            else -> "未知按键"
        }
        return "$msg\n"
    }

    @Deprecated("Use showImuKeyPress")
    fun ShowImuKeyPress(data: Byte): String = showImuKeyPress(data)
}
