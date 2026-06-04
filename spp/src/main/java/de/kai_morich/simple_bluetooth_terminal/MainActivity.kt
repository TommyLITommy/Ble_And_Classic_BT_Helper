package de.kai_morich.simple_bluetooth_terminal

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager

class MainActivity : AppCompatActivity(), FragmentManager.OnBackStackChangedListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.spp_activity_main)
        setupWindowInsets()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportFragmentManager.addOnBackStackChangedListener(this)
        onBackStackChanged()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment, DevicesFragment(), "devices")
                .commit()
        }
    }

    override fun onBackStackChanged() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val vTop = findViewById<View>(R.id.vTop)
        val fragmentHost = findViewById<View>(R.id.fragment)
        val extraTop = resources.getDimensionPixelSize(R.dimen.spp_toolbar_extra_top)
        val insetTypes = WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(vTop) { v, insets ->
            var systemTop = insets.getInsets(insetTypes).top
            if (systemTop <= 0) {
                systemTop = statusBarHeightPx()
            }
            val topInset = systemTop + extraTop
            v.layoutParams = v.layoutParams.apply { height = topInset }
            v.requestLayout()
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            fragmentHost.setPadding(0, 0, 0, navBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(vTop)
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }
}
