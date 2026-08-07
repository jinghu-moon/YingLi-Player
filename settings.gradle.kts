pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YingLi-Player"
include(":app")

gradle.beforeProject {
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        throw GradleException(
            "Project $path must not apply org.jetbrains.kotlin.android: " +
                "AGP 9.3.1 provides the built-in Kotlin plugin.",
        )
    }
}
