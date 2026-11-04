package com.pedrolopes.ttsing.desktop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where TTSing keeps its own files: `$XDG_DATA_HOME/ttsing`, `~/.local/share/ttsing` by default. */
class AppDirs(val root: File) {
    val covers: File get() = File(root, "covers").apply { mkdirs() }
    val voices: File get() = File(root, "voices").apply { mkdirs() }

    companion object {
        fun default(): AppDirs {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(System.getProperty("user.home"), ".local/share")
            return AppDirs(File(dataHome, "ttsing").apply { mkdirs() })
        }
    }
}

/**
 * One value kept in a JSON file. A write goes to a temporary file that is then renamed over the
 * old one, so a crash mid-write leaves the previous version rather than half a file. A file
 * that can't be read is set aside as `<name>.unreadable` rather than overwritten, since it
 * holds every reading position.
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    fun read(): T {
        if (!file.isFile) return default()
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse {
            runCatching {
                Files.move(file.toPath(), File(file.parentFile, "${file.name}.unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            default()
        }
    }

    @Synchronized
    fun write(value: T) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(serializer, value))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
