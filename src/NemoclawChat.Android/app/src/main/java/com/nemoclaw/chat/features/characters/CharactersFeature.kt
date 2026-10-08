package com.nemoclaw.chat.features.characters

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import com.nemoclaw.chat.AppSettings
import com.nemoclaw.chat.PollWhileStarted
import com.nemoclaw.chat.httpGetResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun CharactersScreen(context: Context, settings: AppSettings, onOpenVideo: () -> Unit) {
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var wizardOpen by rememberSaveable { mutableStateOf(false) }
    var listBump by rememberSaveable { mutableIntStateOf(0) }
    val id = detailId
    if (wizardOpen) {
        BackHandler { wizardOpen = false }
        CreateCharacterWizard(context, settings, onDone = { created -> wizardOpen = false; detailId = created }, onCancel = { wizardOpen = false })
        return
    }
    if (id != null) {
        BackHandler { detailId = null; listBump++ }
        CharacterDetail(context, settings, id, onBack = { detailId = null; listBump++ }, onOpenVideo = onOpenVideo)
        return
    }
    var items by remember { mutableStateOf<List<CharacterSummary>>(emptyList()) }
    var status by remember { mutableStateOf("Carico personaggi…") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            val (list, message) = loadCharacters(settings, managerKeyOf(context))
            items = list
            status = message
        }
    }
    PollWhileStarted("list", refresh, listBump, baseIntervalMs = 15000) {
        val (list, message) = loadCharacters(settings, managerKeyOf(context))
        items = list
        status = message
        true
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Personaggi", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Identità persistenti per i video H3, tutte locali.", color = AppColors.Muted, fontSize = 13.sp)
                }
                Row {
                    IconButton(onClick = { refresh++; reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna", tint = Color.White)
                    }
                    IconButton(onClick = { wizardOpen = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Crea personaggio", tint = Color.White)
                    }
                }
            }
        }
        item { Text(status, color = AppColors.Faint, fontSize = 12.sp) }
        if (items.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nessun personaggio", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Tocca + : dai un nome, carica 20-80 foto della stessa persona, Hermes fa il resto.", color = AppColors.Muted, fontSize = 13.sp)
                        Button(onClick = { wizardOpen = true }) { Text("Crea personaggio") }
                    }
                }
            }
        } else {
            item {
                CharacterGrid(items = items, onOpen = { detailId = it }, onUse = { detailId = it })
            }
        }
    }
}

@Composable
private fun CharacterGrid(items: List<CharacterSummary>, onOpen: (String) -> Unit, onUse: (String) -> Unit) {
    // Righe da 2 nella LazyColumn madre: niente griglia annidata, niente clip
    // con molti personaggi (il calcolo altezza fissa tagliava oltre ~14 card).
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        CharacterCard(item = item, onOpen = { onOpen(item.id) }, onUse = { onUse(item.id) })
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CharacterCard(item: CharacterSummary, onOpen: () -> Unit, onUse: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(20.dp),
        onClick = onOpen
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = AppColors.Panel, modifier = Modifier.size(44.dp)) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(item.name.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(characterStatusLabel(item.status), color = AppColors.Muted, fontSize = 12.sp)
                }
            }
            val score = item.identityScore
            Text(
                if (score != null) "Identità ${(score * 100).roundToInt()}% · Auto · ${if (item.recommendedEngine == "lora") "LoRA" else "Reference"}"
                else "Foto: ${item.imageCount}",
                color = AppColors.Muted, fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUse, enabled = item.status == "ready") { Text("Usa") }
                TextButton(onClick = onOpen) { Text("Apri") }
            }
        }
    }
}

@Composable
private fun CreateCharacterWizard(context: Context, settings: AppSettings, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    var name by rememberSaveable { mutableStateOf("") }
    var characterId by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val managerKey = remember { managerKeyOf(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        val id = characterId ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        message = "Carico ${uris.size} foto…"
        scope.launch {
            // Batch da 10: un unico multipart da 80 foto supererebbe timeout/RAM.
            var uploaded = 0
            val failedReasons = mutableListOf<String>()
            for (batch in uris.take(80).chunked(10)) {
                val result = uploadCharacterPhotos(context, settings, managerKey, id, batch)
                uploaded += result.first
                failedReasons.addAll(result.third)
                if (result.first < batch.size) break
            }
            message = if (uploaded > 0) {
                "Caricate $uploaded foto." + if (failedReasons.isNotEmpty()) " Scartate: ${failedReasons.take(3).joinToString("; ")}" else ""
            } else {
                failedReasons.firstOrNull() ?: "Nessuna foto caricata."
            }
            busy = false
            if (uploaded > 0) step = 3
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Indietro", tint = Color.White) }
                Text("Crea personaggio · passo $step di 5", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        when (step) {
            1 -> item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Nome", color = Color.White, fontWeight = FontWeight.SemiBold)
                        TextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Sofia") }, modifier = Modifier.fillMaxWidth())
                        Button(
                            enabled = name.isNotBlank() && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val (created, info) = createCharacter(settings, managerKey, name.trim())
                                    busy = false
                                    if (created != null) {
                                        characterId = created
                                        step = 2
                                        message = ""
                                    } else message = info
                                }
                            }
                        ) { Text("Continua") }
                        if (message.isNotBlank()) Text(message, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            }
            2 -> item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Aggiungi foto", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Carica 20-80 foto della stessa persona (jpg/png/webp, max 15MB). Più angoli ed espressioni = identità migliore.", color = AppColors.Muted, fontSize = 13.sp)
                        Button(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("Scegli foto") }
                        if (busy) CircularProgressIndicator()
                        if (message.isNotBlank()) Text(message, color = AppColors.Muted, fontSize = 12.sp)
                    }
                }
            }
            3, 4 -> item {
                AnalyzeStep(context, settings, characterId = characterId, onReady = { step = 5 }, message = message, onMessage = { message = it })
            }
            5 -> item {
                ReadyStep(context, settings, characterId = characterId, name = name, onDone = onDone, message = message, onMessage = { message = it })
            }
        }
    }
}

@Composable
private fun AnalyzeStep(context: Context, settings: AppSettings, characterId: String?, onReady: () -> Unit, message: String, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val managerKey = remember { managerKeyOf(context) }
    var state by remember { mutableStateOf("draft") }
    var job by remember { mutableStateOf<CharacterJob?>(null) }
    var counts by remember { mutableStateOf("") }
    PollWhileStarted(characterId, baseIntervalMs = 3000) {
        if (characterId == null) return@PollWhileStarted true
        val (current, active, _) = loadCharacterStatus(settings, managerKey, characterId)
        state = current
        job = active
        if (current == "ready_to_train") {
            val manifest = loadCharacterManifest(settings, managerKey, characterId)
            counts = "${manifest?.optInt("image_count", 0)} foto utilizzabili"
            onReady()
        } else {
            val (good, warn, bad) = loadImageVerdicts(settings, managerKey, characterId)
            if (good + warn + bad > 0) counts = "✓ $good buone · ⚠ $warn dubbie · ✕ $bad scartate"
        }
        if (active?.status == "failed") onMessage(active.error.ifBlank { "Analisi fallita" })
        true
    }
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Analisi foto", color = Color.White, fontWeight = FontWeight.SemiBold)
            val active = job
            if (active != null && state == "analyzing") {
                LinearProgressIndicator(progress = { active.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Analisi ${ (active.progress * 100).toInt()}% · ${active.detail}", color = AppColors.Muted, fontSize = 12.sp)
            } else {
                Text("Stato: ${characterStatusLabel(state)}", color = AppColors.Muted, fontSize = 13.sp)
                Button(onClick = {
                    scope.launch {
                        val (code, body) = postCharacterAction(settings, managerKey, characterId ?: return@launch, "analyze")
                        onMessage(if (code in 200..299) "Analisi avviata" else "Analisi HTTP $code")
                    }
                }) { Text("Avvia analisi") }
            }
            if (counts.isNotBlank()) Text(counts, color = Color.White, fontSize = 13.sp)
            if (message.isNotBlank()) Text(message, color = AppColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReadyStep(context: Context, settings: AppSettings, characterId: String?, name: String, onDone: (String) -> Unit, message: String, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val managerKey = remember { managerKeyOf(context) }
    var manifest by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(characterId) {
        manifest = characterId?.let { loadCharacterManifest(settings, managerKey, it) }
    }
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val count = manifest?.optInt("image_count", 0) ?: 0
            Text("Pronto a creare $name", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("$count foto pronte per il training.", color = AppColors.Muted, fontSize = 13.sp)
            DatasetQualityView(manifest)
            Button(
                enabled = !busy && count >= 20,
                onClick = {
                    busy = true
                    scope.launch {
                        val (code, body) = postCharacterAction(settings, managerKey, characterId ?: return@launch, "train")
                        busy = false
                        if (code in 200..299) onDone(characterId) else onMessage("Training HTTP $code")
                    }
                }
            ) { Text(if (busy) "Avvio…" else "Crea ${name.ifBlank { "personaggio" }}") }
            if (count < 20) Text("Servono almeno 20 foto utilizzabili: aggiungine altre e riavvia l'analisi.", color = AppColors.Accent, fontSize = 12.sp)
            if (message.isNotBlank()) Text(message, color = AppColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DatasetQualityView(manifest: JSONObject?) {
    if (manifest == null) return
    val refs = manifest.optJSONObject("references")
    val slots = listOf("front" to "Frontale", "three_quarter_left" to "3/4 sinistra", "three_quarter_right" to "3/4 destra", "profile_left" to "Profilo sx", "profile_right" to "Profilo dx", "full_body" to "Figura intera")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Qualità dataset", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        slots.forEach { (key, label) ->
            val ok = refs?.optString(key)?.isNotBlank() == true
            Text("${if (ok) "✓" else "·"} $label", color = if (ok) Color.White else AppColors.Muted, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterDetail(context: Context, settings: AppSettings, id: String, onBack: () -> Unit, onOpenVideo: () -> Unit) {
    val scope = rememberCoroutineScope()
    val managerKey = remember { managerKeyOf(context) }
    var manifest by remember { mutableStateOf<JSONObject?>(null) }
    var state by remember { mutableStateOf("draft") }
    var job by remember { mutableStateOf<CharacterJob?>(null) }
    var message by remember { mutableStateOf("") }
    var generateOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    PollWhileStarted(id, baseIntervalMs = 3000) {
        manifest = loadCharacterManifest(settings, managerKey, id)
        val (current, active, _) = loadCharacterStatus(settings, managerKey, id)
        state = current
        job = active
        true
    }
    val name = manifest?.optString("name").orEmpty().ifBlank { "Personaggio" }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Indietro", tint = Color.White) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(characterStatusLabel(state), color = AppColors.Muted, fontSize = 13.sp)
                }
                Surface(shape = CircleShape, color = AppColors.Panel, modifier = Modifier.size(48.dp)) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
        val active = job
        if (active != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Creazione di $name", color = Color.White, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { active.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(trainingPhaseLabel(active), color = AppColors.Muted, fontSize = 12.sp)
                        if (active.detail.isNotBlank()) Text(active.detail, color = AppColors.Faint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val (code, _) = postCharacterAction(settings, managerKey, id, "cancel")
                                    message = if (code in 200..299) "Creazione annullata" else "Annulla HTTP $code"
                                }
                            }) { Text("Annulla") }
                            TextButton(onClick = { advanced = !advanced }) { Text("Dettagli tecnici") }
                        }
                        if (advanced) {
                            Text("Job ${active.id} · ${active.kind} · ${active.status}", color = AppColors.Faint, fontSize = 11.sp)
                            if (active.error.isNotBlank()) Text(active.error, color = AppColors.Accent, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.Surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val count = manifest?.optInt("image_count", 0) ?: 0
                    val metrics = manifest?.optJSONObject("metrics")
                    val score = metrics?.optDouble("default_identity_score", Double.NaN)
                    Text("Foto: $count · Versione: v${manifest?.optInt("training_version", 0) ?: 0}", color = Color.White, fontSize = 13.sp)
                    if (score != null && !score.isNaN()) Text("Identità ${(score * 100).roundToInt()}% · Consigliato: ${manifest?.optJSONObject("identity")?.optString("recommended_engine")}", color = AppColors.Muted, fontSize = 13.sp)
                    DatasetQualityView(manifest)
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = state == "ready", onClick = { generateOpen = true }) { Text("Usa personaggio") }
                OutlinedButton(enabled = state == "ready_to_train" || state == "failed" || state == "interrupted" || state == "needs_retrain", onClick = {
                    scope.launch {
                        val (code, _) = postCharacterAction(settings, managerKey, id, "train")
                        message = if (code in 200..299) "Creazione avviata" else "Training HTTP $code"
                    }
                }) { Text("Crea / Ripeti") }
                OutlinedButton(onClick = { renameOpen = !renameOpen }) { Text("Rinomina") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val (code, _) = postCharacterAction(settings, managerKey, id, "export", JSONObject().put("include_originals", false))
                        message = if (code in 200..299) "Esportato (.hcid)" else "Export HTTP $code"
                    }
                }) { Text("Esporta") }
                OutlinedButton(onClick = {
                    if (!confirmDelete) {
                        confirmDelete = true
                        message = "Tocca di nuovo Elimina per confermare (cancella tutto)."
                    } else scope.launch {
                        val (code, _) = deleteCharacter(settings, managerKey, id)
                        if (code in 200..299) onBack() else message = "Elimina HTTP $code"
                    }
                }) { Text(if (confirmDelete) "Confermi?" else "Elimina") }
            }
            if (renameOpen) {
                var newName by rememberSaveable(id) { mutableStateOf(name) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        scope.launch {
                            val (code, _) = renameCharacter(settings, managerKey, id, newName.trim())
                            message = if (code in 200..299) "Rinominato" else "Rinomina HTTP $code"
                            renameOpen = false
                        }
                    }) { Text("OK") }
                }
            }
            if (message.isNotBlank()) Text(message, color = AppColors.Muted, fontSize = 12.sp)
        }
        if (generateOpen) {
            item {
                val currentStrength = manifest?.optJSONObject("identity")?.optDouble("lora_strength", 0.9)?.toString() ?: "0.9"
                GenerateSheet(context, settings, id = id, name = name, strength = currentStrength, onClose = { generateOpen = false }, onOpenVideo = onOpenVideo)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerateSheet(context: Context, settings: AppSettings, id: String, name: String, strength: String, onClose: () -> Unit, onOpenVideo: () -> Unit) {
    val scope = rememberCoroutineScope()
    val managerKey = remember { managerKeyOf(context) }
    var prompt by rememberSaveable(id) { mutableStateOf("") }
    var identityMode by rememberSaveable(id) { mutableStateOf("auto") }
    var duration by rememberSaveable(id) { mutableIntStateOf(5) }
    var aspect by rememberSaveable(id) { mutableStateOf("16:9") }
    var seedText by rememberSaveable(id) { mutableStateOf("42") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.Panel), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Genera con $name", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            TextField(value = prompt, onValueChange = { prompt = it }, placeholder = { Text("walking through Tokyo at night… (@$name funziona)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Personaggio", color = AppColors.Muted, fontSize = 13.sp)
                Surface(shape = RoundedCornerShape(10.dp), color = AppColors.Surface, modifier = Modifier.weight(1f)) {
                    Text(name, color = Color.White, modifier = Modifier.padding(10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Identità", color = AppColors.Muted, fontSize = 13.sp)
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { modeMenu = true }) {
                        Text(if (identityMode == "auto") "Auto" else if (identityMode == "lora") "Character LoRA" else "Reference")
                    }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DropdownMenuItem(text = { Text("Auto") }, onClick = { identityMode = "auto"; modeMenu = false })
                        DropdownMenuItem(text = { Text("Character LoRA") }, onClick = { identityMode = "lora"; modeMenu = false })
                        DropdownMenuItem(text = { Text("Reference") }, onClick = { identityMode = "reference"; modeMenu = false })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10).forEach { seconds ->
                    OutlinedButton(onClick = { duration = seconds }) { Text(if (duration == seconds) "✓ ${seconds}s" else "${seconds}s") }
                }
                Spacer(modifier = Modifier.width(4.dp))
                listOf("16:9", "9:16", "1:1").forEach { ratio ->
                    OutlinedButton(onClick = { aspect = ratio }) { Text(if (aspect == ratio) "✓ $ratio" else ratio) }
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text("Impostazioni avanzate identità") }
            if (advanced) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Seed", color = AppColors.Muted, fontSize = 13.sp)
                    TextField(value = seedText, onValueChange = { seedText = it.filter { c -> c.isDigit() }.take(9) }, singleLine = true, modifier = Modifier.width(140.dp))
                }
                Text("Durata: ${duration}s · Formato: $aspect · Strength: $strength (dal server, non modificabile qui).", color = AppColors.Faint, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = prompt.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        result = "Accodo generazione…"
                        scope.launch {
                            val seed = seedText.toLongOrNull() ?: 42L
                            val (jobId, info) = generateCharacterVideo(settings, managerKey, id, prompt.trim(), identityMode, duration, aspect, seed)
                            busy = false
                            result = if (jobId != null) {
                                pollCharacterJob(context, settings, managerKey, jobId)
                            } else info
                        }
                    }
                ) { Text(if (busy) "…" else "Genera") }
                TextButton(onClick = onClose) { Text("Chiudi") }
                TextButton(onClick = onOpenVideo) { Text("Video") }
            }
            if (result.isNotBlank()) Text(result, color = AppColors.Muted, fontSize = 12.sp)
        }
    }
}

private suspend fun pollCharacterJob(context: Context, settings: AppSettings, managerKey: String?, jobId: String): String =
    withContext(Dispatchers.IO) {
        runCatching {
            val base = charactersBase(settings)
            repeat(120) {
                kotlinx.coroutines.delay(5000)
                val (code, body) = httpGetResponse("$base/jobs/$jobId", managerKey)
                if (code !in 200..299) return@runCatching "Job $jobId in coda (stato $code)"
                val root = JSONObject(body)
                when (root.optString("status")) {
                    "done" -> {
                        val paths = root.optJSONArray("result_paths")
                        val count = paths?.length() ?: 0
                        return@runCatching "Video pronto ($count file). Lo trovi nella sezione Video."
                    }
                    "failed" -> return@runCatching "Generazione fallita: ${root.optString("error").take(160)}"
                    "cancelled" -> return@runCatching "Generazione annullata."
                }
            }
            "Job $jobId ancora in coda: controlla la sezione Video."
        }.getOrElse { "Stato job non disponibile." }
    }
