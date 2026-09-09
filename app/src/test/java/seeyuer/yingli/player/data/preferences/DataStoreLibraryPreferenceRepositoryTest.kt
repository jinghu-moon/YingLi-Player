package seeyuer.yingli.player.data.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.domain.library.BreadcrumbMode

class DataStoreLibraryPreferenceRepositoryTest {
    @Test
    fun `breadcrumb mode maps to persisted user preference`() = runTest {
        val themeRepository = FakeThemeRepository()
        val repository = DataStoreLibraryPreferenceRepository(themeRepository)

        repository.setBreadcrumbMode(BreadcrumbMode.SCROLL)

        assertEquals(BreadcrumbPreference.SCROLL, themeRepository.settings.value.libraryBreadcrumb)
        assertEquals(BreadcrumbMode.SCROLL, repository.preference.first().breadcrumbMode)
    }

    private class FakeThemeRepository : ThemeRepository {
        override val settings = MutableStateFlow(UserPreferences())

        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            settings.value = transform(settings.value)
        }
    }
}
