package com.yahorzabotsin.openvpnclient.core

import android.app.ActivityManager
import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import com.yahorzabotsin.openvpnclient.vpn.OpenVpnService
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.GlobalPreferences

class CoreApp : Application() {
    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclient.core.logging.LogTags.APP + ':' + "CoreApp"
    }

    override fun onCreate() {
        super.onCreate()
        installGlobalExceptionHandler()
        GlobalPreferences.setInstance(false, false, false)
        UserSettingsStore.applyThemeAndLocale(this)
        if (isMainProcess()) {
            if (!isTelevision()) {
                startService(Intent(this, OpenVpnService::class.java))
            }
        }
    }

    private fun isMainProcess(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return packageName == Application.getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(ActivityManager::class.java)
        val name = am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return name == packageName
    }

    private fun isTelevision(): Boolean {
        val uiModeManager = getSystemService(UiModeManager::class.java)
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun installGlobalExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
