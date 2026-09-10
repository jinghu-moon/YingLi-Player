package seeyuer.yingli.player.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureRulesTest {
    private val sourceRoot = File("src/main/java")
    private val sources = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map(::parseSource)
        .toList()

    @Test
    fun `dependencies follow the current app feature implementation domain core order`() {
        val violations = sources.flatMap { source ->
            val sourceLayer = layerOf(source.packageName) ?: return@flatMap emptyList()
            source.imports.mapNotNull { imported ->
                val targetLayer = layerOf(imported) ?: return@mapNotNull null
                if (sourceLayer < targetLayer) {
                    "${source.relativePath} (${sourceLayer.name}) imports $imported (${targetLayer.name})"
                } else {
                    null
                }
            }
        }

        assertTrue("Illegal dependency direction:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `player feature depends on contracts instead of implementations`() {
        val forbiddenPrefixes = listOf(
            "$ROOT_PACKAGE.data.",
            "$ROOT_PACKAGE.engine.",
            "androidx.media3.",
        )
        val violations = sources
            .filter { it.packageName.startsWith("$ROOT_PACKAGE.feature.player") }
            .flatMap { source ->
                source.imports
                    .filter { imported -> forbiddenPrefixes.any(imported::startsWith) }
                    .map { imported -> "${source.relativePath} imports $imported" }
            }

        assertTrue(
            "Player feature bypasses playback contracts:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `ui does not access dao files or exoplayer directly`() {
        val forbiddenPrefixes = listOf(
            "androidx.room",
            "androidx.media3.exoplayer",
            "java.io",
            "java.nio.file",
        )
        val violations = sources
            .filter { it.packageName.startsWith("$ROOT_PACKAGE.feature.") }
            .flatMap { source ->
                source.imports
                    .filter { imported -> forbiddenPrefixes.any(imported::startsWith) }
                    .map { imported -> "${source.relativePath} imports $imported" }
            }

        assertTrue("UI bypasses its ViewModel/repository boundary:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `repositories do not depend on compose`() {
        val violations = sources
            .filter { source ->
                source.relativePath.substringAfterLast('/').contains("Repository") ||
                    source.packageName.contains(".repository")
            }
            .filter { source -> source.imports.any { it.startsWith("androidx.compose") } }
            .map(SourceFile::relativePath)

        assertTrue("Repositories import Compose:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `production code injects dispatchers and clocks`() {
        val exemptions = setOf("AppDispatchers.kt", "AppClock.kt")
        val forbiddenCalls = listOf(
            "Dispatchers.Main",
            "Dispatchers.IO",
            "Dispatchers.Default",
            "Instant.now()",
            "System.currentTimeMillis()",
        )
        val violations = sources
            .filterNot { it.file.name in exemptions }
            .flatMap { source ->
                forbiddenCalls
                    .filter(source.text::contains)
                    .map { call -> "${source.relativePath} uses $call" }
            }

        assertTrue("Inject AppDispatchers/AppClock instead:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `test substitutes cannot be packaged in production`() {
        val violations = sources.filter { source ->
            source.packageName.contains(".testing") ||
                source.imports.any { imported ->
                    imported.startsWith("org.junit") || imported.startsWith("kotlinx.coroutines.test")
                }
        }

        assertTrue(
            "Test-only code found in src/main:\n${violations.joinToString("\n") { it.relativePath }}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `internal package dependency graph is acyclic`() {
        val graph = sources
            .groupBy(SourceFile::packageName)
            .mapValues { (packageName, packageSources) ->
                packageSources.flatMap(SourceFile::imports)
                    .mapNotNull(::ownedPackage)
                    .filterNot { it == packageName }
                    .filter { dependency -> layerOf(packageName) != layerOf(dependency) }
                    .toSet()
            }
        val cycles = mutableListOf<String>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(node: String, path: List<String>) {
            if (node in visiting) {
                cycles += (path + node).joinToString(" -> ")
                return
            }
            if (!visited.add(node)) return
            visiting += node
            graph[node].orEmpty().forEach { dependency -> visit(dependency, path + node) }
            visiting -= node
        }

        graph.keys.forEach { visit(it, emptyList()) }
        assertTrue("Package cycles detected:\n${cycles.joinToString("\n")}", cycles.isEmpty())
    }

    private fun parseSource(file: File): SourceFile {
        val text = file.readText()
        val packageName = PACKAGE_PATTERN.find(text)?.groupValues?.get(1).orEmpty()
        val imports = IMPORT_PATTERN.findAll(text).map { it.groupValues[1] }.toList()
        return SourceFile(
            file = file,
            relativePath = file.relativeTo(sourceRoot).invariantSeparatorsPath,
            packageName = packageName,
            imports = imports,
            text = text,
        )
    }

    private fun layerOf(packageName: String): Layer? {
        if (!packageName.startsWith(ROOT_PACKAGE)) return null
        return when (packageName.removePrefix("$ROOT_PACKAGE.").substringBefore('.')) {
            "core" -> Layer.CORE
            "domain" -> Layer.DOMAIN
            "data", "engine" -> Layer.IMPLEMENTATION
            "feature" -> Layer.FEATURE
            "app" -> Layer.APP
            else -> null
        }
    }

    private fun ownedPackage(importedName: String): String? =
        sources.map(SourceFile::packageName)
            .filter(importedName::startsWith)
            .maxByOrNull(String::length)

    private data class SourceFile(
        val file: File,
        val relativePath: String,
        val packageName: String,
        val imports: List<String>,
        val text: String,
    )

    private enum class Layer {
        CORE,
        DOMAIN,
        IMPLEMENTATION,
        FEATURE,
        APP,
    }

    private companion object {
        const val ROOT_PACKAGE = "seeyuer.yingli.player"
        val PACKAGE_PATTERN = Regex("(?m)^package\\s+([A-Za-z0-9_.]+)")
        val IMPORT_PATTERN = Regex("(?m)^import\\s+([A-Za-z0-9_.]+)")
    }
}
