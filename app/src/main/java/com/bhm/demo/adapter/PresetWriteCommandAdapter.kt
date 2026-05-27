/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.adapter

import com.bhm.demo.R
import com.bhm.demo.entity.PresetWriteCommand
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder

class PresetWriteCommandAdapter(
    data: MutableList<PresetWriteCommand> = mutableListOf(),
    private val onEdit: (PresetWriteCommand) -> Unit,
    private val onDelete: (PresetWriteCommand) -> Unit,
) : BaseQuickAdapter<PresetWriteCommand, BaseViewHolder>(
    R.layout.item_preset_write_command,
    data
) {
    override fun convert(holder: BaseViewHolder, item: PresetWriteCommand) {
        holder.setText(R.id.tvPresetName, item.name)
        val formatLabel = if (item.hexMode) "Hex" else "UTF-8"
        holder.setText(R.id.tvPresetPayload, "$formatLabel: ${item.payload}")
        holder.getView<android.view.View>(R.id.btnEdit).setOnClickListener { onEdit(item) }
        holder.getView<android.view.View>(R.id.btnDelete).setOnClickListener { onDelete(item) }
    }
}
