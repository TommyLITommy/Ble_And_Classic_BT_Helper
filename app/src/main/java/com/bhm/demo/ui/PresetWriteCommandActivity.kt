/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.ui

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bhm.ble.utils.BleUtil
import com.bhm.demo.BaseActivity
import com.bhm.demo.R
import com.bhm.demo.adapter.PresetWriteCommandAdapter
import com.bhm.demo.databinding.ActivityPresetWriteCommandBinding
import com.bhm.demo.entity.PresetWriteCommand
import com.bhm.demo.utils.PresetWriteCommandStore
import com.bhm.support.sdk.common.BaseViewModel
import com.bhm.support.sdk.utils.ViewUtil

/**
 * 预设写命令管理
 */
class PresetWriteCommandActivity : BaseActivity<BaseViewModel, ActivityPresetWriteCommandBinding>() {

    override fun createViewModel() = BaseViewModel(application)

    private val commands = mutableListOf<PresetWriteCommand>()

    private var listAdapter: PresetWriteCommandAdapter? = null

    override fun initData() {
        super.initData()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
        viewBinding.toolbar.setNavigationOnClickListener { finish() }
        commands.addAll(PresetWriteCommandStore.loadAll(applicationContext))
        initList()
        refreshEmptyState()
    }

    private fun initList() {
        val layoutManager = LinearLayoutManager(applicationContext)
        viewBinding.recyclerView.layoutManager = layoutManager
        viewBinding.recyclerView.addItemDecoration(
            DividerItemDecoration(applicationContext, DividerItemDecoration.VERTICAL)
        )
        listAdapter = PresetWriteCommandAdapter(
            commands,
            onEdit = { showEditDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        viewBinding.recyclerView.adapter = listAdapter
    }

    override fun initEvent() {
        super.initEvent()
        viewBinding.btnAdd.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            showEditDialog(null)
        }
    }

    private fun showEditDialog(existing: PresetWriteCommand?) {
        val hexButtonId = View.generateViewId()
        val utf8ButtonId = View.generateViewId()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.preset_name_hint)
            setText(existing?.name.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val payloadInput = EditText(this).apply {
            hint = getString(R.string.hint_write_hex)
            setText(existing?.payload.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        val hexMode = existing?.hexMode ?: true
        val formatGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(context).apply {
                id = hexButtonId
                text = getString(R.string.write_format_hex)
                isChecked = hexMode
            })
            addView(RadioButton(context).apply {
                id = utf8ButtonId
                text = getString(R.string.write_format_utf8)
                isChecked = !hexMode
            })
            setOnCheckedChangeListener { _, checkedId ->
                payloadInput.hint = if (checkedId == hexButtonId) {
                    getString(R.string.hint_write_hex)
                } else {
                    getString(R.string.hint_write_utf8)
                }
            }
        }
        payloadInput.hint = if (hexMode) {
            getString(R.string.hint_write_hex)
        } else {
            getString(R.string.hint_write_utf8)
        }
        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, dp(8), padding, 0)
            addView(nameInput)
            addView(formatGroup, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            addView(payloadInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        val editDialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.preset_add else R.string.preset_edit)
            .setView(contentView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        editDialog.setOnShowListener {
            editDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val payload = payloadInput.text.toString().trim()
                if (name.isEmpty() || payload.isEmpty()) {
                    Toast.makeText(applicationContext, R.string.preset_name_payload_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val isHex = formatGroup.checkedRadioButtonId == hexButtonId
                if (isHex && BleUtil.hexStringToByteArray(payload) == null) {
                    Toast.makeText(applicationContext, R.string.write_hex_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (existing == null) {
                    commands.add(PresetWriteCommand(name = name, payload = payload, hexMode = isHex))
                    listAdapter?.notifyItemInserted(commands.lastIndex)
                } else {
                    val index = commands.indexOfFirst { it.id == existing.id }
                    if (index >= 0) {
                        commands[index] = existing.copy(name = name, payload = payload, hexMode = isHex)
                        listAdapter?.notifyItemChanged(index)
                    }
                }
                persist()
                refreshEmptyState()
                editDialog.dismiss()
            }
        }
        editDialog.show()
    }

    private fun confirmDelete(item: PresetWriteCommand) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.preset_delete_confirm, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.preset_delete) { _, _ ->
                val index = commands.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    commands.removeAt(index)
                    listAdapter?.notifyItemRemoved(index)
                    persist()
                    refreshEmptyState()
                }
            }
            .show()
    }

    private fun persist() {
        PresetWriteCommandStore.saveAll(applicationContext, commands)
    }

    private fun refreshEmptyState() {
        viewBinding.tvEmpty.visibility = if (commands.isEmpty()) View.VISIBLE else View.GONE
        viewBinding.recyclerView.visibility = if (commands.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
