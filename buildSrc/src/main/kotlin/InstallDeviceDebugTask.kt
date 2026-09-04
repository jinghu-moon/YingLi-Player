import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class InstallDeviceDebugTask : DefaultTask() {
    @get:Internal
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val adbExecutable: Property<String>

    @get:Optional
    @get:Input
    abstract val deviceSerial: Property<String>

    @TaskAction
    fun install() {
        val connectedDevices = runAdb("devices")
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                columns.takeIf { it.size >= 2 && it[1] == "device" }?.first()
            }
            .toList()
        val configuredSerial = deviceSerial.orNull
        val serial = configuredSerial ?: when (connectedDevices.size) {
            0 -> throw GradleException(
                "No authorized Android device found. Connect a device and enable USB debugging.",
            )
            1 -> connectedDevices.single()
            else -> throw GradleException(
                "Multiple Android devices found (${connectedDevices.joinToString()}). " +
                    "Choose one with -PdeviceSerial=<serial>.",
            )
        }
        if (configuredSerial != null && configuredSerial !in connectedDevices) {
            throw GradleException(
                "Device '$configuredSerial' is not connected and authorized. " +
                    "Available: ${connectedDevices.joinToString()}",
            )
        }

        val abi = runAdb("-s", serial, "shell", "getprop", "ro.product.cpu.abi")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it in SUPPORTED_ABIS }
            ?: throw GradleException("Unsupported or unavailable device ABI for '$serial'.")
        val apk = apkDirectory.file("app-$abi-debug.apk").get().asFile
        if (!apk.isFile) {
            throw GradleException("Matching APK was not produced: ${apk.absolutePath}")
        }

        logger.lifecycle("Installing ${apk.name} to $serial ($abi)")
        runAdb("-s", serial, "install", "-r", "-d", apk.absolutePath)
        logger.lifecycle("Installed successfully to $serial")
    }

    private fun runAdb(vararg arguments: String): String {
        val process = ProcessBuilder(listOf(adbExecutable.get()) + arguments.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("ADB command failed: adb ${arguments.joinToString(" ")}\n$output")
        }
        return output
    }

    private companion object {
        val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
}
