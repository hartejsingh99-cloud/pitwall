package dev.pitwall.data

import dev.pitwall.db.QualifyingForSeason
import dev.pitwall.domain.QualiRow
import kotlin.test.Test
import kotlin.test.assertEquals

class MappingTest {
    @Test fun mapsGeneratedRowToDomain() {
        val row = QualifyingForSeason(
            race_id = 5L, round = 1L, official_name = "GP", driver_id = "ver", driver_name = "Max Verstappen",
            abbreviation = "VER", constructor_id = "rb", constructor_name = "Red Bull",
            time_millis = 90_000L, q1_millis = 92_000L, q2_millis = 91_000L, q3_millis = 90_000L,
            best_millis = 90_000L,
        )
        assertEquals(QualiRow(5, "ver", "rb", 90_000L, 92_000L, 91_000L, 90_000L), row.toDomain())
    }
}
