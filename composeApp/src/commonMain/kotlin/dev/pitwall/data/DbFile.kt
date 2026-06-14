package dev.pitwall.data

const val DATASET_VERSION = 1

/**
 * Human-facing freshness stamp for the bundled f1db copy. f1db carries no embedded version, so this
 * is derived from the data: the latest race with results is 2026 Round 6 (Monaco), 6 of 22 rounds run.
 * Surface this anywhere "as-of" matters (About screen, Title calculator, Records) so a stale bundle is
 * never mistaken for live truth. Bump alongside DATASET_VERSION when re-bundling a newer f1db release.
 */
const val DATASET_LABEL = "f1db · data through 2026 R6 (Monaco)"

expect suspend fun ensureF1dbFile(): String
