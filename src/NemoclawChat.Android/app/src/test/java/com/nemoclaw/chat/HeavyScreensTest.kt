package com.nemoclaw.chat

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
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
 * Misura il costo di prima composizione delle schermate pesanti
 * (sidebar -> impostazioni/profilo). Solo diagnostica + screenshot.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class HeavyScreensTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHarnessActivity>()

    private fun report(name: String, ms: Long) {
        File("C:/Users/matte/AppData/Local/Temp/opencode/robo_perf.txt").appendText("$name=${ms}ms\n")
    }

    @Test
    fun settingsFirstCompose() {
        val start = System.nanoTime()
        composeRule.setContent {
            ChatClawTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    gatewaySecret = null,
                    voiceProfile = VoiceProfile(),
                    onGatewaySecretChanged = {},
                    onSave = {},
                    onReset = {}
                )
            }
        }
        composeRule.waitForIdle()
        report("settings_first_compose", (System.nanoTime() - start) / 1_000_000)
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_settings.png")
        )
    }

    @Test
    fun profileFirstCompose() {
        val start = System.nanoTime()
        composeRule.setContent {
            ChatClawTheme {
                ProfileScreen(
                    context = LocalContext.current,
                    settings = AppSettings(),
                    onOpenTab = {}
                )
            }
        }
        composeRule.waitForIdle()
        report("profile_first_compose", (System.nanoTime() - start) / 1_000_000)
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_profile.png")
        )
    }
}
