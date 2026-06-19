package com.nemoclaw.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StreamingBubbleView(state: StreamingState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .animateContentSize()
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                val showActivity = !state.isDone || state.hasThinking || state.toolCalls.isNotEmpty()
                if (showActivity) {
                    HermesActivityExpander(state)
                }

                if (state.text.isNotEmpty()) {
                    MarkdownText(state.text, color = Color.White, fontSize = 15.sp)
                }

                val validBlocks = remember(state.visualBlocks) {
                    state.visualBlocks.filter { it.isValidVisualBlock() }
                }
                validBlocks.forEach { block ->
                    androidx.compose.runtime.key(block.id) {
                        VisualBlockView(block)
                    }
                }

                if (state.isDone) {
                    val parts = mutableListOf<String>()
                    state.stats?.ttftMs?.takeIf { it > 0 }?.let { parts += "TTFT ${String.format(java.util.Locale.US, "%.0f", it)}ms" }
                    state.stats?.tokensPerSecond?.takeIf { it > 0 }?.let { parts += "${String.format(java.util.Locale.US, "%.2f", it)} t/s" }
                    state.stats?.tokensOut?.takeIf { it > 0 }?.let { parts += "$it tok" }
                    state.stats?.promptTokens?.takeIf { it > 0 }?.let { parts += "prompt $it" }
                    state.stats?.contextTokens()?.takeIf { it > 0 }?.let { parts += "ctx $it" }
                    state.stats?.contextLength?.takeIf { it > 0 }?.let { parts += "max $it" }
                    state.stats?.totalMs?.takeIf { it > 0 }?.let { parts += "${String.format(java.util.Locale.US, "%.1f", it / 1000.0)}s" }
                    if (parts.isNotEmpty()) {
                        Text(
                            text = parts.joinToString("  ·  "),
                            color = AppColors.Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                state.error?.let { err ->
                    if (state.text.isEmpty()) {
                        Text(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                            text = err,
                            color = AppColors.Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
}

@Composable
internal fun HermesActivityExpander(state: StreamingState) {
    var nowNs by remember(state.startedAtNs) { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(state.startedAtNs, state.isDone) {
        while (!state.isDone) {
            kotlinx.coroutines.delay(500L)
            nowNs = System.nanoTime()
        }
    }
    val active = !state.isDone
    val elapsedSec = ((if (active) nowNs else System.nanoTime()) - state.startedAtNs) / 1_000_000_000.0
    val generationStarted = state.text.isNotBlank() || state.hasThinking || state.toolCalls.isNotEmpty()
    val processingPercent = state.promptProgressPercent

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active && !generationStarted) {
            FlagRow(
                title = state.status.ifBlank { "Processing prompt" },
                value = processingPercent?.let { "$it%" } ?: String.format(java.util.Locale.US, "%.1fs", elapsedSec),
                shimmer = true
            )
        }

        if (state.hasThinking || state.thinking.isNotBlank()) {
            ThinkingExpander(
                thinking = state.thinking,
                active = active && state.text.isBlank(),
                elapsedSec = if (state.thinkingElapsedSec > 0) state.thinkingElapsedSec else elapsedSec
            )
        }

        if (state.toolCalls.isNotEmpty()) {
            ToolGroupExpander(state.toolCalls)
        }

        if (active && state.text.isNotBlank()) {
            FlagRow(
                title = state.status.ifBlank { "Generazione" },
                value = activityIndicator(state),
                shimmer = true
            )
        }
    }
}

@Composable
internal fun ToolGroupExpander(tools: List<ToolCallState>) {
    var expanded by remember { mutableStateOf(false) }
    val pending = tools.count { inferToolOutcome(it) == ToolOutcome.Pending }
    val failed = tools.count { inferToolOutcome(it) == ToolOutcome.Error }
    val completed = tools.size - pending - failed
    val status = when {
        pending > 0 -> "$pending in corso"
        failed > 0 -> "$failed falliti"
        else -> "$completed completati"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pending > 0) {
                ShimmerText("Tool")
            } else {
                Text(text = "Tool", color = AppColors.Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Text(text = "${tools.size}", color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = status, color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Chiudi tool" else "Mostra tool",
                tint = AppColors.Muted,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 2.dp, end = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tools.forEach { tool ->
                    androidx.compose.runtime.key(tool.id) {
                        ToolActivityRow(tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagRow(title: String, value: String, shimmer: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (shimmer) {
            ShimmerText(title)
        } else {
            Text(text = title, color = AppColors.Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, color = AppColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun activityIndicator(state: StreamingState): String {
    if (state.isDone) return "Completato"
    val pendingTool = state.toolCalls.any { inferToolOutcome(it) == ToolOutcome.Pending }
    if (pendingTool) return "tool…"
    if (state.text.isNotEmpty()) {
        val toks = state.text.length / 4
        return if (toks > 0) "$toks tok" else "…"
    }
    if (state.hasThinking) {
        val toks = state.thinking.length / 4
        return if (toks > 0) "reasoning $toks tok" else "reasoning…"
    }
    return "prompt…"
}

@Composable
internal fun ThinkingExpander(thinking: String, active: Boolean, elapsedSec: Double) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (active) {
                ShimmerText("Sto pensando")
            } else {
                val label = if (elapsedSec >= 1) "Pensato per ${String.format(java.util.Locale.US, "%.1f", elapsedSec)}s" else "Ragionamento"
                Text(text = label, color = AppColors.Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Chiudi ragionamento" else "Mostra ragionamento",
                tint = AppColors.Muted,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            Text(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 2.dp, end = 8.dp, bottom = 4.dp),
                text = thinking.ifBlank { "Hermes non ha ancora inviato token di reasoning. Se il modello/server li manda, appariranno qui in tempo reale." },
                color = AppColors.Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
internal fun ShimmerText(text: String) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val sweep = 520f
    val x = -260f + phase * 900f
    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF6F7888),
            Color(0xFF8B95A5),
            Color(0xFFFFFFFF),
            Color(0xFF8B95A5),
            Color(0xFF6F7888)
        ),
        start = Offset(x = x - sweep, y = 0f),
        end = Offset(x = x, y = 0f),
        tileMode = TileMode.Clamp
    )
    Text(
        text = text,
        style = TextStyle(
            brush = brush,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
    )
}

@Composable
internal fun ActivityLine(label: String, value: String, monospaced: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = AppColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            modifier = Modifier
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
            text = value,
            color = if (value.startsWith("Nessun token")) AppColors.Muted else Color.White,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun ToolActivityRow(tool: ToolCallState) {
    var expanded by remember { mutableStateOf(false) }
    val outcome = inferToolOutcome(tool)
    val statusColor = when (outcome) {
        ToolOutcome.Success -> Color(0xFF34C759)
        ToolOutcome.Error -> Color(0xFFFF453A)
        ToolOutcome.Pending -> AppColors.Accent
    }
    val statusIcon = when (outcome) {
        ToolOutcome.Success -> Icons.Rounded.CheckCircle
        ToolOutcome.Error -> Icons.Rounded.Error
        ToolOutcome.Pending -> Icons.Rounded.AutoAwesome
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(18.dp))
            Text(tool.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(outcome.label, color = statusColor, fontSize = 11.sp)
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Chiudi tool" else "Apri tool",
                tint = AppColors.Muted,
                modifier = Modifier.size(15.dp)
            )
        }
        if (expanded) {
            ActivityLine("Argomenti", if (tool.args.isBlank()) "-" else prettifyJson(tool.args), monospaced = true)
            tool.result?.takeIf { it.isNotBlank() }?.let { result ->
                ActivityLine("Risultato", prettifyJson(result), monospaced = true)
            }
        }
    }
}

@Composable
internal fun ToolCallCard(tool: ToolCallState) {
    var expanded by remember { mutableStateOf(false) }
    val outcome = inferToolOutcome(tool)
    val statusColor = when (outcome) {
        ToolOutcome.Success -> Color(0xFF34C759)
        ToolOutcome.Error -> Color(0xFFFF453A)
        ToolOutcome.Pending -> AppColors.Accent
    }
    val statusIcon = when (outcome) {
        ToolOutcome.Success -> Icons.Rounded.CheckCircle
        ToolOutcome.Error -> Icons.Rounded.Error
        ToolOutcome.Pending -> Icons.Rounded.AutoAwesome
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppColors.Surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                Text(
                    text = "Tool · ${tool.name}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(text = tool.status, color = AppColors.Muted, fontSize = 11.sp)
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Chiudi" else "Apri",
                    tint = AppColors.Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "Argomenti", color = AppColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Surface(color = AppColors.Composer, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            text = if (tool.args.isEmpty()) "—" else prettifyJson(tool.args),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    tool.result?.takeIf { it.isNotEmpty() }?.let { result ->
                        Text(text = "Risultato", color = AppColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Surface(color = AppColors.Composer, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                text = prettifyJson(result),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = "Esito: ${outcome.label}",
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

internal enum class ToolOutcome(val label: String) {
    Pending("in corso"),
    Success("riuscito"),
    Error("fallito")
}

internal fun inferToolOutcome(tool: ToolCallState): ToolOutcome {
    val s = tool.status.lowercase()
    val r = tool.result?.lowercase().orEmpty()
    return when {
        s.contains("error") || s.contains("fail") || s.contains("fallit") || r.contains("\"error\"") -> ToolOutcome.Error
        s.contains("completat") || s.contains("risultato") || s.contains("success") || s.contains("done") || tool.result != null -> ToolOutcome.Success
        else -> ToolOutcome.Pending
    }
}

private const val PRETTIFY_JSON_MAX_CHARS = 20_000

internal fun prettifyJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    return try {
        val pretty = when {
            trimmed.startsWith("{") -> org.json.JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> org.json.JSONArray(trimmed).toString(2)
            else -> raw
        }
        if (pretty.length > PRETTIFY_JSON_MAX_CHARS) {
            pretty.take(PRETTIFY_JSON_MAX_CHARS) + "\n... [troncato a $PRETTIFY_JSON_MAX_CHARS char]"
        } else {
            pretty
        }
    } catch (_: Exception) {
        if (raw.length > PRETTIFY_JSON_MAX_CHARS) {
            raw.take(PRETTIFY_JSON_MAX_CHARS) + "\n... [troncato a $PRETTIFY_JSON_MAX_CHARS char]"
        } else {
            raw
        }
    }
}

@Composable
internal fun MarkdownText(
    markdown: String,
    color: Color = Color.White,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> {
                    val annotated = remember(block.text, color) { renderInlineMarkdown(block.text, color) }
                    Text(text = annotated, color = color, fontSize = fontSize)
                }
                is MdBlock.Header -> {
                    val annotated = remember(block.text, color) { renderInlineMarkdown(block.text, color) }
                    Text(
                        text = annotated,
                        color = color,
                        fontSize = when (block.level) { 1 -> 20.sp; 2 -> 17.sp; else -> 15.sp },
                        fontWeight = FontWeight.SemiBold
                    )
                }
                is MdBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", color = color, fontSize = fontSize)
                    val annotated = remember(block.text, color) { renderInlineMarkdown(block.text, color) }
                    Text(text = annotated, color = color, fontSize = fontSize)
                }
                is MdBlock.CodeBlock -> Surface(
                    color = AppColors.Composer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (block.language.isNotBlank()) {
                            Text(block.language, color = AppColors.Muted, fontSize = 11.sp)
                        }
                        Text(
                            block.code,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

internal sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Header(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
}

internal fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var i = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushParagraph()
                val lang = line.removePrefix("```").trim()
                val codeBuf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    if (codeBuf.isNotEmpty()) codeBuf.append('\n')
                    codeBuf.append(lines[i])
                    i++
                }
                blocks += MdBlock.CodeBlock(lang, codeBuf.toString())
                i++
            }
            line.startsWith("# ") -> { flushParagraph(); blocks += MdBlock.Header(1, line.removePrefix("# ").trim()); i++ }
            line.startsWith("## ") -> { flushParagraph(); blocks += MdBlock.Header(2, line.removePrefix("## ").trim()); i++ }
            line.startsWith("### ") -> { flushParagraph(); blocks += MdBlock.Header(3, line.removePrefix("### ").trim()); i++ }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushParagraph()
                blocks += MdBlock.Bullet(line.drop(2).trim())
                i++
            }
            line.isBlank() -> { flushParagraph(); i++ }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

private const val MARKDOWN_MAX_INPUT_CHARS = 200_000
private const val MARKDOWN_MAX_INLINE_STYLES = 500

internal fun renderInlineMarkdown(text: String, baseColor: Color): androidx.compose.ui.text.AnnotatedString {
    val safe = if (text.length > MARKDOWN_MAX_INPUT_CHARS) text.substring(0, MARKDOWN_MAX_INPUT_CHARS) else text
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    var i = 0
    var styleCount = 0
    while (i < safe.length) {
        val ch = safe[i]
        if (styleCount < MARKDOWN_MAX_INLINE_STYLES) {
            if (ch == '*' && i + 1 < safe.length && safe[i + 1] == '*') {
                val end = safe.indexOf("**", i + 2)
                if (end > i + 2) {
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                    builder.append(safe.substring(i + 2, end))
                    builder.pop()
                    styleCount++
                    i = end + 2
                    continue
                }
            }
            if ((ch == '*' || ch == '_') && i + 1 < safe.length && safe[i + 1] != ch) {
                val end = safe.indexOf(ch, i + 1)
                if (end > i + 1) {
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    builder.append(safe.substring(i + 1, end))
                    builder.pop()
                    styleCount++
                    i = end + 1
                    continue
                }
            }
            if (ch == '`') {
                val end = safe.indexOf('`', i + 1)
                if (end > i + 1) {
                    builder.pushStyle(androidx.compose.ui.text.SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x33A2ADBF)
                    ))
                    builder.append(safe.substring(i + 1, end))
                    builder.pop()
                    styleCount++
                    i = end + 1
                    continue
                }
            }
        }
        builder.append(ch)
        i++
    }
    return builder.toAnnotatedString()
}

internal data class SlashCommand(
    val display: String,
    val title: String,
    val description: String,
    val action: SlashAction
)

internal enum class SlashAction {
    ModeChat, ModeAgent, Clear, Help, Health,
    OpenServer, OpenHardware, OpenOperator, OpenArchive, OpenTasks, OpenVoice, OpenVideo, OpenNews, OpenSettings, OpenAbout,
    PromptSetup, PromptVisual, PromptResearch, PromptWeb, PromptImage, PromptVideo, PromptNews
}

internal fun slashCommands(): List<SlashCommand> = listOf(
    SlashCommand("/chat", "Modalita Chat", "Conversazione normale", SlashAction.ModeChat),
    SlashCommand("/agente", "Modalita Agente", "Esegui Runs/Jobs Hermes", SlashAction.ModeAgent),
    SlashCommand("/agent", "Modalita Agente", "Alias di /agente", SlashAction.ModeAgent),
    SlashCommand("/clear", "Pulisci chat", "Svuota conversazione corrente", SlashAction.Clear),
    SlashCommand("/new", "Nuova chat", "Inizia nuova conversazione", SlashAction.Clear),
    SlashCommand("/health", "Controlla Hermes", "Verifica health e capabilities", SlashAction.Health),
    SlashCommand("/server", "Apri Server", "Dashboard server", SlashAction.OpenServer),
    SlashCommand("/hardware", "Apri Hardware", "Prestazioni server", SlashAction.OpenHardware),
    SlashCommand("/prestazioni", "Apri Hardware", "Gestione attivita server", SlashAction.OpenHardware),
    SlashCommand("/runs", "Apri Operator/Runs", "Probe API Hermes", SlashAction.OpenOperator),
    SlashCommand("/archive", "Apri Archivio", "Conversazioni salvate", SlashAction.OpenArchive),
    SlashCommand("/tasks", "Apri Task agente", "Coda jobs Hermes", SlashAction.OpenTasks),
    SlashCommand("/voce", "Modalita Voce", "Schermo particelle Hermes", SlashAction.OpenVoice),
    SlashCommand("/voice", "Modalita Voce", "Alias di /voce", SlashAction.OpenVoice),
    SlashCommand("/video", "Video Hub", "Crea spunto o apri feed video", SlashAction.PromptVideo),
    SlashCommand("/news", "News Hub", "Crea articolo o apri feed news", SlashAction.PromptNews),
    SlashCommand("/settings", "Impostazioni", "Pagina settings", SlashAction.OpenSettings),
    SlashCommand("/about", "Info app", "Versione, profilo", SlashAction.OpenAbout),
    SlashCommand("/setup", "Setup Hermes", "Prompt setup", SlashAction.PromptSetup),
    SlashCommand("/visual", "Spiegazione visiva", "Richiedi blocchi visuali", SlashAction.PromptVisual),
    SlashCommand("/research", "Deep research", "Approfondisci con fonti", SlashAction.PromptResearch),
    SlashCommand("/web", "Ricerca web", "Cerca sul web", SlashAction.PromptWeb),
    SlashCommand("/image", "Crea immagine", "Prepara richiesta immagine", SlashAction.PromptImage),
    SlashCommand("/help", "Aiuto", "Mostra comandi", SlashAction.Help)
)

internal fun filterSlashCommands(query: String): List<SlashCommand> {
    if (query.isEmpty() || !query.startsWith("/")) return emptyList()
    val q = query.lowercase()
    val needle = q.trimStart('/')
    return slashCommands().filter { cmd ->
        cmd.display.startsWith(q, ignoreCase = true) ||
            cmd.display.contains(needle, ignoreCase = true) ||
            cmd.title.contains(needle, ignoreCase = true)
    }.take(10)
}

@Composable
internal fun SlashCommandList(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        color = AppColors.Elevated,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            commands.forEach { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(cmd) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = cmd.display, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(text = "${cmd.title} · ${cmd.description}", color = AppColors.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

