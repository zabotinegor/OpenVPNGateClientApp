package com.yahorzabotsin.openvpnclientgate.core

import android.app.ActivityManager
import android.app.Application
import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.GlobalPreferences

class CoreApp : Application() {
    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "CoreApp"
    }

    override fun onCreate() {
        super.onCreate()
        installGlobalExceptionHandler()
        GlobalPreferences.setInstance(false, false, false)
        UserSettingsStore.applyThemeAndLocale(this)
        if (isMainProcess()) {
            Log.d(TAG, "Skipping OpenVpnService auto-start in Application")
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

    private fun installGlobalExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}

