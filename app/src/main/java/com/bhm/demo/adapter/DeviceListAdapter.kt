package com.bhm.demo.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.bhm.ble.BleManager
import com.bhm.ble.device.BleDevice
import com.bhm.demo.R
import com.bhm.demo.databinding.LayoutRecyclerItemBinding
import com.bhm.demo.utils.BleScanRecordFormatter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder


/**
 * 设备列表
 */
class DeviceListAdapter(data: MutableList<BleDevice>?) :
    BaseQuickAdapter<BleDevice, DeviceListAdapter.VH>(0, data) {

    class VH(
        parent: ViewGroup,
        val binding: LayoutRecyclerItemBinding = LayoutRecyclerItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : BaseViewHolder(binding.root)

    override fun onCreateDefViewHolder(parent: ViewGroup, viewType: Int): VH = VH(parent)

    override fun convert(holder: VH, item: BleDevice) {
        val context = holder.binding.root.context
        val displayName = item.deviceName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.device_unnamed)
        holder.binding.tvName.text = displayName
        holder.binding.tvAddress.text = item.deviceAddress ?: "-"
        holder.binding.tvRssi.text = context.getString(R.string.rssi_format, item.rssi ?: 0)

        val rssi = item.rssi ?: -100
        holder.binding.tvRssi.setTextColor(
            ContextCompat.getColor(
                context,
                if (rssi >= -75) R.color.rssi_good else R.color.rssi_weak
            )
        )
        when {
            rssi >= -65 -> holder.binding.ivRssi.setImageResource(R.drawable.adddevice_device_signal_four_icon)
            rssi >= -75 -> holder.binding.ivRssi.setImageResource(R.drawable.adddevice_device_signal_three_icon)
            rssi >= -85 -> holder.binding.ivRssi.setImageResource(R.drawable.adddevice_device_signal_two_icon)
            else -> holder.binding.ivRssi.setImageResource(R.drawable.adddevice_device_signal_one_icon)
        }

        holder.binding.tvBroadcastPreview.text =
            BleScanRecordFormatter.formatParsedSummary(item.scanRecord)

        if (BleManager.get().isConnected(item)) {
            holder.binding.btnConnect.text = context.getString(R.string.disconnect)
            holder.binding.btnOperate.isEnabled = true
            holder.binding.btnConnect.setBackgroundResource(R.drawable.bg_button_danger)
        } else {
            holder.binding.btnConnect.text = context.getString(R.string.connect)
            holder.binding.btnOperate.isEnabled = false
            holder.binding.btnConnect.setBackgroundResource(R.drawable.bg_button_primary)
        }
    }
}
