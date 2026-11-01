package com.nemoclaw.chat

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * La card aggiornamenti vive in fondo alla dashboard: scroll fino in fondo
 * e verifica che ci sia (stessa card del Profilo, estratta in AppUpdateCard).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class DashboardUpdateCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHarnessActivity>()

    @Test
    fun dashboardBottomShowsUpdateCard() {
        composeRule.setContent {
            ChatClawTheme {
                ServerScreen(
                    context = LocalContext.current,
                    settings = AppSettings(),
                    onOpenTab = {}
                )
            }
        }
        composeRule.waitForIdle()
        for (i in 0 until 12) {
            val found = runCatching {
                composeRule.onNodeWithText("Controlla aggiornamenti").assertExists()
            }.isSuccess
            if (found) break
            composeRule.onRoot().performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Controlla aggiornamenti").assertExists()
        composeRule.onNodeWithText("AGGIORNAMENTI").assertExists()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_dashboard_update.png")
        )
    }
}
