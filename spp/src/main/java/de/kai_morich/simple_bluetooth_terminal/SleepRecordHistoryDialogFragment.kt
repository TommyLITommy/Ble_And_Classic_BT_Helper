package de.kai_morich.simple_bluetooth_terminal

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class SleepRecordHistoryDialogFragment : DialogFragment(), SleepRecordHistoryStore.Listener {

    private var tvHistory: TextView? = null
    private var tvCount: TextView? = null
    private var scrollView: ScrollView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_sleep_record_history, null)
        tvHistory = view.findViewById(R.id.tvSleepRecordHistory)
        tvCount = view.findViewById(R.id.tvSleepRecordCount)
        scrollView = view.findViewById(R.id.scrollSleepRecord)
        view.findViewById<Button>(R.id.btnClearSleepRecord).setOnClickListener {
            SleepRecordHistoryStore.clear()
        }

        refreshUi()

        return AlertDialog.Builder(requireContext())
            .setTitle("Sleep Record History")
            .setView(view)
            .setPositiveButton("Close", null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.72f).toInt(),
        )
        SleepRecordHistoryStore.addListener(this)
        refreshUi()
    }

    override fun onStop() {
        SleepRecordHistoryStore.removeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        tvHistory = null
        tvCount = null
        scrollView = null
        super.onDestroyView()
    }

    override fun onSleepRecordHistoryChanged() {
        val activity = activity ?: return
        activity.runOnUiThread { refreshUi(scrollToBottom = true) }
    }

    private fun refreshUi(scrollToBottom: Boolean = false) {
        val count = SleepRecordHistoryStore.size()
        tvCount?.text = "$count entries"
        tvHistory?.text = SleepRecordHistoryStore.formatAll()
        if (scrollToBottom) {
            scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val TAG = "SleepRecordHistoryDialog"

        fun show(fm: FragmentManager) {
            val existing = fm.findFragmentByTag(TAG)
            if (existing is SleepRecordHistoryDialogFragment) {
                if (existing.dialog?.isShowing == true) return
                existing.dismissAllowingStateLoss()
            }
            SleepRecordHistoryDialogFragment().show(fm, TAG)
        }
    }
}
