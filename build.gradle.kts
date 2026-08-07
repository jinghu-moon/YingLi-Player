import org.gradle.util.GradleVersion

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

apply(from = "wrapper.gradle.kts")

val requiredJdk = "21"
if (JavaVersion.current().majorVersion != requiredJdk) {
    throw GradleException(
        "YingLi requires JDK $requiredJdk, but Gradle is running on JDK ${JavaVersion.current().majorVersion}. " +
            "Set JAVA_HOME to a JDK 21 installation.",
    )
}

val requiredGradle = "9.5.0"
if (GradleVersion.current() != GradleVersion.version(requiredGradle)) {
    throw GradleException(
        "YingLi requires Gradle $requiredGradle, but ${GradleVersion.current().version} is running. " +
            "Use the checked-in Gradle Wrapper.",
    )
}

val verifyBuildBaseline by tasks.registering(BuildBaselineTask::class) {
    group = "verification"
    description = "Checks the frozen toolchain and stable dependency policy."
    catalogFile.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
    moduleBuildScripts.from(fileTree(layout.projectDirectory) {
        include("*/build.gradle.kts")
        exclude("**/build/**")
    })
    rootDirectory.set(layout.projectDirectory)
}

subprojects {
    tasks.configureEach {
        if (name in setOf("preBuild", "testDebugUnitTest", "lintDebug")) {
            dependsOn(rootProject.tasks.named("verifyBuildBaseline"))
        }
    }
}
