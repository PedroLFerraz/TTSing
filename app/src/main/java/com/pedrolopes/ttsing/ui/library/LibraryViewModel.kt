package com.pedrolopes.ttsing.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pedrolopes.ttsing.TTSingApp
import com.pedrolopes.ttsing.data.BookRepository
import com.pedrolopes.ttsing.data.db.BookEntity
import com.pedrolopes.ttsing.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A subfolder of the one being shown, and how many books sit anywhere beneath it. */
data class LibraryFolder(val name: String, val path: String, val bookCount: Int)

data class LibraryUiState(
    /** The books directly in [folder]; those in its subfolders are behind [subfolders]. */
    val books: List<BookEntity> = emptyList(),
    val subfolders: List<LibraryFolder> = emptyList(),
    /** The folder being shown, relative to the library folder; "" is its top. */
    val folder: String = "",
    val totalBooks: Int = 0,
    val hasFolder: Boolean = false,
    val isScanning: Boolean = false,
)

class LibraryViewModel(
    private val repo: BookRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    private val folder = MutableStateFlow("")

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        repo.observeBooks(),
        settings.settings.map { it.libraryFolderUri != null },
        scanning,
        folder,
    ) { books, hasFolder, isScanning, folder ->
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val subfolders = books
            .filter { it.folder.startsWith(prefix) && it.folder.length > prefix.length }
            .groupingBy { it.folder.removePrefix(prefix).substringBefore('/') }
            .eachCount()
            .map { (name, count) -> LibraryFolder(name, prefix + name, count) }
            .sortedBy { it.name.lowercase() }
        LibraryUiState(
            books = books.filter { it.folder == folder },
            subfolders = subfolders,
            folder = folder,
            totalBooks = books.size,
            hasFolder = hasFolder,
            isScanning = isScanning,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    init {
        // Refresh the cache on launch in case files changed outside the app.
        refresh()
    }

    fun openFolder(path: String) {
        folder.value = path
    }

    fun upFolder() {
        folder.value = folder.value.substringBeforeLast('/', missingDelimiterValue = "")
    }

    fun onFolderPicked(uri: String) {
        folder.value = ""
        viewModelScope.launch {
            settings.setLibraryFolder(uri)
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            scanning.value = true
            runCatching { repo.syncLibrary() }
            scanning.value = false
        }
    }

    companion object {
        fun create(): LibraryViewModel {
            val app = TTSingApp.instance
            return LibraryViewModel(app.books, app.settings)
        }
    }
}
