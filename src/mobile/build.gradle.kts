import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "${rootProject.extra.get("basePackageName")}.mobile"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.isFile) {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    defaultConfig {
        applicationId = "${rootProject.extra.get("basePackageName")}"
        minSdk = 24
        targetSdk = 36
        val versionCodeOverride = (project.findProperty("appVersionCodeMobile") as String?)
            ?: (project.findProperty("appVersionCode") as String?)
        versionCode = (versionCodeOverride ?: "1").toInt()
        versionName = project.findProperty("appVersionName") as String? ?: "1.0"
        missingDimensionStrategy("version", "full")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val appName = rootProject.extra.get("appName") as String
        resValue("string", "app_name", appName)
        // Title for engine notification is defined in core as "%1$s"; avoid duplication
        resValue("string", "channel_name_background", appName)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    lint {
        checkReleaseBuilds = false
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.contrib)
}

apply(from = "../copy_drawables.gradle.kts")
