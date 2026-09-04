import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateChecksumsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val artifacts = inputDirectory.get().asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "apk" }
            .sortedBy { it.name }
        check(artifacts.isNotEmpty()) { "No release APKs found for checksum generation." }
        val content = artifacts.joinToString("\n", postfix = "\n") { artifact ->
            "${artifact.sha256()}  ${artifact.name}"
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun java.io.File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
