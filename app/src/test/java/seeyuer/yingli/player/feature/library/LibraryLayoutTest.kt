package seeyuer.yingli.player.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLayoutTest {
    @Test
    fun `grid row count handles empty complete and partial rows`() {
        assertEquals(0, gridRowCount(itemCount = 0, columnCount = 3))
        assertEquals(2, gridRowCount(itemCount = 6, columnCount = 3))
        assertEquals(3, gridRowCount(itemCount = 7, columnCount = 3))
    }

    @Test
    fun `last grid row only exposes valid item indices`() {
        assertEquals(listOf(0, 1, 2), gridRowIndices(rowIndex = 0, itemCount = 5, columnCount = 3).toList())
        assertEquals(listOf(3, 4), gridRowIndices(rowIndex = 1, itemCount = 5, columnCount = 3).toList())
        assertEquals(emptyList<Int>(), gridRowIndices(rowIndex = 2, itemCount = 5, columnCount = 3).toList())
    }
}
