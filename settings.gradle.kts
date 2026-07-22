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
    }
}

rootProject.name = "TTSing"
include(":app")
