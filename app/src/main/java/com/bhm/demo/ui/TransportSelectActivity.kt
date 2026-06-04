package com.bhm.demo.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bhm.demo.R
import com.bhm.demo.databinding.ActivityTransportSelectBinding
import de.kai_morich.simple_bluetooth_terminal.MainActivity as SppMainActivity

/**
 * 统一入口：让用户选择走 BLE 还是经典蓝牙 SPP。
 */
class TransportSelectActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityTransportSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityTransportSelectBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        setupWindowInsets()

        viewBinding.btnEnterBle.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        viewBinding.btnEnterSpp.setOnClickListener {
            startActivity(Intent(this, SppMainActivity::class.java))
        }
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val contentPadding = resources.getDimensionPixelSize(R.dimen.transport_select_padding)
        val insetTypes = WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { v, insets ->
            var topInset = insets.getInsets(insetTypes).top
            if (topInset <= 0) {
                topInset = statusBarHeightPx()
            }
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                contentPadding,
                contentPadding + topInset,
                contentPadding,
                contentPadding + navBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(viewBinding.root)
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }
}
