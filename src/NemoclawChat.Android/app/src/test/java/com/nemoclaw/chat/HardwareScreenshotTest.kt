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
 * Screenshot della sezione Prestazioni su JVM (stato senza server: verifica
 * che la griglia card + vista espansa si compongano senza crash).
 * PNG in C:/Users/matte/AppData/Local/Temp/opencode/robo_*.png
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class HardwareScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHarnessActivity>()

    @Test
    fun hardwareEmptyComposes() {
        composeRule.setContent {
            ChatClawTheme {
                HardwareScreen(
                    context = LocalContext.current,
                    settings = AppSettings()
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_hardware.png")
        )
    }
}
