package dev.pitwall.di

import dev.pitwall.data.TitleRepository
import dev.pitwall.ui.title.TitleCalculatorViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Feature C — Title-Permutation Calculator DI.
 * The controller includes this module alongside the others; F1db is provided by the shared appModule.
 */
val titleModule: Module = module {
    single { TitleRepository(get()) }
    factory { TitleCalculatorViewModel(get()) }
}
