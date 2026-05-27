package com.bhm.demo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bhm.ble.device.BleDevice
import com.bhm.demo.R
import com.bhm.demo.databinding.ActivityScanBroadcastBinding
import com.bhm.demo.utils.BleScanRecordFormatter
import com.google.android.material.tabs.TabLayout

/**
 * BLE 广播数据预览
 */
class ScanBroadcastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBroadcastBinding
    private lateinit var bleDevice: BleDevice
    private var showingHex = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBroadcastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DEVICE, BleDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DEVICE)
        } ?: run {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        bindDeviceInfo()
        bindBroadcastContent()
        setupTabs()
        binding.btnCopy.setOnClickListener { copyCurrentTabContent() }
    }

    private fun bindDeviceInfo() {
        val name = bleDevice.deviceName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.device_unnamed)
        binding.tvDeviceName.text = name
        binding.tvDeviceAddress.text = bleDevice.deviceAddress ?: "-"
        binding.tvRssi.text = getString(R.string.rssi_format, bleDevice.rssi ?: 0)
    }

    private fun bindBroadcastContent() {
        val record = bleDevice.scanRecord
        binding.tvHex.text = BleScanRecordFormatter.formatHex(record).ifBlank { "-" }
        val structures = BleScanRecordFormatter.parseAdStructures(record)
        binding.tvParsed.text = if (structures.isEmpty()) {
            getString(R.string.scan_empty)
        } else {
            structures.joinToString("\n\n") { ad ->
                buildString {
                    append("Type: 0x")
                    append(ad.type.toString(16).uppercase().padStart(2, '0'))
                    append(" (")
                    append(ad.typeName)
                    append(")\n")
                    if (ad.description.isNotEmpty()) {
                        append("Value: ")
                        append(ad.description)
                        append('\n')
                    }
                    append("Data: ")
                    append(ad.dataHex)
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                showingHex = tab?.position == 1
                binding.scrollParsed.visibility = if (showingHex) View.GONE else View.VISIBLE
                binding.scrollHex.visibility = if (showingHex) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun copyCurrentTabContent() {
        val text = if (showingHex) binding.tvHex.text else binding.tvParsed.text
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ble_broadcast", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_DEVICE = "ble_device"

        fun start(context: Context, bleDevice: BleDevice) {
            context.startActivity(
                Intent(context, ScanBroadcastActivity::class.java).apply {
                    putExtra(EXTRA_DEVICE, bleDevice)
                }
            )
        }
    }
}
