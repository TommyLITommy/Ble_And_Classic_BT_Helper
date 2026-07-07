package de.kai_morich.simple_bluetooth_terminal

import android.content.Context
import java.util.UUID

object SppPreferences {

    private const val PREFS = "spp_prefs"
    private const val KEY_SERVICE_UUID = "service_uuid"
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
}
