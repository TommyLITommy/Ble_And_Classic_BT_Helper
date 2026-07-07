package de.kai_morich.simple_bluetooth_terminal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.ListFragment
import java.util.ArrayList

class DevicesFragment : ListFragment() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val listItems = ArrayList<BluetoothDevice>()
    private lateinit var listAdapter: ArrayAdapter<BluetoothDevice>
    private lateinit var requestBluetoothPermissionLauncherForRefresh: ActivityResultLauncher<String>
    private var menu: Menu? = null
    private var permissionMissing = false
    private var sppUuidSpinner: Spinner? = null
    private var sppUuidInitializing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        val activity = activity ?: return
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }

        listAdapter = object : ArrayAdapter<BluetoothDevice>(activity, 0, listItems) {
            @SuppressLint("SetTextI18n", "MissingPermission")
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                val device = listItems[position]
                val row = view ?: layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val text1 = row.findViewById<TextView>(R.id.text1)
                val text2 = row.findViewById<TextView>(R.id.text2)
                text1.text = "Name:${device.name}"
                text2.text = "Addr:${device.address}"
                return row
            }
        }

        requestBluetoothPermissionLauncherForRefresh = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            BluetoothUtil.onPermissionsResult(this, granted) { refresh() }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = layoutInflater.inflate(R.layout.device_list_header, null, false)
        sppUuidSpinner = header.findViewById(R.id.spp_uuid_spinner)
        setupSppUuidSpinner()
        listView.addHeaderView(header, null, false)
        setEmptyText("initializing...")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        this.menu = menu
        inflater.inflate(R.menu.menu_devices, menu)
        if (permissionMissing) {
            menu.findItem(R.id.bt_refresh).isVisible = true
        }
        if (bluetoothAdapter == null) {
            menu.findItem(R.id.bt_settings).isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.bt_settings -> {
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                true
            }
            R.id.bt_refresh -> {
                if (BluetoothUtil.hasPermissions(this, requestBluetoothPermissionLauncherForRefresh)) {
                    refresh()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        listItems.clear()
        bluetoothAdapter?.let { adapter ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionMissing = requireActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                menu?.findItem(R.id.bt_refresh)?.isVisible = permissionMissing
            }
            if (!permissionMissing) {
                for (device in adapter.bondedDevices) {
                    if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
                        listItems.add(device)
                    }
                }
                listItems.sortWith(BluetoothUtil::compareTo)
            }
        }

        when {
            bluetoothAdapter == null -> setEmptyText("<bluetooth not supported>")
            bluetoothAdapter?.isEnabled == false -> setEmptyText("<bluetooth is disabled>")
            permissionMissing -> setEmptyText("<permission missing, use REFRESH>")
            else -> setEmptyText("<no bluetooth devices found>")
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun setupSppUuidSpinner() {
        val spinner = sppUuidSpinner ?: return
        val activity = activity ?: return
        val values = resources.getStringArray(R.array.spp_uuid_values)
        spinner.adapter = ArrayAdapter(
            activity,
            R.layout.spp_uuid_spinner_item,
            values
        ).apply {
            setDropDownViewResource(R.layout.spp_uuid_spinner_dropdown_item)
        }

        val savedUuid = SppPreferences.getServiceUuidString(activity)
        val savedIndex = values.indexOf(savedUuid).let { if (it >= 0) it else 0 }

        sppUuidInitializing = true
        spinner.setSelection(savedIndex, false)
        sppUuidInitializing = false

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (sppUuidInitializing) return
                SppPreferences.setServiceUuid(activity, values[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val device = listItems[position - 1]
        val args = Bundle().apply {
            putString("device", device.address)
        }
        val fragment: Fragment = TerminalFragment().apply {
            arguments = args
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment, fragment, "terminal")
            .addToBackStack(null)
            .commit()
    }
}
