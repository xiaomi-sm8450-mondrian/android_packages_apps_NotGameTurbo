/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import java.util.concurrent.Executors

class GameModeActivity : CollapsingToolbarBaseActivity() {

    companion object {
        private const val TAG = "GameModeActivity"
    }

    private val executor = Executors.newSingleThreadExecutor()

    private var errorDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                GameModeFragment(),
                TAG,
            )
            .commit()

        startService(Intent(this, GameModeService::class.java))

        checkTouchFeature()
    }

    override fun onDestroy() {
        errorDialog?.dismiss()
        errorDialog = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun checkTouchFeature() {
        executor.execute {
            val available = TouchFeatureManager.isAvailable()
            runOnUiThread {
                if (!available && !isFinishing && !isDestroyed) {
                    showTouchFeatureError()
                }
            }
        }
    }

    private fun showTouchFeatureError() {
        if (errorDialog?.isShowing == true) {
            return
        }
        errorDialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.touchfeature_error_title)
                .setMessage(R.string.touchfeature_error_message)
                .setPositiveButton(R.string.retry) { _, _ -> checkTouchFeature() }
                .setNegativeButton(R.string.exit) { _, _ -> finish() }
                .setOnDismissListener { errorDialog = null }
                .show()
    }
}
