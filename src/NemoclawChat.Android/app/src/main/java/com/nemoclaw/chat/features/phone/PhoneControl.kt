package com.nemoclaw.chat.features.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stato armato + log esecuzioni + albero UI. Puro dove possibile (testabile). */
internal const val PHONE_PREFS = "hermes_phone_control"
private const val PHONE_ARMED_KEY = "armed"
private const val PHONE_LOG_KEY = "log"
internal const val PHONE_LOG_CAP = 50
internal const val PHONE_TREE_NODE_CAP = 200

internal fun isPhoneArmed(context: Context): Boolean {
    return context.applicationContext
        .getSharedPreferences(PHONE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(PHONE_ARMED_KEY, false)
}

internal fun setPhoneArmed(context: Context, armed: Boolean) {
    context.applicationContext
        .getSharedPreferences(PHONE_PREFS, Context.MODE_PRIVATE)
        .edit { putBoolean(PHONE_ARMED_KEY, armed) }
}

internal fun isPhoneAccessibilityEnabled(context: Context): Boolean {
    val component = android.content.ComponentName(
        context.applicationContext, HermesPhoneService::class.java
    ).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}

internal fun openPhoneAccessibilitySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Nodo UI semplificato (niente framework nel formato: testabile). */
internal data class UiNode(
    val depth: Int,
    val className: String,
    val text: String,
    val description: String,
    val viewId: String,
    val bounds: String,
    val clickable: Boolean,
    val editable: Boolean
)

internal fun collectUiTree(root: AccessibilityNodeInfo): List<UiNode> {
    val out = ArrayList<UiNode>(PHONE_TREE_NODE_CAP)
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (out.size >= PHONE_TREE_NODE_CAP) return
        val bounds = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val rawText = node.text?.toString().orEmpty()
        val text = if (node.isPassword) "•••" else rawText.take(80)
        val cls = (node.className?.toString() ?: "").substringAfterLast('.')
        val vid = (node.viewIdResourceName ?: "").substringAfterLast('/').take(80)
        out.add(
            UiNode(
                depth = depth,
                className = cls.take(40),
                text = text,
                description = (node.contentDescription?.toString().orEmpty()).take(80),
                viewId = vid,
                bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                clickable = node.isClickable,
                editable = node.isEditable
            )
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            visit(child, depth + 1)
        }
    }
    visit(root, 0)
    return out
}

internal fun formatUiTree(nodes: List<UiNode>): String {
    val lines = ArrayList<String>(nodes.size.coerceAtMost(PHONE_TREE_NODE_CAP))
    for (node in nodes.take(PHONE_TREE_NODE_CAP)) {
        val indent = "  ".repeat(node.depth.coerceIn(0, 8))
        val flags = buildString {
            if (node.clickable) append('C')
            if (node.editable) append('E')
        }
        val label = listOf(node.text, node.description, node.viewId)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .take(160)
        lines.add("$indent[${node.className}] $label <$flags> ${node.bounds}")
    }
    return lines.joinToString("\n")
}

internal fun phoneLogLine(kind: String, id: String, result: String): String {
    val stamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date())
    return "$stamp $kind $id: ${result.take(120)}"
}

internal fun appendPhoneLog(context: Context, line: String) {
    val prefs = context.applicationContext.getSharedPreferences(PHONE_PREFS, Context.MODE_PRIVATE)
    val updated = (prefs.getStringSet(PHONE_LOG_KEY, emptySet()) ?: emptySet()).toMutableList()
    updated.add(0, line)
    prefs.edit { putStringSet(PHONE_LOG_KEY, updated.take(PHONE_LOG_CAP).toSet()) }
}

internal fun loadPhoneLog(context: Context): List<String> {
    val set = context.applicationContext
        .getSharedPreferences(PHONE_PREFS, Context.MODE_PRIVATE)
        .getStringSet(PHONE_LOG_KEY, emptySet()).orEmpty()
    return set.sortedDescending().take(PHONE_LOG_CAP)
}

internal fun AccessibilityService.findEditableNode(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var guard = 0
    while (queue.isNotEmpty() && guard++ < 2000) {
        val node = queue.removeFirst()
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return null
}
