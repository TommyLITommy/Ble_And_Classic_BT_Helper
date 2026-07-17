package de.kai_morich.simple_bluetooth_terminal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Accumulates valid sleep_record snapshots so they are not lost in the 1Hz RX flood.
 * Every valid reception is kept as-is (no dedupe).
 */
object SleepRecordHistoryStore {

    data class Entry(
        val receiveTime: String,
        val content: String,
    )

    interface Listener {
        fun onSleepRecordHistoryChanged()
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addFromParser(parser: SleepUploadDataParser, receiveTime: String = now()) {
        val content = parser.sleepRecordsContentOrNull() ?: return
        synchronized(lock) {
            entries.add(Entry(receiveTime, content))
        }
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) {
            if (entries.isEmpty()) return
            entries.clear()
        }
        notifyListeners()
    }

    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    fun size(): Int = synchronized(lock) { entries.size }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Format:
     *   HH:mm:ss
     *   len=N
     *   [0] stage=...,pos=...
     *   ...  (N/4 lines of sleep_record groups)
     */
    fun formatAll(): String {
        val list = snapshot()
        if (list.isEmpty()) return "(empty)"
        return buildString {
            list.forEachIndexed { index, entry ->
                if (index > 0) append('\n')
                append(entry.receiveTime).append('\n')
                append(entry.content).append('\n')
            }
        }
    }

    private fun notifyListeners() {
        listeners.forEach { it.onSleepRecordHistoryChanged() }
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
