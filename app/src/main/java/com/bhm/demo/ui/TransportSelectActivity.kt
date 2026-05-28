package com.bhm.demo.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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

        viewBinding.btnEnterBle.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        viewBinding.btnEnterSpp.setOnClickListener {
            startActivity(Intent(this, SppMainActivity::class.java))
        }
    }
}
