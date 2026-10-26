package com.nemoclaw.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshot della sezione bot vera su JVM. PNG in
 * C:/Users/matte/AppData/Local/Temp/opencode/robo_bot*.png
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class BotSectionScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHarnessActivity>()

    @Test
    fun botEmpty() {
        composeRule.setContent {
            ChatClawTheme {
                BotEmptyState(
                    displayName = "Socrate",
                    opening = false,
                    unreachable = false
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_botempty.png")
        )
    }

    @Test
    fun botTopBar() {
        composeRule.setContent {
            ChatClawTheme {
                TopBar(
                    settings = AppSettings(),
                    contextUsage = ContextUsage(tokens = 12000, percent = 9),
                    connected = true,
                    gatewayRuntime = null,
                    title = "Socrate",
                    identityPicker = null,
                    onNewChat = {},
                    onOpenSidebar = {},
                    onOpenArchive = {},
                    onOpenComfy = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_bottopbar.png")
        )
    }

    @Test
    fun botToggle() {
        composeRule.setContent {
            ChatClawTheme {
                ChatBotToggle(
                    botActive = true,
                    onSelectChat = {},
                    onSelectBot = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_bottoggle.png")
        )
    }

    @Test
    fun botChatStackNoEmpty() {
        composeRule.setContent {
            ChatClawTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                ) {
                    TopBar(
                        settings = AppSettings(),
                        contextUsage = ContextUsage(tokens = 8000, percent = 6),
                        connected = true,
                        gatewayRuntime = null,
                        title = "Socrate",
                        identityPicker = null,
                        onNewChat = {},
                        onOpenSidebar = {},
                        onOpenArchive = {},
                        onOpenComfy = {}
                    )
                    ChatBotToggle(
                        botActive = true,
                        onSelectChat = {},
                        onSelectBot = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_botstack_noempty.png")
        )
    }

    @Test
    fun botChatStack() {
        // Replica fedele dell'ordine ChatScreen: TopBar + toggle + empty.
        // Prova che non ci siano veli/sanguinamenti tra gli strati.
        composeRule.setContent {
            ChatClawTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                ) {
                    TopBar(
                        settings = AppSettings(),
                        contextUsage = ContextUsage(tokens = 8000, percent = 6),
                        connected = true,
                        gatewayRuntime = null,
                        title = "Socrate",
                        identityPicker = null,
                        onNewChat = {},
                        onOpenSidebar = {},
                        onOpenArchive = {},
                        onOpenComfy = {}
                    )
                    ChatBotToggle(
                        botActive = true,
                        onSelectChat = {},
                        onSelectBot = {}
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        BotEmptyState(
                            displayName = "Socrate",
                            opening = false,
                            unreachable = false
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_botstack.png")
        )
    }

    @Test
    fun topBarIdentityMenuOpen() {
        composeRule.setContent {
            ChatClawTheme {
                TopBar(
                    settings = AppSettings(),
                    contextUsage = ContextUsage(tokens = 8000, percent = 6),
                    connected = true,
                    gatewayRuntime = null,
                    title = "Hermes Hub",
                    identityPicker = ChatIdentityPicker(
                        currentLabel = "Hermes",
                        activeKey = "",
                        loading = false,
                        status = "",
                        options = listOf(
                            ChatIdentityOption("primary::socrate", "Socrate", "primary"),
                            ChatIdentityOption("primary::journey", "Journey", "primary")
                        ),
                        onPick = {},
                        onRefresh = {}
                    ),
                    onNewChat = {},
                    onOpenSidebar = {},
                    onOpenArchive = {},
                    onOpenComfy = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hermes Hub").performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_topbarmenu.png")
        )
    }
}
