package de.kai_morich.simple_bluetooth_terminal

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TapParameterDialogFragment : DialogFragment() {

    interface OnParameterSetListener {
        fun onParametersSet(params: AppInvImuTapParameters)
    }

    private var listener: OnParameterSetListener? = null
    private var parameters: TapParameterParser? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            context is OnParameterSetListener -> context
            parentFragment is OnParameterSetListener -> parentFragment as OnParameterSetListener
            else -> throw ClassCastException("Host activity or parent fragment must implement OnParameterSetListener")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getByteArray(ARG_PARAMETERS)?.let { paramData ->
            parameters = deserializeParameters(paramData)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val inflater: LayoutInflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_tap_parameters, null)

        val editTapTmax = view.findViewById<EditText>(R.id.editTapTmax)
        val editTapTmin = view.findViewById<EditText>(R.id.editTapTmin)
        val editTapMax = view.findViewById<EditText>(R.id.editTapMax)
        val editTapMin = view.findViewById<EditText>(R.id.editTapMin)
        val editTapMaxPeakTol = view.findViewById<EditText>(R.id.editTapMaxPeakTol)
        val editTapTavg = view.findViewById<EditText>(R.id.editTapTavg)
        val editTapMinJerkThreshold = view.findViewById<EditText>(R.id.editTapMinJerkThreshold)
        val editTapSmudgeRejection = view.findViewById<EditText>(R.id.editTapSmudgeRejection)

        parameters?.let { p ->
            editTapTmax.setText(p.tap_tmax.toString())
            editTapTmin.setText((p.tap_tmin.toInt() and 0xFF).toString())
            editTapMax.setText((p.tap_max.toInt() and 0xFF).toString())
            editTapMin.setText((p.tap_min.toInt() and 0xFF).toString())
            editTapMaxPeakTol.setText((p.tap_max_peak_tol.toInt() and 0xFF).toString())
            editTapTavg.setText((p.tap_tavg.toInt() and 0xFF).toString())
            editTapMinJerkThreshold.setText(p.tap_min_jerk_threshold.toString())
            editTapSmudgeRejection.setText((p.tap_smudge_rejection.toInt() and 0xFF).toString())
        } ?: run {
            editTapTmax.setText("450")
            editTapTmin.setText("60")
            editTapMax.setText("3")
            editTapMin.setText("1")
            editTapMaxPeakTol.setText("4")
            editTapTavg.setText("8")
            editTapMinJerkThreshold.setText("2048")
            editTapSmudgeRejection.setText("34")
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setTitle("Tap Parameters Configuration")
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel") { _, _ -> dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                try {
                    val params = AppInvImuTapParameters().apply {
                        tap_tmax = parseShort(editTapTmax.text.toString(), "tap_tmax")
                        tap_tmin = parseByte(editTapTmin.text.toString(), "tap_tmin")
                        tap_max = parseByte(editTapMax.text.toString(), "tap_max")
                        tap_min = parseByte(editTapMin.text.toString(), "tap_min")
                        tap_max_peak_tol = parseByte(editTapMaxPeakTol.text.toString(), "tap_max_peak_tol")
                        tap_tavg = parseByte(editTapTavg.text.toString(), "tap_tavg")
                        tap_min_jerk_threshold = parseShort(editTapMinJerkThreshold.text.toString(), "tap_min_jerk_threshold")
                        tap_smudge_rejection = parseByte(editTapSmudgeRejection.text.toString(), "tap_smudge_rejection")
                    }
                    listener?.onParametersSet(params)
                    dialog.dismiss()
                } catch (e: NumberFormatException) {
                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        return dialog
    }

    @Throws(NumberFormatException::class)
    private fun parseByte(s: String, fieldName: String): Byte {
        if (TextUtils.isEmpty(s)) return 0
        try {
            val value = Integer.decode(s)
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw NumberFormatException("$fieldName value out of range (${Byte.MIN_VALUE} to ${Byte.MAX_VALUE})")
            }
            return value.toByte()
        } catch (_: NumberFormatException) {
            throw NumberFormatException("$fieldName: please enter a valid number")
        }
    }

    @Throws(NumberFormatException::class)
    private fun parseShort(s: String, fieldName: String): Short {
        if (TextUtils.isEmpty(s)) return 0
        try {
            val value = Integer.decode(s)
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw NumberFormatException("$fieldName value out of range (${Short.MIN_VALUE} to ${Short.MAX_VALUE})")
            }
            return value.toShort()
        } catch (_: NumberFormatException) {
            throw NumberFormatException("$fieldName: please enter a valid number")
        }
    }

    companion object {
        private const val ARG_PARAMETERS = "parameters"

        fun newInstance(parameters: TapParameterParser?): TapParameterDialogFragment {
            val fragment = TapParameterDialogFragment()
            val args = Bundle()
            args.putByteArray(ARG_PARAMETERS, serializeParameters(parameters))
            fragment.arguments = args
            return fragment
        }

        private fun serializeParameters(parameters: TapParameterParser?): ByteArray? {
            if (parameters == null) return null
            val buffer = ByteBuffer.allocate(10)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(parameters.tap_tmax)
            buffer.put(parameters.tap_tmin)
            buffer.put(parameters.tap_max)
            buffer.put(parameters.tap_min)
            buffer.put(parameters.tap_max_peak_tol)
            buffer.put(parameters.tap_tavg)
            buffer.putShort(parameters.tap_min_jerk_threshold)
            buffer.put(parameters.tap_smudge_rejection)
            return buffer.array()
        }

        private fun deserializeParameters(data: ByteArray?): TapParameterParser? {
            if (data == null || data.size != 10) return null
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return TapParameterParser(ByteArray(10)).apply {
                tap_tmax = buffer.short
                tap_tmin = buffer.get()
                tap_max = buffer.get()
                tap_min = buffer.get()
                tap_max_peak_tol = buffer.get()
                tap_tavg = buffer.get()
                tap_min_jerk_threshold = buffer.short
                tap_smudge_rejection = buffer.get()
            }
        }
    }
}
