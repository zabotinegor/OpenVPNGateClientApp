import java.io.File

val imageMappings = mapOf(
    "appicon.png" to "drawable/appicon.png",
    "logo_with_text_1536x1024.png" to "drawable-nodpi/banner.png"
)

tasks.register("copyAndRenameDrawables") {
    doLast {
        val coreLogoDir = File(project.projectDir, "../core/src/main/logo")
        if (!coreLogoDir.exists()) {
            println("Source logo directory not found: ${coreLogoDir.absolutePath}")
            return@doLast
        }

        imageMappings.forEach { (sourceName, destination) ->
            val sourceFile = File(coreLogoDir, sourceName)
            if (sourceFile.exists()) {
                val destinationFile = File(project.projectDir, "src/main/res/$destination")
                destinationFile.parentFile.mkdirs()
                sourceFile.copyTo(destinationFile, overwrite = true)
                println("Copied $sourceName to $destinationFile")
            } else {
                println("Source file not found: $sourceFile")
            }
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(tasks.named("copyAndRenameDrawables"))
    }
}
