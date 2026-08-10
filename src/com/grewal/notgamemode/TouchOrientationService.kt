/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Display

class TouchOrientationService : Service() {

    private lateinit var displayManager: DisplayManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var lastRotation = -1

    // Not onConfigurationChanged: that does not fire on a 180 degree flip, since Configuration is
    // unchanged between portrait and reverse portrait.
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    updateOrientation()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        thread = HandlerThread(TAG).apply { start() }
        handler = Handler(thread.looper)
        // A null handler here would deliver callbacks on the main thread, putting a synchronous HAL
        // binder call on it every rotation.
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post { updateOrientation() }
        return START_STICKY
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateOrientation() {
        // onDisplayChanged also fires for brightness and refresh rate, so only act on real moves.
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        if (rotation == lastRotation) {
            return
        }
        lastRotation = rotation
        Log.i(TAG, "orientation update, rotation: $rotation")
        TouchFeatureManager.setPanelOrientation(rotation)
    }

    companion object {
        private const val TAG = "TouchOrientationService"
    }
}
