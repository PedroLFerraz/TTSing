import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// TTSing for the Linux desktop: the library, the reader and the neural voices, on :core.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/*
 * PdfBook and PdfExtract use nothing of Android but PDFBox, whose Android port keeps Apache
 * PDFBox's API under another package name. They are built here from the Android app's own
 * files with the package swapped, so there is one copy to fix; PdfExtractTest comes along to
 * show the two PDFBoxes read the same.
 */
val pdfSources = rootProject.file("app/src/main/java/com/pedrolopes/ttsing/data/pdf")
val pdfTests = rootProject.file("app/src/test/java/com/pedrolopes/ttsing/data/pdf")
val sharedPdfSources = tasks.register<Sync>("sharedPdfSources") {
    from(pdfSources) { include("PdfBook.kt", "PdfExtract.kt") }
    into(layout.buildDirectory.dir("generated/pdf/main/com/pedrolopes/ttsing/data/pdf"))
    filter { line: String -> line.replace("com.tom_roush.pdfbox", "org.apache.pdfbox") }
}
val sharedPdfTests = tasks.register<Sync>("sharedPdfTests") {
    from(pdfTests) { include("PdfExtractTest.kt") }
    into(layout.buildDirectory.dir("generated/pdf/test/com/pedrolopes/ttsing/data/pdf"))
    filter { line: String -> line.replace("com.tom_roush.pdfbox", "org.apache.pdfbox") }
}

/** The three typefaces, from the Android app's resources, and the icon: one copy of each. */
val sharedResources = tasks.register<Sync>("sharedResources") {
    from(rootProject.file("app/src/main/res/font")) { into("fonts") }
    from(rootProject.file("docs/brand/icon.svg"))
    into(layout.buildDirectory.dir("generated/resources"))
}

sourceSets {
    main {
        kotlin.srcDir(layout.buildDirectory.dir("generated/pdf/main"))
        resources.srcDir(sharedResources)
    }
    test {
        kotlin.srcDir(layout.buildDirectory.dir("generated/pdf/test"))
        resources.srcDir(rootProject.file("core/src/test/resources"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("compileKotlin") { dependsOn(sharedPdfSources) }
tasks.named("compileTestKotlin") { dependsOn(sharedPdfTests) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pdfbox)
    implementation(libs.sherpa.onnx.jvm)
    runtimeOnly(libs.sherpa.onnx.native.linux.x64)

    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "2g"
    // PiperEngineCheck speaks with a real voice: -PpiperVoices=<folder of voices>.
    (project.findProperty("piperVoices") as String?)?.let { systemProperty("piperVoices", it) }
    testLogging.showStandardStreams = true
}

compose.desktop {
    application {
        mainClass = "com.pedrolopes.ttsing.desktop.MainKt"
        // Lets Main name the window's X11 class (see nameWindowClass).
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "ttsing"
            packageVersion = "0.1.0"
            description = "Reads your EPUBs and PDFs aloud, highlighting every word as it's spoken"
            vendor = "PedroLFerraz"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "jdk.unsupported")
        }
    }
}
