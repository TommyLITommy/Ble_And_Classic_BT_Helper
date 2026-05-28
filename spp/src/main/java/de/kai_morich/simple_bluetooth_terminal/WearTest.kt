package de.kai_morich.simple_bluetooth_terminal

object WearTest {
    fun showWearStatus(data: Byte): String {
        val msg = when (data.toInt()) {
            0x00 -> "双耳未佩戴"
            0x01 -> "左耳佩戴"
            0x10 -> "右耳佩戴"
            0x11 -> "双耳佩戴"
            else -> "未知佩戴状态"
        }
        return "$msg\n"
    }

    @Deprecated("Use showWearStatus")
    fun ShowWearStatus(data: Byte): String = showWearStatus(data)
}
