/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.adapter

import android.bluetooth.BluetoothGattCharacteristic
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.bhm.demo.R
import com.bhm.demo.entity.CharacteristicNode
import com.bhm.demo.entity.OperateType
import com.bhm.demo.entity.ServiceNode
import com.bhm.support.sdk.utils.ViewUtil
import com.chad.library.adapter.base.BaseNodeAdapter
import com.chad.library.adapter.base.entity.node.BaseNode
import com.chad.library.adapter.base.provider.BaseNodeProvider
import com.chad.library.adapter.base.viewholder.BaseViewHolder


/**
 * 折叠布局 显示服务 特征值
 *
 * @author Buhuiming
 * @date 2023年06月01日 10时10分
 */
class DetailsExpandAdapter(nodeList: MutableList<BaseNode>,
                           operateCallback: ((checkBox: CheckBox?,
                                              operateType: OperateType,
                                              isChecked: Boolean,
                                              node: CharacteristicNode) -> Unit)? = null
) : BaseNodeAdapter(nodeList) {

    init {
        // 需要占满一行的，使用此方法（例如section）
        addFullSpanNodeProvider(ServiceNodeProvider())
        // 普通的item provider
        addNodeProvider(CharacteristicProvider(operateCallback))
    }

    override fun getItemType(data: List<BaseNode>, position: Int): Int {
        return when (data[position]) {
            is ServiceNode -> 0
            is CharacteristicNode -> 1
            else -> -1
        }
    }

    class ServiceNodeProvider : BaseNodeProvider() {
        override val itemViewType: Int
            get() = 0
        override val layoutId: Int
            get() = R.layout.layout_recycler_service

        override fun convert(helper: BaseViewHolder, item: BaseNode) {
            val node = item as ServiceNode
            helper.setText(R.id.tvServiceName, "服务: (${node.serviceName})")
            helper.setText(R.id.tvServiceUUID, "ServiceUUID: ${node.serviceUUID}")
            val expandable = node.childNode?.isNotEmpty() == true
            helper.setVisible(R.id.ivExpand, expandable)
            helper.setImageResource(
                R.id.ivExpand,
                if (node.isExpanded) R.drawable.icon_down else R.drawable.icon_right
            )
            helper.itemView.isClickable = expandable
            helper.itemView.isFocusable = expandable
            if (expandable) {
                helper.itemView.setOnClickListener {
                    val position = helper.layoutPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                    getAdapter()?.expandOrCollapse(position, animate = true, notify = true)
                }
            } else {
                helper.itemView.setOnClickListener(null)
            }
        }
    }

    class CharacteristicProvider(private val operateCallback: ((checkBox: CheckBox?,
                                                                operateType: OperateType,
                                                                isChecked: Boolean,
                                                                node: CharacteristicNode) -> Unit)? = null
    ) : BaseNodeProvider() {
        override val itemViewType: Int
            get() = 1
        override val layoutId: Int
            get() = R.layout.layout_recycler_characteristic

        override fun convert(helper: BaseViewHolder, item: BaseNode) {
            val node = item as CharacteristicNode
            applyCharacteristicItemSpacing(helper)
            helper.setText(R.id.tvCharacteristicName, "特征(${node.characteristicName})")
            // UUID / properties 只展示内容，避免显示 "Characteristic" 字样占用空间
            helper.setText(R.id.tvCharacteristicUUID, node.characteristicUUID)
            helper.setText(R.id.tvCharacteristicProperties, node.characteristicProperties)
            helper.setGone(R.id.tvCharacteristicProperties, node.characteristicProperties.isEmpty())

            val btnWriteData = helper.getView<Button>(R.id.btnWriteData)
            val btnReadData = helper.getView<Button>(R.id.btnReadData)
            val cbNotify = helper.getView<CheckBox>(R.id.cbNotify)
            val cbIndicate = helper.getView<CheckBox>(R.id.cbIndicate)
            cbNotify.isChecked = node.enableNotify
            cbIndicate.isChecked = node.enableIndicate

            val charaProp: Int = node.characteristicIntProperties
            helper.setGone(R.id.btnReadData, charaProp and BluetoothGattCharacteristic.PROPERTY_READ <= 0)
            helper.setGone(R.id.btnWriteData, charaProp and BluetoothGattCharacteristic.PROPERTY_WRITE <= 0 &&
                    charaProp and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE <= 0)
            helper.setGone(R.id.cbNotify, charaProp and BluetoothGattCharacteristic.PROPERTY_NOTIFY <= 0)
            helper.setGone(R.id.cbIndicate, charaProp and BluetoothGattCharacteristic.PROPERTY_INDICATE <= 0)

            layoutCharacteristicActions(
                helper.getView(R.id.llCharacteristicActions),
                listOf(btnReadData, cbNotify, btnWriteData, cbIndicate)
            )

            btnWriteData.setOnClickListener {
                if (ViewUtil.isInvalidClick(it)) {
                    return@setOnClickListener
                }
                operateCallback?.invoke(null, OperateType.Write, false, node)
            }
            btnReadData.setOnClickListener {
                if (ViewUtil.isInvalidClick(it)) {
                    return@setOnClickListener
                }
                operateCallback?.invoke(null, OperateType.Read, false, node)
            }
            cbNotify.setOnClickListener { buttonView ->
                if (ViewUtil.isInvalidClick(buttonView)) {
                    return@setOnClickListener
                }
                node.enableNotify = cbNotify.isChecked
                val isChecked = cbNotify.isChecked
                operateCallback?.invoke(buttonView as CheckBox, OperateType.Notify, isChecked, node)
            }
            cbIndicate.setOnClickListener { buttonView ->
                if (ViewUtil.isInvalidClick(buttonView)) {
                    return@setOnClickListener
                }
                node.enableIndicate = cbIndicate.isChecked
                val isChecked = cbIndicate.isChecked
                operateCallback?.invoke(buttonView as CheckBox, OperateType.Indicate, isChecked, node)
            }
        }

        /**
         * 按可见控件数量排列：
         * - 仅 1 个且为 Button：占满整行；仅 1 个 Checkbox：居中
         * - 2 个：左右各占一半（含 Button 时 Button 填满所属半区）
         * - 3 个及以上：等分宽度 space-between（Button 填满各自区域）
         */
        private fun layoutCharacteristicActions(container: LinearLayout, orderedViews: List<View>) {
            container.removeAllViews()
            val visible = orderedViews.filter { it.visibility == View.VISIBLE }
            val gap = (4 * container.resources.displayMetrics.density + 0.5f).toInt()
            val actionHeight = container.resources.getDimensionPixelSize(R.dimen.gatt_action_height)

            when (visible.size) {
                0 -> Unit
                1 -> {
                    val view = visible[0]
                    if (view is Button) {
                        container.gravity = Gravity.CENTER_VERTICAL
                        container.addView(
                            view,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                actionHeight
                            )
                        )
                    } else {
                        container.gravity = Gravity.CENTER
                        container.addView(
                            view,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                actionHeight
                            )
                        )
                    }
                }
                else -> {
                    container.gravity = Gravity.CENTER_VERTICAL
                    visible.forEachIndexed { index, view ->
                        val lp = LinearLayout.LayoutParams(0, actionHeight, 1f)
                        if (index > 0) {
                            lp.marginStart = gap
                        }
                        lp.gravity = Gravity.CENTER
                        container.addView(view, lp)
                    }
                }
            }
        }

        private fun applyCharacteristicItemSpacing(helper: BaseViewHolder) {
            val resources = helper.itemView.context.resources
            val density = resources.displayMetrics.density
            val groupGap = (resources.getDimension(R.dimen.gatt_char_group_spacing) + 0.5f).toInt()
            val bottom = if (isLastCharacteristicInService(helper)) groupGap else (2 * density).toInt()
            val lp = helper.itemView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom
                helper.itemView.layoutParams = lp
            }
        }

        private fun isLastCharacteristicInService(helper: BaseViewHolder): Boolean {
            val adapter = getAdapter() ?: return true
            val position = helper.layoutPosition
            if (position == RecyclerView.NO_POSITION) return true
            val nextIndex = position + 1
            if (nextIndex >= adapter.data.size) return true
            return adapter.data[nextIndex] is ServiceNode
        }
    }
}
