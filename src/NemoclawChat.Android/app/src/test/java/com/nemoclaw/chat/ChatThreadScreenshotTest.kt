package com.nemoclaw.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.nemoclaw.chat.features.bots.BotRosterCard
import com.nemoclaw.chat.features.bots.HermesBotItem
import com.nemoclaw.chat.ui.theme.ChatClawTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshot di thread chat, banner e card roster su JVM. PNG in
 * C:/Users/matte/AppData/Local/Temp/opencode/robo_thread*.png
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class ChatThreadScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestHarnessActivity>()

    @Test
    fun threadBubbles() {
        composeRule.setContent {
            ChatClawTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MessageBubble(
                        message = ChatMessage(author = "Tu", text = "Accendi la luce in salotto", fromUser = true),
                        settings = AppSettings()
                    )
                    MessageBubble(
                        message = ChatMessage(author = "Hermes", text = "Fatto, luce del salotto accesa al 70%. Vuoi anche abbassare le tapparelle?", fromUser = false),
                        settings = AppSettings()
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_thread.png")
        )
    }

    @Test
    fun stagedBanner() {
        composeRule.setContent {
            ChatClawTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ServerStagedBanner(count = 3, onShow = {}, onDismiss = {})
                    LiveFollowBanner(chars = 512, onStop = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_banners.png")
        )
    }

    @Test
    fun rosterCard() {
        val bot = HermesBotItem(
            profile = "socrate",
            displayName = "Socrate",
            description = "Ragionamento",
            hidden = false,
            chatId = null,
            isDefault = false,
            connectionId = "primary",
            connectionLabel = "Gateway principale",
            identityKey = "primary::socrate"
        )
        composeRule.setContent {
            ChatClawTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                        .padding(16.dp)
                ) {
                    BotRosterCard(
                        bot = bot,
                        screenRunning = false,
                        screenPreview = null,
                        chatSupported = true,
                        busy = false,
                        onOpenDetail = {},
                        onOpenChat = {},
                        onOpenScreen = {},
                        onEdit = {},
                        onDelete = {},
                        canDelete = false,
                        previewText = "Certo, vediamo i log di oggi",
                        previewTime = "10:41"
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_rostercard.png")
        )
    }

    @Test
    fun noticeBanners() {
        composeRule.setContent {
            ChatClawTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppleBannerRow(
                            icon = Icons.Rounded.SmartToy,
                            tint = AppColors.Accent,
                            title = "Chat bot archiviata",
                            subtitle = "Riaprila da Bot Hermes."
                        ) {
                            BannerGlassButton(
                                Icons.Rounded.SmartToy,
                                "Apri Bot Hermes",
                                Color.White,
                                onClick = {}
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppleBannerRow(
                            icon = Icons.Rounded.Delete,
                            tint = AppColors.Error,
                            title = "Conversazione eliminata",
                            subtitle = "Stai vedendo un contenitore vuoto: i messaggi non torneranno."
                        ) {
                            TextButton(onClick = {}) {
                                Text("Nuova chat", color = AppColors.Accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            file = File("C:/Users/matte/AppData/Local/Temp/opencode/robo_notices.png")
        )
    }
}
