package dev.pitwall.di

import dev.pitwall.data.BrowseRepository
import dev.pitwall.ui.browse.RaceResultViewModel
import dev.pitwall.ui.browse.RacesViewModel
import dev.pitwall.ui.browse.SeasonsViewModel
import dev.pitwall.ui.browse.StandingsViewModel
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Feature A (Browse) DI. The controller includes this module alongside the others.
 * BrowseRepository depends on the singleton F1db registered by the central app module.
 * The three parameterised ViewModels receive their id via parametersOf at the call site
 * (koinViewModel(parameters = { parametersOf(year) }) in the screens).
 */
val browseModule: Module = module {
    single { BrowseRepository(get()) }
    factory { SeasonsViewModel(get()) }
    factory { params -> RacesViewModel(get(), params.get()) }
    factory { params -> RaceResultViewModel(get(), params.get()) }
    factory { params -> StandingsViewModel(get(), params.get()) }
}
