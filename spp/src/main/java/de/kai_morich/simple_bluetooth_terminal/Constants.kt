package de.kai_morich.simple_bluetooth_terminal

object Constants {
    private const val ACTION_PREFIX = "de.kai_morich.simple_bluetooth_terminal"

    // values have to be globally unique
    const val INTENT_ACTION_DISCONNECT = "$ACTION_PREFIX.Disconnect"
    const val NOTIFICATION_CHANNEL = "$ACTION_PREFIX.Channel"
    const val INTENT_CLASS_MAIN_ACTIVITY = "$ACTION_PREFIX.MainActivity"

    // values have to be unique within each app
    const val NOTIFY_MANAGER_START_FOREGROUND_SERVICE = 1001
}
