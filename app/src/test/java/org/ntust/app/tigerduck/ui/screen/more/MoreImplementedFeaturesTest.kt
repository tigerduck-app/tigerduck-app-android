// Taps More's cards with the set More hands its grid, against a real NavHost. A separate list
// here once had to be edited alongside AppFeature.isImplemented; when the two drifted, a feature
// that worked as a tab read "Coming soon" in More, or a placeholder card tried to open a page.

package org.ntust.app.tigerduck.ui.screen.more

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.navigation.toRoute
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class MoreImplementedFeaturesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Free lunch has no screen and no open branch shipping one, so it stays a placeholder.
    private val features = listOf(AppFeature.HOME, AppFeature.SCORE, AppFeature.FREE_LUNCH)
    private lateinit var navController: NavHostController
    private var comingSoon = 0

    @Before
    fun setUp() {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = START) {
                    composable(START) {
                        BoxWithConstraints(Modifier.requiredWidth(360.dp)) {
                            val columns = featureGridColumns(maxWidth)
                            FeatureGrid(
                                features = features,
                                columns = columns,
                                cardSize = rememberFeatureCardSize(
                                    labels = features.map { stringResource(it.displayNameRes) },
                                    gridWidthPx = constraints.maxWidth,
                                    columns = columns,
                                ),
                                implementedFeatures = implementedFeatures,
                                navController = navController,
                                onNotImplemented = { comingSoon++ },
                            )
                        }
                    }
                    composable(AppFeature.HOME.toRoute()) {}
                    composable(AppFeature.SCORE.toRoute()) {}
                }
            }
        }
    }

    private fun tap(feature: AppFeature) {
        composeRule.onNodeWithText(composeRule.activity.getString(feature.displayNameRes)).performClick()
        composeRule.waitForIdle()
    }

    private fun route() = navController.currentDestination?.route

    @Test
    fun `Home opens its page`() {
        tap(AppFeature.HOME)
        assertEquals(AppFeature.HOME.toRoute(), route())
        assertEquals(0, comingSoon)
    }

    @Test
    fun `Score opens its page`() {
        tap(AppFeature.SCORE)
        assertEquals(AppFeature.SCORE.toRoute(), route())
        assertEquals(0, comingSoon)
    }

    @Test
    fun `a feature that has not shipped says Coming soon instead of opening`() {
        tap(AppFeature.FREE_LUNCH)
        assertEquals(START, route())
        assertEquals(1, comingSoon)
    }

    private companion object {
        const val START = "more"
    }
}
