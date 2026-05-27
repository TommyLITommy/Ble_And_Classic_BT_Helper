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
        val json = SPUtil.getInstance(context).getString(SP_KEY) ?: return mutableListOf()
        if (json.isBlank()) {
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

        // 如果用户已有其他预设，但还没有睡眠模式的两条默认命令，则在 UI 层补齐。
        // 不强制落盘，避免覆盖用户手动删除的行为。
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
        // 默认睡眠模式预设（HEX 模式，payload 直接使用用户提供的字符串）
        return mutableListOf(
            PresetWriteCommand(
                id = "default_sleep_open",
                name = "打开睡眠模式",
                payload = "3a5e10a29009000141b710519f9d010000",
                hexMode = true
            ),
            PresetWriteCommand(
                id = "default_sleep_close",
                name = "关闭睡眠模式",
                payload = "3a5e10a2900900002Cb710519f9d010000",
                hexMode = true
            )
        )
    }
}
