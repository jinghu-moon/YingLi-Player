import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class BuildBaselineTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleBuildScripts: ConfigurableFileCollection

    @get:org.gradle.api.tasks.Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val versions = readVersions(catalogFile.get().asFile.readText())
        val mismatches = FROZEN_VERSIONS.filter { (name, expected) -> versions[name] != expected }
        if (mismatches.isNotEmpty()) {
            val details = mismatches.entries.joinToString { (name, expected) ->
                "$name must be $expected (found ${versions[name] ?: "missing"})"
            }
            throw GradleException("Version Catalog does not match the accepted technology baseline: $details")
        }

        val unstable = versions.filterValues(::isUnstable)
        if (unstable.isNotEmpty()) {
            throw GradleException("Only stable, fixed dependency versions are allowed: $unstable")
        }

        val offenders = moduleBuildScripts.files.filter { file ->
            file.readText().contains(FORBIDDEN_KOTLIN_PLUGIN)
        }
        if (offenders.isNotEmpty()) {
            val root = rootDirectory.get().asFile
            throw GradleException(
                "$FORBIDDEN_KOTLIN_PLUGIN must not be applied because AGP 9.3.1 provides built-in Kotlin. " +
                    "Found in: ${offenders.joinToString { it.relativeTo(root).invariantSeparatorsPath }}",
            )
        }
    }

    private fun readVersions(catalogText: String): Map<String, String> = buildMap {
        var inVersions = false
        catalogText.lineSequence().forEach { line ->
            when {
                line.trim() == "[versions]" -> inVersions = true
                line.trim().startsWith("[") -> inVersions = false
                inVersions -> VERSION_ENTRY.find(line)
                    ?.destructured
                    ?.let { (name, version) -> put(name, version) }
            }
        }
    }

    private fun isUnstable(version: String): Boolean =
        UNSTABLE_PATTERN.containsMatchIn(version) ||
            version.contains('+') ||
            version.equals("latest.release", ignoreCase = true)

    private companion object {
        const val FORBIDDEN_KOTLIN_PLUGIN = "org.jetbrains.kotlin.android"
        val VERSION_ENTRY = Regex("""^\s*([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""")
        val UNSTABLE_PATTERN = Regex("(?i)(alpha|beta|rc|snapshot|preview|eap|dev)")
        val FROZEN_VERSIONS = mapOf(
            "agp" to "9.3.1",
            "kotlin" to "2.4.0",
            "compose-bom" to "2026.06.01",
            "activity-compose" to "1.13.0",
            "lifecycle" to "2.10.0",
            "navigation-compose" to "2.9.8",
            "media3" to "1.10.1",
            "room" to "2.8.4",
            "ksp" to "2.3.2",
            "datastore" to "1.2.1",
            "coil" to "3.5.0",
            "coroutines" to "1.11.0",
            "documentfile" to "1.1.0",
            "material3-window" to "1.4.0",
            "tabler-icons" to "0.1.0-local.1",
        )
    }
}
