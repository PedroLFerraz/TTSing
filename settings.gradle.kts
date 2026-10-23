pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The AnkiDroid API is published only on JitPack. FAIL_ON_PROJECT_REPOS above
        // means it has to be declared here rather than in app/build.gradle.kts.
        maven("https://jitpack.io")
        // sherpa-onnx publishes its JVM jars (for the desktop app) only as GitHub release assets.
        ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
            patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "TTSing"
include(":app")
include(":core")
include(":desktop")
