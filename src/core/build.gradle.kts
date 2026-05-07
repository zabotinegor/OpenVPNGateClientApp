import groovy.json.JsonSlurper
import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

fun loadLocalServersConfig(): Map<String, String> {
    // Optional local JSON config, not committed to VCS.
    // Expected format:
    // {
    //   "PRIMARY_SERVERS_URL": "https://...",
    //   "FALLBACK_SERVERS_URL": "https://..."
    // }
    val candidates = listOfNotNull(
        // For builds started from src/ (current Gradle root)
        rootProject.file("servers.local.json"),
        // For file placed in repo root (parent of src/)
        rootProject.rootDir.parentFile?.resolve("servers.local.json")
    )

    val file = candidates.firstOrNull { it.exists() } ?: return emptyMap()

    return try {
        val allowedKeys = setOf("PRIMARY_SERVERS_URL", "FALLBACK_SERVERS_URL", "PRIMARY_SERVERS_V2_URL")
        @Suppress("UNCHECKED_CAST")
        val parsed = JsonSlurper().parse(file) as? Map<String, Any?> ?: emptyMap()
        parsed.mapNotNull { (k, v) ->
            if (k in allowedKeys && v is String && v.isNotBlank()) k to v else null
        }.toMap()
    } catch (e: Exception) {
        project.logger.warn("Could not parse servers.local.json: ${e.message}")
        emptyMap()
    }
}

fun isPlaceholderServerUrl(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return false
    val host = runCatching { URI(normalized).host?.lowercase() }.getOrNull()
    return host == "placeholder"
}

fun firstUsableServerUrl(vararg candidates: String?): String? {
    return candidates
        .mapNotNull { it?.trim() }
        .firstOrNull { url ->
            if (url.isBlank()) return@firstOrNull false
            if (isPlaceholderServerUrl(url)) return@firstOrNull false
            val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "https") error(
                "Server URL must use HTTPS scheme, got: $url"
            )
            true
        }
}

android {
    namespace = "${rootProject.extra.get("basePackageName")}.core"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24
        missingDimensionStrategy("version", "full")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        resValue("string", "app_name", rootProject.extra.get("appName") as String)

        val localConfig = loadLocalServersConfig()

        val primaryServersUrl: String? = firstUsableServerUrl(
            project.findProperty("PRIMARY_SERVERS_URL") as String?,
            System.getenv("PRIMARY_SERVERS_URL"),
            localConfig["PRIMARY_SERVERS_URL"]
        )
        val fallbackServersUrl: String? = firstUsableServerUrl(
            project.findProperty("FALLBACK_SERVERS_URL") as String?,
            System.getenv("FALLBACK_SERVERS_URL"),
            localConfig["FALLBACK_SERVERS_URL"]
        )
        val primaryServersV2Url: String? = firstUsableServerUrl(
            project.findProperty("PRIMARY_SERVERS_V2_URL") as String?,
            System.getenv("PRIMARY_SERVERS_V2_URL"),
            localConfig["PRIMARY_SERVERS_V2_URL"]
        )
        val appReleaseType: String =
            ((project.findProperty("appReleaseType") as String?)
                ?: System.getenv("APP_RELEASE_TYPE")
                ?: "release")
                .trim()
                .lowercase()

        require(!primaryServersUrl.isNullOrBlank()) {
            "PRIMARY_SERVERS_URL is not set (or is placeholder). Provide it via Gradle property PRIMARY_SERVERS_URL, env var PRIMARY_SERVERS_URL, or servers.local.json."
        }
        require(!fallbackServersUrl.isNullOrBlank()) {
            "FALLBACK_SERVERS_URL is not set (or is placeholder). Provide it via Gradle property FALLBACK_SERVERS_URL, env var FALLBACK_SERVERS_URL, or servers.local.json."
        }
        require(!primaryServersV2Url.isNullOrBlank()) {
            "PRIMARY_SERVERS_V2_URL is not set (or is placeholder). Provide it via Gradle property PRIMARY_SERVERS_V2_URL, env var PRIMARY_SERVERS_V2_URL, or servers.local.json."
        }
        require(appReleaseType == "release" || appReleaseType == "beta") {
            "APP_RELEASE_TYPE/appReleaseType must be either 'release' or 'beta'."
        }

        buildConfigField("String", "PRIMARY_SERVERS_URL", "\"$primaryServersUrl\"")
        buildConfigField("String", "FALLBACK_SERVERS_URL", "\"$fallbackServersUrl\"")
        buildConfigField("String", "PRIMARY_SERVERS_V2_URL", "\"$primaryServersV2Url\"")
        buildConfigField("String", "APP_RELEASE_TYPE", "\"$appReleaseType\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.material)
    implementation(project(":openVpnEngine"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.android.gif.drawable)
    implementation(libs.koin.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.timber)
    implementation(libs.commonmark)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
