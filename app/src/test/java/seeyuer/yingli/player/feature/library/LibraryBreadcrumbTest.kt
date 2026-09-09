package seeyuer.yingli.player.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.domain.library.LibraryPathSegment

class LibraryBreadcrumbTest {
    @Test
    fun `collapsed breadcrumb keeps nearest two ancestors and hides earlier levels`() {
        val path = listOf("A", "B", "C", "D", "Current")
            .mapIndexed { index, name -> LibraryPathSegment(name, (0..index).joinToString("/") { "P$it" }) }

        val layout = collapsedBreadcrumbLayout(path)

        assertEquals(listOf("A", "B"), layout.hidden.map { it.value.name })
        assertEquals(listOf(0, 1), layout.hidden.map { it.index })
        assertEquals(listOf("C", "D"), layout.visible.map { it.value.name })
        assertEquals(listOf(2, 3), layout.visible.map { it.index })
    }

    @Test
    fun `collapsed breadcrumb omits current folder because title already shows it`() {
        val path = listOf(
            LibraryPathSegment("Parent", "Parent"),
            LibraryPathSegment("Current", "Parent/Current"),
        )

        val layout = collapsedBreadcrumbLayout(path)

        assertEquals(emptyList<IndexedValue<LibraryPathSegment>>(), layout.hidden)
        assertEquals(listOf("Parent"), layout.visible.map { it.value.name })
    }
}
