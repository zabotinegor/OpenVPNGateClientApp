package com.yahorzabotsin.openvpnclientgate.core

import android.app.ActivityManager
import android.app.Application
import com.yahorzabotsin.openvpnclientgate.core.di.coreModule
import com.yahorzabotsin.openvpnclientgate.core.logging.AppDebugTree
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.AppReleaseTree
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.GlobalPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import timber.log.Timber

class CoreApp : Application() {

    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "CoreApp"
    }

    override fun onCreate() {
        super.onCreate()
        initLogging()
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@CoreApp)
                modules(coreModule)
            }
        }
        installGlobalExceptionHandler()
        GlobalPreferences.setInstance(false, false, false)
        UserSettingsStore.applyThemeAndLocale(this)
        if (isMainProcess()) {
            AppLog.d(TAG, "Skipping OpenVpnService auto-start in Application")
        }
    }

    private fun initLogging() {
        if (Timber.forest().isNotEmpty()) return
        val tree = if (BuildConfig.DEBUG) AppDebugTree() else AppReleaseTree()
        Timber.plant(tree)
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
            AppLog.e(TAG, "Uncaught exception in thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}

