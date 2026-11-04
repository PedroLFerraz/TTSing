package com.pedrolopes.ttsing.desktop.tts

import com.pedrolopes.ttsing.desktop.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Puts the Python helper that runs Coqui's Tacotron 2 in the app's own folder, and takes it out.
 *
 * Coqui is PyTorch, so it can't run inside the app the way Piper does: [CoquiEngine] starts
 * `helper.py` with a Python of its own. Getting it is one step for the reader and four here:
 * uv (found on the computer, else fetched), a Python 3.11 that uv downloads, PyTorch (CPU) and
 * Coqui (about a gigabyte), then the model. Everything lands under `coqui/`, so removing the
 * voice gives all of it back; uv's download cache stays where uv keeps it, for a reinstall.
 * A setup that stops halfway leaves no `.ready` mark, and the next one starts over.
 */
class CoquiSetup(dirs: AppDirs, private val scope: CoroutineScope) {

    data class Progress(val step: String, val fraction: Float, val failed: Boolean = false, val reason: String? = null)

    private val root = File(dirs.root, "coqui")
    private val venv = File(root, "venv")
    private val python = File(venv, "bin/python")
    private val helper = File(root, "helper.py")
    private val marker = File(root, ".ready")
    private val log = File(root, "setup.log")

    private val _ready = MutableStateFlow(marker.isFile && python.canExecute())
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)

    /** The setup in flight, or the last one to fail; null otherwise. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var running: Process? = null

    fun start() {
        if (job?.isActive == true || _ready.value) return
        job = scope.launch {
            try {
                root.mkdirs()
                log.writeText("")
                val uv = findUv() ?: fetchUv()
                step("Getting Python 3.11", 0.1f)
                run(uv.path, "venv", "--allow-existing", "--python", "3.11", venv.path)
                step("Installing PyTorch and Coqui (about 1 GB)", 0.2f)
                run(
                    uv.path, "pip", "install", "--python", python.path,
                    "--index-strategy", "unsafe-best-match", "--extra-index-url", TORCH_CPU_INDEX,
                    *PACKAGES.toTypedArray(),
                )
                step("Downloading the voice", 0.85f)
                writeHelper()
                run(python.path, helper.path, "--download")
                marker.writeText(PACKAGES.joinToString("\n"))
                _ready.value = true
                _progress.value = null
            } catch (e: CancellationException) {
                running?.destroyForcibly()
                _progress.value = null
                throw e
            } catch (e: Exception) {
                _progress.value = Progress("", 0f, failed = true, reason = "Setting up Tacotron 2 failed: ${e.message} (see ${log.path})")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        running?.destroyForcibly()
    }

    /** Takes the helper, its Python and the model off the computer. */
    fun remove() {
        cancel()
        marker.delete()
        _ready.value = false
        _progress.value = null
        scope.launch { root.deleteRecursively() }
    }

    /** Starts the helper, which answers on its stdout; what it logs goes to `helper.log`. */
    fun startHelper(): Process {
        writeHelper()
        return ProcessBuilder(python.path, helper.path)
            .also { it.environment().putAll(environment()) }
            .redirectError(ProcessBuilder.Redirect.appendTo(File(root, "helper.log")))
            .start()
    }

    /** The app's copy of the helper, written out each time so an update to the app updates it. */
    private fun writeHelper() {
        val source = javaClass.getResourceAsStream("/coqui/helper.py") ?: throw IOException("helper.py is missing from the app")
        source.use { input -> helper.outputStream().use { input.copyTo(it) } }
    }

    private fun environment() = mapOf(
        // Coqui keeps its models here rather than in ~/.local/share/tts, so Remove takes them too.
        "TTS_HOME" to File(root, "models").path,
        "UV_PYTHON_INSTALL_DIR" to File(root, "python").path,
        "PYTHONUNBUFFERED" to "1",
    )

    private fun step(name: String, fraction: Float) {
        _progress.value = Progress(name, fraction)
        log.appendText("\n== $name\n")
    }

    /** Runs [command] to the end, its output in the log; cancelling the setup stops it. */
    private suspend fun run(vararg command: String) {
        val process = ProcessBuilder(*command)
            .also { it.environment().putAll(environment()) }
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        running = process
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) currentCoroutineContext().ensureActive()
            if (process.exitValue() != 0) throw IOException("${File(command.first()).name} ${command.getOrNull(1).orEmpty()} exited with ${process.exitValue()}")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            running = null
        }
    }

    /** uv, if this computer has it already: on the PATH, or where its installer puts it. */
    private fun findUv(): File? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "uv") }
        return (path + File(System.getProperty("user.home"), ".local/bin/uv") + File(root, "uv/uv"))
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /** Fetches uv's own release for this machine into `coqui/uv`. */
    private suspend fun fetchUv(): File {
        step("Getting uv", 0.02f)
        val arch = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw IOException("no uv for ${System.getProperty("os.arch")}")
        }
        val archive = File(root, "uv.tar.gz")
        URI("https://github.com/astral-sh/uv/releases/latest/download/uv-$arch-unknown-linux-gnu.tar.gz").toURL()
            .openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val target = File(root, "uv").apply { mkdirs() }
        run("tar", "-xzf", archive.path, "-C", target.path, "--strip-components=1")
        archive.delete()
        return File(target, "uv").takeIf { it.canExecute() } ?: throw IOException("uv wasn't in its archive")
    }

    private companion object {
        const val TORCH_CPU_INDEX = "https://download.pytorch.org/whl/cpu"

        /** Pinned to what was tried together: Coqui's newer dependencies need PyTorch 2.6 or later. */
        val PACKAGES = listOf("torch==2.8.0+cpu", "torchaudio==2.8.0+cpu", "coqui-tts==0.28.0")
    }
}
