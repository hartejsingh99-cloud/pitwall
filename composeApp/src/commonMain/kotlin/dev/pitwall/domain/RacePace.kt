package dev.pitwall.domain

/**
 * A hero row enriched with the optional race-pace companion. The hero's meaning is unchanged: rows are
 * still ranked by the teammate-normalized ONE-LAP rating; [racePacePct] is a parallel column (symmetric
 * % vs teammate on median race pace, negative = faster), null when the bake has no race-pace for that
 * driver/season. 2018+ only — the era-gate lives in the repository, not here.
 */
data class HeroRow(
    val rating: DriverCarRating,
    val name: String,
    val racePacePct: Double? = null,
)

/**
 * Attach race-pace to each ranked hero row WITHOUT reordering. Drivers absent from [pace] keep a null
 * companion (graceful, never an error). Pure: no DB, no era logic.
 */
fun mergeRacePace(
    ratings: List<Pair<DriverCarRating, String>>,
    pace: Map<String, Double>,
): List<HeroRow> = ratings.map { (rating, name) ->
    HeroRow(rating = rating, name = name, racePacePct = pace[rating.driverId])
}
