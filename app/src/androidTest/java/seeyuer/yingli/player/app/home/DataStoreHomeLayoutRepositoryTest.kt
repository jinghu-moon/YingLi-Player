package seeyuer.yingli.player.app.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.domain.home.HomeCardId
import seeyuer.yingli.player.domain.home.HomeCardLayout

@RunWith(AndroidJUnit4::class)
class DataStoreHomeLayoutRepositoryTest {
    @Test
    fun savedLayoutSurvivesRepositoryRecreation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = DataStoreHomeLayoutRepository(context)
        val expected = HomeCardLayout.Default
            .move(HomeCardId.MAINTENANCE.ordinal, HomeCardId.STATS.ordinal)
            .setVisible(HomeCardId.RECENTLY_ADDED, false)

        first.save(expected)
        val recreated = DataStoreHomeLayoutRepository(context)

        assertEquals(expected, recreated.layout.first())
        recreated.save(HomeCardLayout.Default)
    }
}
