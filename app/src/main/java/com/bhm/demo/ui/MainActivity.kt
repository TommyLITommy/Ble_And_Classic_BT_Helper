package com.bhm.demo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.bhm.ble.device.BleDevice
import com.bhm.ble.log.BleLogger
import com.bhm.demo.BaseActivity
import com.bhm.demo.R
import com.bhm.demo.adapter.DeviceListAdapter
import com.bhm.demo.constants.LOCATION_PERMISSION
import com.bhm.demo.databinding.ActivityMainBinding
import com.bhm.demo.entity.ScanFilterMode
import com.bhm.demo.vm.MainViewModel
import com.bhm.support.sdk.utils.ViewUtil
import de.kai_morich.simple_bluetooth_terminal.MainActivity as SppMainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主页面 — BLE 扫描与设备列表
 */
class MainActivity : BaseActivity<MainViewModel, ActivityMainBinding>() {

    private var listAdapter: DeviceListAdapter? = null

    /**
     * 连接成功后是否自动打开服务/特征页面；从地址栏连接时退出详情页将断开 GATT。
     */
    private data class PendingGattPage(val disconnectGattWhenLeavingDetail: Boolean)

    private var pendingGattPage: PendingGattPage? = null

    private var filterExpanded = false

    override fun createViewModel() = MainViewModel(application)

    override fun handleEdgeToEdgeInsets(): Boolean = false

    override fun initData() {
        super.initData()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.vTop) { _: View, insets: WindowInsetsCompat ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            viewBinding.vTop.layoutParams.height = statusBars.top
            rootView.setPadding(0, 0, 0, navBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(viewBinding.vTop)
        initList()
        initSwipeRefresh()
        initFilterUi()
        viewModel.initBle()
        updateDeviceCount(0)
    }

    private fun initSwipeRefresh() {
        viewBinding.swipeRefresh.setColorSchemeResources(R.color.primary)
        viewBinding.swipeRefresh.setOnRefreshListener { restartScan(clearList = true) }
    }

    private fun initFilterUi() {
        updateFilterExpandUi()
        viewBinding.btnToggleFilter.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            filterExpanded = !filterExpanded
            updateFilterExpandUi()
        }
        viewBinding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chipFilterAll -> ScanFilterMode.ALL
                R.id.chipFilterName -> ScanFilterMode.NAME_FILTER
                else -> ScanFilterMode.NAMED_ONLY
            }
            viewModel.scanFilterMode = mode
            viewBinding.tilNameFilter.visibility =
                if (mode == ScanFilterMode.NAME_FILTER) View.VISIBLE else View.GONE
            updateFilterSummary()
        }
        viewBinding.etNameFilter.doAfterTextChanged {
            viewModel.nameFilterKeyword = it?.toString().orEmpty()
            if (viewModel.scanFilterMode == ScanFilterMode.NAME_FILTER) {
                updateFilterSummary()
            }
        }
        updateFilterSummary()
    }

    private fun updateFilterExpandUi() {
        viewBinding.llFilterExpandable.visibility =
            if (filterExpanded) View.VISIBLE else View.GONE
        viewBinding.btnToggleFilter.text = getString(
            if (filterExpanded) R.string.scan_filter_collapse else R.string.scan_filter_expand
        )
        viewBinding.btnToggleFilter.setIconResource(
            if (filterExpanded) R.drawable.icon_up else R.drawable.icon_down
        )
    }

    private fun updateFilterSummary() {
        val summary = when (viewModel.scanFilterMode) {
            ScanFilterMode.ALL -> getString(R.string.scan_filter_summary_all)
            ScanFilterMode.NAMED_ONLY -> getString(R.string.scan_filter_summary_named)
            ScanFilterMode.NAME_FILTER -> {
                val keyword = viewBinding.etNameFilter.text?.toString()?.trim().orEmpty()
                if (keyword.isEmpty()) {
                    getString(R.string.scan_filter_summary_name)
                } else {
                    getString(R.string.scan_filter_summary_name) + " · $keyword"
                }
            }
        }
        viewBinding.tvFilterSummary.text = summary
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun initEvent() {
        super.initEvent()

        lifecycleScope.launch {
            viewModel.scanUpdateStateFlow.collect { update ->
                update ?: return@collect
                when {
                    update.sortOrderChanged -> listAdapter?.notifyDataSetChanged()
                    update.isNew -> {
                        listAdapter?.notifyItemInserted(update.index)
                        if (update.index == 0) {
                            viewBinding.recyclerView.scrollToPosition(0)
                        }
                    }
                    else -> listAdapter?.notifyItemChanged(update.index)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.deviceCountStateFlow.collect { count ->
                updateDeviceCount(count)
            }
        }

        lifecycleScope.launch {
            viewModel.scanStopStateFlow.collect { stopped ->
                viewBinding.pbLoading.visibility = if (stopped) View.INVISIBLE else View.VISIBLE
                viewBinding.swipeRefresh.isRefreshing = !stopped
                viewBinding.btnStart.text =
                    if (stopped) getString(R.string.scan_start) else getString(R.string.scanning)
                viewBinding.btnStart.isEnabled = stopped
                viewBinding.btnConnect.isEnabled = stopped
                viewBinding.btnSetting.isEnabled = stopped
                viewBinding.btnStop.isEnabled = !stopped
                viewBinding.chipGroupFilter.isEnabled = stopped
                viewBinding.etNameFilter.isEnabled = stopped
            }
        }

        lifecycleScope.launch {
            viewModel.refreshStateFlow.collect {
                delay(300)
                dismissLoading()
                if (it?.bleDevice == null) {
                    pendingGattPage = null
                    listAdapter?.notifyDataSetChanged()
                    return@collect
                }
                val bleDevice = it.bleDevice
                val position = listAdapter?.data?.indexOf(bleDevice) ?: -1
                if (position >= 0) {
                    listAdapter?.notifyItemChanged(position)
                }
                val isConnected = viewModel.isConnected(bleDevice)
                if (bleDevice.deviceAddress == viewBinding.etAddress.text.toString()) {
                    viewBinding.btnConnect.isEnabled = !isConnected
                }
                pendingGattPage?.let { pending ->
                    if (isConnected) {
                        openGattDetailPage(bleDevice, pending.disconnectGattWhenLeavingDetail)
                    }
                    pendingGattPage = null
                }
            }
        }

        listAdapter?.addChildClickViewIds(R.id.btnConnect, R.id.btnOperate, R.id.btnPreview)
        listAdapter?.setOnItemChildClickListener { adapter, view, position ->
            if (ViewUtil.isInvalidClick(view)) return@setOnItemChildClickListener
            val bleDevice = adapter.data[position] as? BleDevice ?: return@setOnItemChildClickListener
            when (view.id) {
                R.id.btnConnect -> {
                    if (viewModel.isConnected(bleDevice)) {
                        showLoading("断开中...")
                        viewModel.disConnect(bleDevice)
                    } else {
                        showLoading("连接中...")
                        pendingGattPage = PendingGattPage(disconnectGattWhenLeavingDetail = false)
                        viewModel.connect(bleDevice)
                    }
                }
                R.id.btnOperate -> openGattDetailPage(bleDevice, disconnectGattWhenLeavingDetail = false)
                R.id.btnPreview -> ScanBroadcastActivity.start(this@MainActivity, bleDevice)
            }
        }

        listAdapter?.setOnItemClickListener { adapter, _, position ->
            val bleDevice = adapter.data[position] as? BleDevice ?: return@setOnItemClickListener
            ScanBroadcastActivity.start(this@MainActivity, bleDevice)
        }

        viewBinding.btnConnect.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            requestPermission(
                LOCATION_PERMISSION,
                {
                    BleLogger.d("获取到了权限")
                    val address = viewBinding.etAddress.text.toString().trim()
                    if (address.isEmpty()) {
                        Toast.makeText(application, "请输入设备地址", Toast.LENGTH_SHORT).show()
                        return@requestPermission
                    }
                    if (!Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(address)) {
                        Toast.makeText(application, "请输入正确的设备地址", Toast.LENGTH_SHORT).show()
                        return@requestPermission
                    }
                    pendingGattPage = PendingGattPage(disconnectGattWhenLeavingDetail = true)
                    showLoading("连接中...")
                    viewModel.connect(address)
                },
                { BleLogger.w("缺少定位权限") }
            )
        }

        viewBinding.btnSetting.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            startActivity(Intent(this@MainActivity, OptionSettingActivity::class.java))
        }

        viewBinding.btnSppTerminal.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            startActivity(Intent(this@MainActivity, SppMainActivity::class.java))
        }

        viewBinding.btnStart.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            restartScan(clearList = true)
        }

        viewBinding.btnStop.setOnClickListener {
            if (ViewUtil.isInvalidClick(it)) return@setOnClickListener
            viewModel.stopScan()
        }
    }

    private fun initList() {
        val layoutManager = LinearLayoutManager(this)
        viewBinding.recyclerView.layoutManager = layoutManager
        (viewBinding.recyclerView.itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
        listAdapter = DeviceListAdapter(viewModel.listDRData)
        viewBinding.recyclerView.adapter = listAdapter
    }

    private fun updateDeviceCount(count: Int) {
        viewBinding.tvDeviceCount.text = getString(R.string.scan_device_count, count)
    }

    /** 清空列表并重新开始扫描（开始扫描按钮 / 下拉刷新共用） */
    private fun restartScan(clearList: Boolean) {
        viewModel.nameFilterKeyword = viewBinding.etNameFilter.text?.toString().orEmpty()
        if (clearList) {
            val removedCount = viewModel.listDRData.size
            viewModel.clearScanResults()
            if (removedCount > 0) {
                listAdapter?.notifyItemRangeRemoved(0, removedCount)
            }
        }
        viewModel.refreshScan(this)
    }

    private fun openGattDetailPage(bleDevice: BleDevice?, disconnectGattWhenLeavingDetail: Boolean) {
        if (viewModel.isConnected(bleDevice)) {
            val intent = Intent(this@MainActivity, DetailOperateActivity::class.java)
            intent.putExtra("data", bleDevice)
            intent.putExtra("disConnectWhileClose", disconnectGattWhenLeavingDetail)
            startActivity(intent) { _, resultIntent ->
                if (resultIntent != null) {
                    showLoading("断开中...")
                    lifecycleScope.launch {
                        delay(1200)
                        dismissLoading()
                    }
                }
            }
        } else {
            Toast.makeText(application, "设备未连接", Toast.LENGTH_SHORT).show()
            val index = listAdapter?.data?.indexOf(bleDevice) ?: -1
            if (index >= 0) listAdapter?.notifyItemChanged(index)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopScan()
        viewModel.close()
    }
}
