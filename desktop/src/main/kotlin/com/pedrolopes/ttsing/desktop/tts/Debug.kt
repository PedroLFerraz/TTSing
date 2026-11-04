package com.pedrolopes.ttsing.desktop.tts

/** `TTSING_DEBUG=1` prints what the voice is doing to stderr: enough to tell where it stalls. */
private val enabled = System.getenv("TTSING_DEBUG") == "1"

internal fun debug(message: () -> String) {
    if (enabled) System.err.println("[ttsing ${System.currentTimeMillis() % 100_000}] ${Thread.currentThread().name}: ${message()}")
}
