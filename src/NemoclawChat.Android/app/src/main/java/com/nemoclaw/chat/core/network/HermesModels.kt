package com.nemoclaw.chat

import org.json.JSONObject
import org.json.JSONArray

/**
 * Client per picker modelli moderno Hermes Agent.
 * Primario: GET /api/model/options (provider-aware, pricing, capability, reasoning).
 * Fallback: GET /v1/models (OpenAI-compatible, solo nomi).
 * Nessun catalogo hardcodato.
 */
data class HermesModelOption(
    val id: String,
    val displayName: String,
    val provider: String,
    val capabilities: List<String> = emptyList(),
    val contextWindow: Long? = null,
    val pricing: JSONObject? = null,
    val reasoningSupported: Boolean = false,
    val reasoningEfforts: List<String> = emptyList(),
    val warning: String? = null,
    val extra: JSONObject? = null
)

data class HermesModelCatalog(
    val providers: List<HermesModelProviderRow> = emptyList(),
    val models: List<HermesModelOption> = emptyList(),
    val source: String = "model-options", // oppure "v1-models"
    val raw: String = ""
)

data class HermesModelProviderRow(
    val slug: String,
    val displayName: String,
    val available: Boolean = true,
    val warning: String? = null
)

internal fun parseModelOptionsPayload(body: String): HermesModelCatalog {
    if (body.isBlank()) return HermesModelCatalog(source = "model-options", raw = body)
    val root = runCatching { JSONObject(body) }.getOrNull()
        ?: return HermesModelCatalog(source = "model-options", raw = body)
    val providers = mutableListOf<HermesModelProviderRow>()
    val models = mutableListOf<HermesModelOption>()
    val providerArray = root.optJSONArray("providers") ?: root.optJSONArray("provider_rows")
    if (providerArray != null) {
        for (i in 0 until providerArray.length()) {
            val p = providerArray.optJSONObject(i) ?: continue
            val slug = p.optString("slug", p.optString("provider", p.optString("id", ""))).trim()
            if (slug.isEmpty()) continue
            providers += HermesModelProviderRow(
                slug = slug,
                displayName = p.optString("display_name", p.optString("name", slug)),
                available = p.optBoolean("available", true),
                warning = p.optString("warning", "").takeIf { it.isNotBlank() }
            )
        }
    }
    // Forme supportate: {"models":[...]} oppure {"data":[...]} oppure mappa provider->lista.
    val modelArray: JSONArray? = root.optJSONArray("models")
        ?: root.optJSONArray("data")
        ?: root.optJSONArray("options")
    if (modelArray != null) {
        for (i in 0 until modelArray.length()) {
            parseSingleModelOption(modelArray.optJSONObject(i) ?: continue)?.let { models += it }
        }
    } else {
        // Forma mappa: {"openrouter":[{...}], "nous":{...}}
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "providers" || k == "object" || k == "meta") continue
            val arr = root.optJSONArray(k)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!obj.has("provider")) obj.put("provider", k)
                    parseSingleModelOption(obj)?.let { models += it }
                }
            }
        }
    }
    return HermesModelCatalog(providers = providers, models = models, source = "model-options", raw = body)
}

private fun parseSingleModelOption(obj: JSONObject): HermesModelOption? {
    val id = obj.optString("id", obj.optString("model", obj.optString("name", ""))).trim()
    if (id.isEmpty()) return null
    val provider = obj.optString("provider", obj.optString("provider_slug", "")).trim()
    val caps = mutableListOf<String>()
    obj.optJSONArray("capabilities")?.let { arr ->
        for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotBlank() }?.let { caps += it }
    }
    obj.optJSONArray("capability_hints")?.let { arr ->
        for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotBlank() }?.let { caps += it }
    }
    val ctx = obj.optLong("context_window", -1L).takeIf { it > 0 }
        ?: obj.optLong("context_length", -1L).takeIf { it > 0 }
        ?: obj.optJSONObject("context")?.optLong("window", -1L)?.takeIf { it > 0 }
    val reasoningSupported = obj.optBoolean("reasoning_supported",
        obj.optBoolean("reasoning", caps.any { it.contains("reason", ignoreCase = true) }))
    val efforts = mutableListOf<String>()
    obj.optJSONArray("reasoning_efforts")?.let { arr ->
        for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotBlank() }?.let { efforts += it.lowercase() }
    }
    return HermesModelOption(
        id = id,
        displayName = obj.optString("display_name", obj.optString("label", id)).ifBlank { id },
        provider = provider,
        capabilities = caps.distinct(),
        contextWindow = ctx,
        pricing = obj.optJSONObject("pricing"),
        reasoningSupported = reasoningSupported || efforts.isNotEmpty(),
        reasoningEfforts = efforts.distinct(),
        warning = obj.optString("warning", "").takeIf { it.isNotBlank() },
        extra = obj
    )
}

internal fun parseV1ModelsFallback(body: String): HermesModelCatalog {
    val models = mutableListOf<HermesModelOption>()
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return HermesModelCatalog(source = "v1-models", raw = body)
    val arr = root.optJSONArray("data") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val id = obj.optString("id", "").trim()
        if (id.isEmpty()) continue
        models += HermesModelOption(id = id, displayName = id, provider = "")
    }
    return HermesModelCatalog(models = models, source = "v1-models", raw = body)
}

/**
 * Costruisce model_options ufficiale per le request (chat/completions, responses, runs, session chat).
 * Precedence rispettata lato server; Android non fa routing, invia solo i campi dichiarati.
 */
internal fun buildHermesModelOptions(
    reasoningEffort: String?,
    serviceTier: String? = null,
    capabilities: HermesCapabilities? = null
): JSONObject? {
    val effort = reasoningEffort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val tier = serviceTier?.trim()?.takeIf { it.isNotEmpty() }
    if (effort == null && tier == null) return null
    if (effort != null && capabilities != null) {
        val resolved = resolveReasoningEffortForServer(capabilities, effort)
        // Capability nota ma effort non supportato -> non inviare effort (fail-closed), solo tier.
        if (resolved == null) {
            return if (tier != null) JSONObject().put("service_tier", tier) else null
        }
        if (!resolved.equals(effort, ignoreCase = true) && tier == null) return null
        val opts = JSONObject()
        if (resolved == "none") {
            opts.put("reasoning", JSONObject().put("enabled", false))
            opts.put("reasoning_effort", "none")
        } else {
            opts.put("reasoning", JSONObject().put("enabled", true).put("effort", resolved))
            opts.put("reasoning_effort", resolved)
        }
        if (tier != null) opts.put("service_tier", tier)
        return opts
    }
    // Capabilities sconosciute: invia formato ufficiale verbatim, sara' il server a validare/clampare.
    // Unica eccezione: i template di inferenza dichiarano xhigh come tetto (es. TabbyAPI/EXL3
    // rifiuta "max"/"ultra" con 400), mentre xhigh resta valido anche su Hermes-native.
    // Clampare max/ultra->xhigh evita il 400 senza cambiare significato dove max era accettato.
    val verbatimEffort = when (effort) {
        "max", "ultra" -> "xhigh"
        else -> effort
    }
    if (verbatimEffort != null) {
        val opts = JSONObject()
        if (verbatimEffort == "none") {
            opts.put("reasoning", JSONObject().put("enabled", false))
            opts.put("reasoning_effort", "none")
        } else {
            opts.put("reasoning", JSONObject().put("enabled", true).put("effort", verbatimEffort))
            opts.put("reasoning_effort", verbatimEffort)
        }
        if (tier != null) opts.put("service_tier", tier)
        return opts
    }
    return JSONObject().put("service_tier", tier)
}

internal fun applyHermesModelOverrides(payload: JSONObject, settings: AppSettings, capabilities: HermesCapabilities? = null) {
    // Hermes resta responsabile del routing; Android invia solo model/provider/model_options dichiarati.
    val provider = settings.provider.trim()
    if (provider.isNotEmpty() && !provider.equals("hermes-agent", ignoreCase = true)) {
        payload.put("provider", provider)
    }
    buildHermesModelOptions(settings.reasoningEffort, settings.serviceTier, capabilities)?.let {
        payload.put("model_options", it)
    }
}
