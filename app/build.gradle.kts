import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pedrolopes.ttsing"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pedrolopes.ttsing"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The sherpa-onnx AAR ships prebuilt native libraries for every ABI (~46 MB total).
        // Restrict to the two that cover essentially all real phones to keep the APK sane.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // PDFBox-Android builds clip paths with android.graphics.Path, which text extraction
        // never reads; the PDF tests need those calls to be no-ops rather than errors.
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // PdfCorpusDump reads a folder of real PDFs: -PpdfCorpus=<folder>.
            (project.findProperty("pdfCorpus") as String?)?.let { test.systemProperty("pdfCorpus", it) }
            (project.findProperty("pdfCorpusOut") as String?)?.let { test.systemProperty("pdfCorpusOut", it) }
            (project.findProperty("pdfRawPages") as String?)?.let { test.systemProperty("pdfRawPages", it) }
            test.maxHeapSize = "2g"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ankidroid.api)
    implementation(libs.sherpa.onnx)
    implementation(libs.pdfbox.android)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * PDFBox-Android keeps its glyph lists and font metrics in the AAR's assets, which a JVM unit
 * test cannot see; unpacked onto the test classpath, the PDF tests run the real extraction.
 */
val pdfboxAar: Configuration by configurations.creating
dependencies { pdfboxAar(libs.pdfbox.android) { isTransitive = false; artifact { type = "aar" } } }
val unpackPdfboxAssets = tasks.register<Sync>("unpackPdfboxAssets") {
    from(pdfboxAar.elements.map { aars -> aars.map { zipTree(it.asFile) } }) {
        include("assets/**")
        eachFile { path = path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("pdfbox-test-resources"))
}
android.sourceSets.getByName("test").resources.srcDir(layout.buildDirectory.dir("pdfbox-test-resources"))
tasks.matching { it.name.endsWith("UnitTestJavaRes") }.configureEach { dependsOn(unpackPdfboxAssets) }

/**
 * Downloads the neural voices the app ships with. They are model files, not source, so they
 * live outside git: run `gradlew fetchPiperVoices` once on a fresh clone. Without them the
 * app still builds and runs — it simply offers no built-in neural voice and falls back to
 * the device's own TTS engine.
 */
val piperVoices = listOf("vits-piper-pt_BR-faber-medium")

tasks.register("fetchPiperVoices") {
    description = "Downloads the bundled Piper voices into src/main/assets/piper."
    group = "build setup"
    val assets = layout.projectDirectory.dir("src/main/assets/piper").asFile
    outputs.dir(assets)
    doLast {
        piperVoices.forEach { voice ->
            val target = File(assets, voice)
            if (File(target, "tokens.txt").exists()) {
                logger.lifecycle("$voice already present")
                return@forEach
            }
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$voice.tar.bz2"
            val archive = File.createTempFile(voice, ".tar.bz2")
            logger.lifecycle("Downloading $url")
            uri(url).toURL().openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            assets.mkdirs()
            exec { commandLine("tar", "-xjf", archive.absolutePath, "-C", assets.absolutePath) }
            archive.delete()
            File(target, "MODEL_CARD").delete()
        }
    }
}
