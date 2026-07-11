package de.kai_morich.simple_bluetooth_terminal

import android.content.Context
import java.util.UUID

object SppPreferences {

    private const val PREFS = "spp_prefs"
    private const val KEY_SERVICE_UUID = "service_uuid"
    private const val KEY_FILENAME_HISTORY = "filename_history"
    private const val KEY_LAST_FILENAME = "last_filename"
    private const val MAX_FILENAME_HISTORY = 10
    const val DEFAULT_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    fun getServiceUuid(context: Context): UUID =
        UUID.fromString(getServiceUuidString(context))

    fun getServiceUuidString(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVICE_UUID, DEFAULT_SPP_UUID) ?: DEFAULT_SPP_UUID

    fun setServiceUuid(context: Context, uuid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE_UUID, uuid)
            .apply()
    }

    fun getLastFilename(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_FILENAME, "") ?: ""

    fun getFilenameHistory(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FILENAME_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split('\u0001').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun rememberFilename(context: Context, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = getFilenameHistory(context).toMutableList()
        history.removeAll { it.equals(name, ignoreCase = true) }
        history.add(0, name)
        while (history.size > MAX_FILENAME_HISTORY) history.removeAt(history.lastIndex)
        prefs.edit()
            .putString(KEY_LAST_FILENAME, name)
            .putString(KEY_FILENAME_HISTORY, history.joinToString("\u0001"))
            .apply()
    }
}
