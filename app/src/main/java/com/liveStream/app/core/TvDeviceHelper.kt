package com.liveStream.app.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object TvDeviceHelper {
    private const val PREFS_NAME = "live_prefs"
    private const val KEY_FORCE_TV_MODE = "force_tv_mode"

    fun isTvDevice(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FORCE_TV_MODE, false)) return true

        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        ) {
            return true
        }
        val config = context.resources.configuration
        val isWideLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            config.screenWidthDp >= 900 &&
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return isWideLandscape
    }
}
