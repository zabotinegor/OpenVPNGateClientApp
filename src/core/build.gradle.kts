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
    val text = file.readText()

    fun extract(key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]+)"""")
        return regex.find(text)?.groups?.get(1)?.value
    }

    val result = mutableMapOf<String, String>()
    extract("PRIMARY_SERVERS_URL")?.let { result["PRIMARY_SERVERS_URL"] = it }
    extract("FALLBACK_SERVERS_URL")?.let { result["FALLBACK_SERVERS_URL"] = it }
    return result
}

android {
    namespace = "${rootProject.extra.get("basePackageName")}.core"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        resValue("string", "app_name", rootProject.extra.get("appName") as String)

        val localConfig = loadLocalServersConfig()

        val primaryServersUrl: String? =
            (project.findProperty("PRIMARY_SERVERS_URL") as String?)
                ?: System.getenv("PRIMARY_SERVERS_URL")
                ?: localConfig["PRIMARY_SERVERS_URL"]
        val fallbackServersUrl: String? =
            (project.findProperty("FALLBACK_SERVERS_URL") as String?)
                ?: System.getenv("FALLBACK_SERVERS_URL")
                ?: localConfig["FALLBACK_SERVERS_URL"]

        require(!primaryServersUrl.isNullOrBlank()) {
            "PRIMARY_SERVERS_URL is not set. Provide it via Gradle property PRIMARY_SERVERS_URL or env var PRIMARY_SERVERS_URL."
        }
        require(!fallbackServersUrl.isNullOrBlank()) {
            "FALLBACK_SERVERS_URL is not set. Provide it via Gradle property FALLBACK_SERVERS_URL or env var FALLBACK_SERVERS_URL."
        }

        buildConfigField("String", "PRIMARY_SERVERS_URL", "\"$primaryServersUrl\"")
        buildConfigField("String", "FALLBACK_SERVERS_URL", "\"$fallbackServersUrl\"")
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
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
