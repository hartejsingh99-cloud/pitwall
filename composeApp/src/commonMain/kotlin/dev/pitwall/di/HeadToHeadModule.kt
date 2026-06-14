package dev.pitwall.di

import dev.pitwall.data.HeadToHeadRepository
import dev.pitwall.ui.h2h.HeadToHeadViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/** Feature B DI. The controller includes this alongside appModule. */
val h2hModule: Module = module {
    single { HeadToHeadRepository(get()) }   // depends on the shared single<F1db> from appModule
    factory { HeadToHeadViewModel(get()) }
}
