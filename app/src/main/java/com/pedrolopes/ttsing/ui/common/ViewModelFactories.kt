package com.pedrolopes.ttsing.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Builds a [androidx.lifecycle.ViewModelProvider.Factory] from a simple lambda. */
inline fun <reified VM : ViewModel> simpleFactory(crossinline create: (CreationExtras) -> VM) =
    viewModelFactory { initializer { create(this) } }
