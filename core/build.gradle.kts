import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything TTSing does that needs no Android: EPUB parsing, PDF reflow, sentences and words,
// the reading position, voice choice and timing. The Android app and the Linux desktop app
// both build on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.jsoup)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "2g"
}
