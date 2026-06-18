/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.utils

import android.content.Context
import com.bhm.demo.entity.PresetWriteCommand
import com.bhm.support.sdk.utils.SPUtil
import org.json.JSONArray

/**
 * 预设写命令本地存储
 */
object PresetWriteCommandStore {

    private const val SP_KEY = "preset_write_commands"

    fun loadAll(context: Context): MutableList<PresetWriteCommand> {
        val defaults = buildDefaultCommands()
        val json = SPUtil.getInstance(context).getString(SP_KEY)
        if (json.isNullOrBlank()) {
            saveAll(context, defaults)
            return defaults
        }
        val loaded = try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    add(PresetWriteCommand.fromJson(array.getJSONObject(i)))
                }
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }

        if (loaded.isEmpty()) {
            saveAll(context, defaults)
            return defaults
        }

        // 内置预设命令随版本更新；已保存的同 id 项同步为最新 payload。
        val defaultsById = defaults.associateBy { it.id }
        for (i in loaded.indices) {
            defaultsById[loaded[i].id]?.let { def ->
                loaded[i] = loaded[i].copy(name = def.name, payload = def.payload, hexMode = def.hexMode)
            }
        }
        val existingIds = loaded.map { it.id }.toHashSet()
        defaults.forEach { def ->
            if (!existingIds.contains(def.id)) {
                loaded.add(def)
            }
        }
        return loaded
    }

    fun saveAll(context: Context, commands: List<PresetWriteCommand>) {
        val array = JSONArray()
        commands.forEach { array.put(it.toJson()) }
        SPUtil.getInstance(context).putString(SP_KEY, array.toString())
    }

    private fun buildDefaultCommands(): MutableList<PresetWriteCommand> {
        return mutableListOf(
            PresetWriteCommand(
                id = "default_sleep_open",
                name = "打开sleep上报",
                payload = "3A 5E 10 FF 08 09 00 01 41 00 00 00 00 00 00 00 00",
                hexMode = true
            ),
            PresetWriteCommand(
                id = "default_sleep_close",
                name = "关闭sleep上报",
                payload = "3A 5E 10 FF 08 09 00 00 41 00 00 00 00 00 00 00 00",
                hexMode = true
            )
        )
    }
}
