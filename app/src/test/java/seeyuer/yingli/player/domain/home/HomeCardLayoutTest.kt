package seeyuer.yingli.player.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardLayoutTest {
    @Test
    fun defaultOrderMatchesHomeContract() {
        assertEquals(
            listOf(
                HomeCardId.STATS,
                HomeCardId.CONTINUE_WATCHING,
                HomeCardId.RECENTLY_ADDED,
                HomeCardId.MY_COLLECTIONS,
                HomeCardId.FREQUENT_FOLDERS,
                HomeCardId.MAINTENANCE,
            ),
            HomeCardLayout.Default.order,
        )
    }

    @Test
    fun normalizeDropsUnknownAndDuplicateIdsThenAppendsMissingCards() {
        val layout = HomeCardLayout.normalize(
            listOf("RECENTLY_ADDED", "UNKNOWN", "RECENTLY_ADDED", "STATS"),
            listOf("STATS", "UNKNOWN"),
        )

        assertEquals(HomeCardId.RECENTLY_ADDED, layout.order.first())
        assertEquals(HomeCardId.STATS, layout.order[1])
        assertEquals(HomeCardId.entries.toSet(), layout.order.toSet())
        assertEquals(setOf(HomeCardId.STATS), layout.hidden)
    }

    @Test
    fun corruptOrAbsentValuesReturnDefaultLayout() {
        assertEquals(HomeCardLayout.Default, HomeCardLayout.normalize(null, null))
        assertEquals(HomeCardLayout.Default, HomeCardLayout.normalize(listOf("broken"), listOf("broken")))
    }

    @Test
    fun moveHideShowAndResetAreDeterministic() {
        val moved = HomeCardLayout.Default.move(5, 1)
        assertEquals(HomeCardId.MAINTENANCE, moved.order[1])

        val hidden = moved.setVisible(HomeCardId.STATS, false)
        assertTrue(HomeCardId.STATS in hidden.hidden)
        assertFalse(HomeCardId.STATS in hidden.setVisible(HomeCardId.STATS, true).hidden)
        assertEquals(HomeCardLayout.Default, hidden.reset())
    }

    @Test
    fun allCardsMayBeHiddenWithoutLosingTheirOrder() {
        val hidden = HomeCardId.entries.fold(HomeCardLayout.Default) { layout, id ->
            layout.setVisible(id, false)
        }
        assertEquals(HomeCardId.entries.toSet(), hidden.hidden)
        assertEquals(HomeCardLayout.DEFAULT_ORDER, hidden.order)
    }
}
