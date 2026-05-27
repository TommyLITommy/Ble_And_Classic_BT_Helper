/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.entity

import org.json.JSONObject
import java.util.UUID

/**
 * BLE 写特征预设命令
 */
data class PresetWriteCommand(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val payload: String,
    val hexMode: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_PAYLOAD, payload)
        put(KEY_HEX_MODE, hexMode)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_HEX_MODE = "hexMode"

        fun fromJson(json: JSONObject): PresetWriteCommand = PresetWriteCommand(
            id = json.optString(KEY_ID, UUID.randomUUID().toString()),
            name = json.optString(KEY_NAME, ""),
            payload = json.optString(KEY_PAYLOAD, ""),
            hexMode = json.optBoolean(KEY_HEX_MODE, true),
        )
    }
}
