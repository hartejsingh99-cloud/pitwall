package dev.pitwall.di

import dev.pitwall.data.RecordsRepository
import dev.pitwall.ui.records.OnThisDayViewModel
import dev.pitwall.ui.records.RecordsViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Feature D — Records Book + On-This-Day. Depends on the shared single F1db (provided by the
 * controller's appModule); the controller includes this module when wiring combined DI.
 */
val recordsModule: Module = module {
    single { RecordsRepository(get()) }
    factory { RecordsViewModel(get()) }
    factory { OnThisDayViewModel(get()) }
}
