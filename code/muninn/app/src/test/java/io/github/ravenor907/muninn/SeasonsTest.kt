package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.Looks
import io.github.ravenor907.muninn.ui.Seasons
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Seasonal look: which season shows on which date, and saved looks opening as the right one. */
class SeasonsTest {
    @Test
    fun autumnShowsAllYearForNow() {
        for (m in 1..12) for (d in listOf(1, 15, 28)) assertEquals(Seasons.AUTUMN, Seasons.at(m, d))
        assertEquals(Seasons.AUTUMN, Seasons.at(12, 31))
    }

    @Test
    fun newSeasonsSlotInByDate() {
        val spans = listOf(
            Seasons.Span("winter", 12, 21, 3, 19),   // runs past the new year
            Seasons.Span("spring", 3, 20, 6, 20),
            Seasons.Span(Seasons.AUTUMN, 9, 22, 12, 20),
        )
        assertEquals("winter", Seasons.at(12, 25, spans))
        assertEquals("winter", Seasons.at(1, 10, spans))
        assertEquals("winter", Seasons.at(3, 19, spans))
        assertEquals("spring", Seasons.at(3, 20, spans))
        assertEquals(Seasons.AUTUMN, Seasons.at(10, 31, spans))
        // A date no span covers gets the first season.
        assertEquals("winter", Seasons.at(7, 4, spans))
    }

    @Test
    fun savedAutumnOpensAsSeasonal() {
        assertEquals(Looks.SEASONAL, Looks.of("autumn"))
        assertEquals(Looks.SEASONAL, Looks.of("seasonal"))
        assertEquals(Looks.DARK, Looks.of("something else"))
        assertEquals("Seasonal", Looks.name(Looks.SEASONAL))
    }
}
