import java.io.File

tasks.register("copyAndRenameDrawables") {
    doLast {
        val repoRoot = rootProject.rootDir.parentFile
        val logoSourceDirs = listOf(
            File(repoRoot, "media/Logos"),
            File(repoRoot, "media/Logo")
        )
        val logoSourceDir = logoSourceDirs.firstOrNull { it.exists() }
        val videoSourceDir = File(repoRoot, "media/Video")

        if (logoSourceDir == null) {
            throw GradleException(
                "Source media directory not found. Ensure 'media' submodule is cloned:\n" +
                        "  git submodule update --init --recursive"
            )
        }

        if (!videoSourceDir.exists()) {
            throw GradleException(
                "Source media video directory not found. Ensure 'media' submodule is up to date:\n" +
                        "  git submodule update --init --recursive --remote media"
            )
        }

        // Map destination -> list of candidate source filenames (first existing is used)
        val imageMappings: Map<String, List<String>> = mapOf(
            "drawable/appicon.png" to listOf(
                "appicon_GP_512x512.png",
                "appicon.png"
            ),
            // Prefer 1280x720 banner; fallback to 1024x500 and legacy
            "drawable-nodpi/banner.png" to listOf(
                "appbanner_GP_1280x720.png",
                "appdesc_GP_1024x500.png",
                "logo_with_text_1536x1024.png"
            )
        )

        var missing = false

        imageMappings.forEach { (destination, candidates) ->
            val sourceFile = candidates
                .map { File(logoSourceDir, it) }
                .firstOrNull { it.exists() }

            if (sourceFile != null) {
                val destinationFile = File(project.projectDir, "src/main/res/$destination")
                destinationFile.parentFile.mkdirs()
                sourceFile.copyTo(destinationFile, overwrite = true)
                println("Copied ${sourceFile.name} to $destinationFile")
            } else {
                println("Source files not found for $destination (candidates: ${candidates.joinToString()})")
                missing = true
            }
        }

        val splashGifSourceName = if (project.name.equals("tv", ignoreCase = true)) {
            "Logo text in right.gif"
        } else {
            "Logo text in bottom.gif"
        }
        val splashGifSource = File(videoSourceDir, splashGifSourceName)
        if (splashGifSource.exists()) {
            val splashGifDestination = File(project.projectDir, "src/main/res/raw/splash_intro.gif")
            splashGifDestination.parentFile.mkdirs()
            splashGifSource.copyTo(splashGifDestination, overwrite = true)
            println("Copied ${splashGifSource.name} to $splashGifDestination")
        } else {
            println("Source file not found for splash intro GIF: ${splashGifSource.absolutePath}")
            missing = true
        }

        if (missing) {
            throw GradleException(
                "Required media files are missing. Ensure the 'media' submodule is cloned and updated, then verify icon/banner assets in media/Logo and splash GIF assets in media/Video."
            )
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(tasks.named("copyAndRenameDrawables"))
    }
}
