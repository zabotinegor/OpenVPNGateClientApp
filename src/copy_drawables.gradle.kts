import java.io.File

tasks.register("copyAndRenameDrawables") {
    doLast {
        val repoRoot = rootProject.rootDir.parentFile
        val sourceDirs = listOf(
            File(repoRoot, "media/Logos"),
            File(repoRoot, "media/Logo")
        )
        val sourceDir = sourceDirs.firstOrNull { it.exists() }

        if (sourceDir == null) {
            println(
                "Source media directory not found. Make sure submodule 'media' is checked out:\n" +
                        "  git submodule update --init --recursive"
            )
            return@doLast
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
                .map { File(sourceDir, it) }
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

        if (missing) {
            throw GradleException(
                "Required media files are missing. Ensure the 'media' submodule is cloned and contains appicon_GP_512x512.png (or appicon.png) and appbanner_GP_1280x720.png (or appdesc_GP_1024x500.png/logo_with_text_1536x1024.png)."
            )
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(tasks.named("copyAndRenameDrawables"))
    }
}
