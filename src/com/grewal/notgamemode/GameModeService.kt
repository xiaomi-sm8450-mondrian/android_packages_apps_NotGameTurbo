/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class GameModeService : Service() {

    private lateinit var prefs: GamePrefs
    private lateinit var usageStats: UsageStatsManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var lastForeground: String? = null
    private var activePkg: String? = null

    @Volatile private var gameModeActive = false
    @Volatile private var polling = false

    private val poll =
        object : Runnable {
            override fun run() {
                if (!polling) {
                    return
                }
                update()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> startPolling()
                    Intent.ACTION_SCREEN_OFF -> stopPolling()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        prefs = GamePrefs(this)
        usageStats = getSystemService(UsageStatsManager::class.java)
        thread = HandlerThread(TAG).apply { start() }
        handler = Handler(thread.looper)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        if (getSystemService(PowerManager::class.java).isInteractive) {
            startPolling()
        }
        Log.i(TAG, "started")
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        polling = false
        handler.removeCallbacks(poll)
        thread.quitSafely()
        disableGameMode()
        super.onDestroy()
    }

    private fun startPolling() {
        if (polling) {
            return
        }
        polling = true
        handler.post(poll)
        Log.i(TAG, "polling started")
    }

    private fun stopPolling() {
        if (!polling) {
            return
        }
        polling = false
        handler.removeCallbacks(poll)
        handler.post { disableGameMode() }
        Log.i(TAG, "polling stopped")
    }

    private fun disableGameMode() {
        if (!gameModeActive) {
            return
        }
        gameModeActive = false
        activePkg = null
        TouchFeatureManager.setSuperReport(false)
        TouchFeatureManager.setGameMode(false)
        setOrientationTracking(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun update() {

        if (manualOverride) {
            return
        }

        val foreground = currentForegroundPackage() ?: lastForeground
        lastForeground = foreground

        val shouldEnable = foreground != null && prefs.isEnabled(foreground)
        if (shouldEnable) {
            if (!gameModeActive) {
                gameModeActive = true
                activePkg = foreground

                applyTuning(foreground!!)
                TouchFeatureManager.setGameMode(true)
                TouchFeatureManager.setSuperReport(prefs.isSuperReport(foreground))
                setOrientationTracking(true)
            } else if (foreground != activePkg) {

                activePkg = foreground
                applyTuning(foreground!!)
                TouchFeatureManager.setSuperReport(prefs.isSuperReport(foreground!!))
            }
        } else {
            disableGameMode()
        }
    }

    private fun applyTuning(pkg: String) {
        if (prefs.isExpert(pkg)) {

            TouchFeatureManager.setTuning(
                TouchFeatureManager.TOUCH_EXPERT_MODE,
                prefs.expertPreset(pkg),
            )
            return
        }
        TouchFeatureManager.TUNING_RANGES.forEach { (mode, range) ->
            TouchFeatureManager.setTuning(mode, prefs.tuningValue(pkg, mode, range.def))
        }
    }

    private fun setOrientationTracking(enabled: Boolean) {
        val intent = Intent(this, TouchOrientationService::class.java)
        if (enabled) startService(intent) else stopService(intent)
    }

    private fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - QUERY_WINDOW_MS, now)
        val event = android.app.usage.UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    companion object {
        private const val TAG = "GameModeService"
        private const val POLL_INTERVAL_MS = 2000L
        private const val QUERY_WINDOW_MS = 10_000L

        @Volatile var manualOverride = false
    }
}
