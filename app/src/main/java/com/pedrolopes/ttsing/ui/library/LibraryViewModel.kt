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

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val hasFolder: Boolean = false,
    val isScanning: Boolean = false,
)

class LibraryViewModel(
    private val repo: BookRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val scanning = MutableStateFlow(false)

    val uiState: StateFlow<LibraryUiState> = kotlinx.coroutines.flow.combine(
        repo.observeBooks(),
        settings.settings.map { it.libraryFolderUri != null },
        scanning,
    ) { books, hasFolder, isScanning ->
        LibraryUiState(books = books, hasFolder = hasFolder, isScanning = isScanning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    init {
        // Refresh the cache on launch in case files changed outside the app.
        refresh()
    }

    fun onFolderPicked(uri: String) {
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
