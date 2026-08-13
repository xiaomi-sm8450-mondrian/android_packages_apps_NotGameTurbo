/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class DebugFragment : SettingsBasePreferenceFragment() {

    private val rows = mutableListOf<Pair<Preference, Int>>()
    private var pollingRateRow: Preference? = null

    private val modes =
        listOf(
            "Game_Mode" to 0,
            "Active_MODE" to 1,
            "UP_THRESHOLD" to 2,
            "Tolerance" to 3,
            "Aim_Sensitivity" to 4,
            "Tap_Stability" to 5,
            "Expert_Mode" to 6,
            "Edge_Filter" to 7,
            "Panel_Orientation" to 8,
            "Report_Rate" to 9,
            "Super_Report" to 202,
        )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(
            Preference(context).apply {
                title = getString(R.string.debug_refresh)
                setOnPreferenceClickListener {
                    refresh()
                    true
                }
            }
        )

        // The mode rows below are all ITouchFeature queries, so they read as a wall of dashes on
        // targets that declare no such HAL. Name the interfaces up front rather than leaving that
        // unexplained.
        listOf(
                "vendor.xiaomi.hw.touchfeature.ITouchFeature" to
                    TouchFeatureManager.isTouchFeatureDeclared(),
                "vendor.lineage.touch.IHighTouchPollingRate" to
                    TouchFeatureManager.isPollingRateDeclared(),
            )
            .forEach { (descriptor, declared) ->
                screen.addPreference(
                    Preference(context).apply {
                        title = descriptor
                        summary =
                            getString(
                                if (declared) R.string.debug_declared
                                else R.string.debug_not_declared
                            )
                        isSelectable = false
                        isIconSpaceReserved = false
                    }
                )
            }

        pollingRateRow =
            Preference(context).apply {
                title = getString(R.string.debug_polling_rate)
                summary = getString(R.string.debug_querying)
                isSelectable = false
                isIconSpaceReserved = false
            }
        screen.addPreference(pollingRateRow!!)

        modes.forEach { (label, mode) ->
            val pref =
                Preference(context).apply {
                    title = "$label ($mode)"
                    summary = getString(R.string.debug_querying)
                    isSelectable = false
                    isIconSpaceReserved = false
                }
            screen.addPreference(pref)
            rows.add(pref to mode)
        }

        preferenceScreen = screen
        refresh()
    }

    private fun refresh() {
        val querying = getString(R.string.debug_querying)
        rows.forEach { (pref, _) -> pref.summary = querying }
        pollingRateRow?.summary = querying
        val handler = Handler(Looper.getMainLooper())
        Thread {
                val results =
                    rows.map { (pref, mode) -> pref to TouchFeatureManager.queryMode(mode) }
                val pollingRate = TouchFeatureManager.queryPollingRate()
                handler.post {
                    results.forEach { (pref, q) -> pref.summary = format(q) }
                    pollingRateRow?.summary =
                        when (pollingRate) {
                            true -> "enabled"
                            false -> "disabled"
                            null -> "—"
                        }
                }
            }
            .start()
    }

    private fun format(q: TouchFeatureManager.ModeQuery): String {
        fun s(v: Int?) = v?.toString() ?: "—"
        return "cur=${s(q.cur)}  def=${s(q.def)}  min=${s(q.min)}  max=${s(q.max)}  values=${s(q.values)}"
    }
}
