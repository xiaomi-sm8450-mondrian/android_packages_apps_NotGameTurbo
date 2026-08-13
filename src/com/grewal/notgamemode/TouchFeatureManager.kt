/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grewal.notgamemode

import android.os.Binder
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import vendor.lineage.touch.IHighTouchPollingRate
import vendor.xiaomi.hw.touchfeature.ITouchFeature

object TouchFeatureManager {

    private const val TAG = "GameModeTouchFeature"

    private const val TOUCH_ID = 0
    private const val TOUCH_GAME_MODE = 0
    private const val TOUCH_SUPER_REPORT = 202
    private const val TOUCH_PANEL_ORIENTATION = 8

    const val TOUCH_UP_THRESHOLD = 2
    const val TOUCH_TOLERANCE = 3
    const val TOUCH_AIM_SENSITIVITY = 4
    const val TOUCH_TAP_STABILITY = 5
    const val TOUCH_EXPERT_MODE = 6
    const val TOUCH_EDGE_FILTER = 7

    data class ModeRange(val min: Int, val max: Int, val def: Int)

    val TUNING_RANGES =
        linkedMapOf(
            TOUCH_UP_THRESHOLD to ModeRange(0, 4, 2),
            TOUCH_TOLERANCE to ModeRange(0, 4, 2),
            TOUCH_AIM_SENSITIVITY to ModeRange(0, 4, 2),
            TOUCH_TAP_STABILITY to ModeRange(0, 4, 2),
            TOUCH_EDGE_FILTER to ModeRange(0, 3, 2),
        )

    val EXPERT_RANGE = ModeRange(1, 3, 1)

    @Volatile private var touchFeature: ITouchFeature? = null

    @Volatile private var pollingRate: IHighTouchPollingRate? = null

    private val deathRecipient =
        IBinder.DeathRecipient {
            Log.w(TAG, "touchfeature service died")
            touchFeature = null
        }

    private val pollingRateDeathRecipient =
        IBinder.DeathRecipient {
            Log.w(TAG, "high touch polling rate service died")
            pollingRate = null
        }

    // The declared guard is load-bearing: allowBlocking(null) returns null rather than throwing, so
    // without it asInterface(null).apply { asBinder() } NPEs inside runCatching on every call.
    @Synchronized
    private fun getService(): ITouchFeature? {
        touchFeature?.let {
            return it
        }
        if (!isTouchFeatureDeclared()) {
            return null
        }
        return runCatching {
                val fqName = "${ITouchFeature.DESCRIPTOR}/default"
                val binder = Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName))
                ITouchFeature.Stub.asInterface(binder).apply {
                    asBinder().linkToDeath(deathRecipient, 0)
                }
            }
            .onSuccess { touchFeature = it }
            .onFailure { e -> Log.e(TAG, "failed to get touchfeature service", e) }
            .getOrNull()
    }

    @Synchronized
    private fun getPollingRateService(): IHighTouchPollingRate? {
        pollingRate?.let {
            return it
        }
        if (!isPollingRateDeclared()) {
            return null
        }
        return runCatching {
                val fqName = "${IHighTouchPollingRate.DESCRIPTOR}/default"
                val binder = Binder.allowBlocking(ServiceManager.waitForDeclaredService(fqName))
                IHighTouchPollingRate.Stub.asInterface(binder).apply {
                    asBinder().linkToDeath(pollingRateDeathRecipient, 0)
                }
            }
            .onSuccess { pollingRate = it }
            .onFailure { e -> Log.e(TAG, "failed to get polling rate service", e) }
            .getOrNull()
    }

    // Not @Synchronized: the getters hold the object monitor across waitForDeclaredService(), so
    // sharing it here would let a main-thread gating call block behind a slow HAL bind.
    private val touchFeatureDeclared: Boolean by lazy { queryDeclared(ITouchFeature.DESCRIPTOR) }

    private val pollingRateDeclared: Boolean by lazy {
        queryDeclared(IHighTouchPollingRate.DESCRIPTOR)
    }

    // Logged here rather than at each call site: by lazy makes this run once per interface, while
    // the getters run on every poll.
    private fun queryDeclared(descriptor: String): Boolean {
        val fqName = "$descriptor/default"
        val declared = runCatching { ServiceManager.isDeclared(fqName) }.getOrDefault(false)
        Log.i(TAG, "$fqName declared: $declared")
        return declared
    }

    fun isTouchFeatureDeclared(): Boolean = touchFeatureDeclared

    fun isPollingRateDeclared(): Boolean = pollingRateDeclared

    /** Binds both services, so this blocks. Call it off the main thread. */
    fun isAvailable(): Boolean = getService() != null || getPollingRateService() != null

    private fun setModeValue(mode: Int, value: Int) {
        // GameModeService polls and TouchOrientationService fires on every rotation, so short
        // circuit before the getService() monitor on targets that declare no touchfeature HAL at
        // all, rather than logging the miss on every call.
        if (!isTouchFeatureDeclared()) {
            return
        }
        val service =
            getService()
                ?: run {
                    Log.d(TAG, "no touchfeature service, cannot set mode $mode")
                    return
                }
        runCatching { service.setTouchMode(TOUCH_ID, mode, value) }
            .onFailure { e -> Log.e(TAG, "setModeValue(mode=$mode, value=$value) failed", e) }
    }

    fun setGameMode(enabled: Boolean) {
        Log.i(TAG, "setGameMode: $enabled")
        setModeValue(TOUCH_GAME_MODE, if (enabled) 1 else 0)
    }

    fun setSuperReport(enabled: Boolean) {
        Log.i(TAG, "setSuperReport: $enabled")
        val service = getPollingRateService()
        if (service == null) {
            setModeValue(TOUCH_SUPER_REPORT, if (enabled) 1 else 0)
            return
        }
        runCatching { service.setEnabled(enabled) }
            .onFailure { e -> Log.e(TAG, "setEnabled(enabled=$enabled) failed", e) }
    }

    fun setPanelOrientation(rotation: Int) {
        Log.i(TAG, "setPanelOrientation: $rotation")
        setModeValue(TOUCH_PANEL_ORIENTATION, rotation)
    }

    fun setTuning(mode: Int, value: Int) = setModeValue(mode, value)

    data class ModeQuery(
        val cur: Int?,
        val def: Int?,
        val min: Int?,
        val max: Int?,
        val values: Int?,
    )

    fun queryPollingRate(): Boolean? =
        getPollingRateService()?.let { runCatching { it.getEnabled() }.getOrNull() }

    fun queryMode(mode: Int): ModeQuery {
        val service = getService()
        fun <T> attempt(block: (ITouchFeature) -> T): T? =
            service?.let { runCatching { block(it) }.getOrNull() }
        return ModeQuery(
            cur = attempt { it.getTouchModeCurValue(TOUCH_ID, mode) },
            def = attempt { it.getTouchModeDefValue(TOUCH_ID, mode) },
            min = attempt { it.getTouchModeMinValue(TOUCH_ID, mode) },
            max = attempt { it.getTouchModeMaxValue(TOUCH_ID, mode) },
            values = attempt { it.getModeValues(TOUCH_ID, mode) },
        )
    }
}
