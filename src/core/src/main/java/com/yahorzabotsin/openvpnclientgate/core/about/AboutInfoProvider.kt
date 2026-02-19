package com.yahorzabotsin.openvpnclientgate.core.about

import android.content.Context

data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val engineName: String,
    val engineLicense: String,
    val copyrightOwner: String,
    val year: Int
)

interface AboutInfoProvider {
    fun load(): AboutInfo
}

class DefaultAboutInfoProvider(
    private val context: Context,
    private val yearProvider: YearProvider
) : AboutInfoProvider {
    override fun load(): AboutInfo {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = pInfo.versionName ?: ""
        val versionCode =
            if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else pInfo.versionCode.toLong()
        return AboutInfo(
            versionName = versionName,
            versionCode = versionCode,
            packageName = context.packageName,
            engineName = AboutMeta.ENGINE_NAME,
            engineLicense = AboutMeta.ENGINE_LICENSE,
            copyrightOwner = AboutMeta.COPYRIGHT_OWNER,
            year = yearProvider.currentYear()
        )
    }
}
