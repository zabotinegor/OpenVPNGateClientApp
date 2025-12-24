// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

val basePackageName by extra("com.yahorzabotsin.openvpnclientgate")
val appName by extra("Client for OpenVPN Gate")

// Show test results in console across all subprojects
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events(
                org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
            )
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }

        addTestListener(object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {}
            override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {}
            override fun afterTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {}
            override fun afterSuite(suite: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
                if (suite.parent == null) {
                    val total = result.testCount
                    val failed = result.failedTestCount
                    val skipped = result.skippedTestCount
                    val passed = total - failed - skipped
                    println("\nResults for ${project.path}:${name}")
                    println("+--------+--------+--------+---------+")
                    println("| Total  | Passed | Failed | Skipped |")
                    println("+--------+--------+--------+---------+")
                    println(String.format("| %6d | %6d | %6d | %7d |", total, passed, failed, skipped))
                    println("+--------+--------+--------+---------+")
                }
            }
        })
    }
}

tasks.register("assembleDebugApp") {
    dependsOn(":mobile:assembleDebug")
}

tasks.register("assembleReleaseApp") {
    dependsOn(":mobile:assembleRelease")
}

tasks.register("bundleReleaseApp") {
    dependsOn(":mobile:bundleRelease")
}

tasks.register("testDebugUnitTestApp") {
    dependsOn(":core:testDebugUnitTest", ":mobile:testDebugUnitTest")
}

tasks.register<Copy>("stageReleaseArtifacts") {
    dependsOn("assembleReleaseApp", "bundleReleaseApp")
    val mobileBuildDir = project(":mobile").layout.buildDirectory
    from(mobileBuildDir.dir("outputs/apk/release")) {
        include("*.apk")
    }
    from(mobileBuildDir.dir("outputs/bundle/release")) {
        include("*.aab")
    }
    into(layout.buildDirectory.dir("staged"))
}
