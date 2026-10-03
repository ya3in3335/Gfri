package com.greenaccesse.app.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenaccesse.app.GreenAccesseApp
import com.greenaccesse.app.di.AppContainer

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as GreenAccesseApp).container

/** Creates a ViewModel with access to the [AppContainer] (manual DI). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(initializer = { create(container) })
}
