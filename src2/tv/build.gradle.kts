plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "${rootProject.extra.get("basePackageName")}.tv"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "${rootProject.extra.get("basePackageName")}.tv"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        resValue("string", "app_name", rootProject.extra.get("appName") as String)
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
}

apply(from = "../copy_drawables.gradle.kts")
