from __future__ import annotations

import json
import re
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_VERSION = "0.6.324"
EXPECTED_ANDROID_VERSION_CODE = 328


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def read_android_sources() -> str:
    source_root = ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java"
    return "\n".join(path.read_text(encoding="utf-8") for path in sorted(source_root.rglob("*.kt")))


def read_android_stream_sources() -> str:
    # Ex ChatStream.kt, spezzato in Stream*.kt (stessa cartella, stesso package).
    stream_root = (
        ROOT / "src" / "NemoclawChat.Android" / "app" / "src" / "main" / "java"
        / "com" / "nemoclaw" / "chat"
    )
    return "\n".join(
        path.read_text(encoding="utf-8") for path in sorted(stream_root.glob("Stream*.kt"))
    )


class ReleaseConsistencyTests(unittest.TestCase):
    def test_gateway_launcher_file_transfer_defaults_are_unlimited(self) -> None:
        launcher = read("scripts/hermes-hub-linux.sh")
        self.assertIn('HERMES_GATEWAY_MAX_REQUEST_MB="${HERMES_GATEWAY_MAX_REQUEST_MB:-0}"', launcher)
        self.assertIn('HERMES_HUB_MAX_UPLOAD_MB="${HERMES_HUB_MAX_UPLOAD_MB:-0}"', launcher)

    def test_application_versions_are_aligned(self) -> None:
        windows_project = read("src/NemoclawChat.Windows/NemoclawChat.Windows.csproj")
        admin_project = read("src/ChatClaw.AdminBridge/ChatClaw.AdminBridge.csproj")
        android_project = read("src/NemoclawChat.Android/app/build.gradle.kts")
        package_manifest = read("src/NemoclawChat.Windows/Package.appxmanifest")

        self.assertIn(f"<Version>{EXPECTED_VERSION}</Version>", windows_project)
        self.assertIn(f"<AssemblyVersion>{EXPECTED_VERSION}.0</AssemblyVersion>", windows_project)
        self.assertIn(f"<FileVersion>{EXPECTED_VERSION}.0</FileVersion>", windows_project)
        self.assertIn(f"<Version>{EXPECTED_VERSION}</Version>", admin_project)
        self.assertIn(f'versionName = "{EXPECTED_VERSION}"', android_project)
        self.assertIn(f"versionCode = {EXPECTED_ANDROID_VERSION_CODE}", android_project)
        self.assertRegex(
            package_manifest,
            rf'<Identity[\s\S]*?Version="{re.escape(EXPECTED_VERSION)}\.0"',
        )

    def test_release_documents_are_current(self) -> None:
        self.assertIn(f"Versione corrente: `{EXPECTED_VERSION}`.", read("README.md"))
        self.assertIn(
            f"Versione corrente: `{EXPECTED_VERSION}`.",
            read("AGENTS.md"),
        )
        self.assertTrue(
            read("release_notes.txt").startswith(f"Hermes Hub {EXPECTED_VERSION} ")
        )
        self.assertIn(f"## {EXPECTED_VERSION} -", read("CHANGELOG.md"))

    def test_android_gateway_paths_are_always_versioned(self) -> None:
        # Regressione 0.6.244: path nudi ({gateway}/chat/completions, /responses,
        # /capabilities, /models, /audio/..., /hub/...) danno 404. Solo /v1, /api, /health.
        sources = read_android_sources()
        bad = sorted(
            set(
                re.findall(
                    r'gatewayUrl\.trimEnd\([^)]*\)\}?/(chat|responses|models|capabilities|audio|hub)/',
                    sources,
                )
            )
        )
        self.assertEqual([], bad)
        for path in (
            'resolveHermesUrl(settings, "/v1/chat/completions")',
            'resolveHermesUrl(settings, "/v1/responses")',
            'resolveHermesUrl(settings, "/v1/capabilities")',
            'resolveHermesUrl(settings, "/v1/models")',
            'resolveHermesUrl(settings, "/v1/audio/transcriptions")',
            'resolveHermesUrl(settings, "/v1/hub/server/action")',
            'resolveHermesUrl(settings, "/v1/hub/server/maintenance")',
            'resolveHermesUrl(settings, "/v1/media/upload")',
            # Libreria immagini live: browse via manager (loadImageBrowse),
            # non il vecchio path gateway /v1/image/library (funzione morta rimossa).
            'loadImageBrowse(settings, managerKey',
        ):
            self.assertIn(path, sources)

    def test_android_reboot_uses_ticket(self) -> None:
        # Riavvio manager via laya (il POST diretto /system/reboot e'
        # controllo laya): niente ticket in app, niente reboot host da app.
        sources = read_android_sources()
        for marker in (
            "riavvia il manager",
            "postLayaCommand",
        ):
            self.assertIn(marker, sources)
        self.assertNotIn('"$managerBase/system/reboot"', sources)

    def test_android_detached_turns_stay_resumable(self) -> None:
        # Kill/rete morta: niente stop server implicito, reattach prima
        # dell'errore, banner di follow. Mai sessione chiusa.
        sources = read_android_sources()
        for marker in (
            "userStopRequested",
            "DetachedFollowBanner(",
            "detachedFollowUp",
            "Connessione persa a turno vivo: recupero la risposta dal server",
            "continue_on_disconnect",
        ):
            self.assertIn(marker, sources)

    def test_android_chat_identity_is_wired(self) -> None:
        # Selettore bot nel titolo chat: identita persistita + sidebar filtrata.
        sources = read_android_sources()
        for marker in (
            "activeChatBotKey",
            "botIdentityKey",
            "resolveChatIdentity(",
            "conversationsForIdentity(",
            "conversationIdentityKey(",
            "chatIdentityOptions(",
            "ChatIdentityPicker(",
            "ChatIdentityTitle(",
            "chatIdentityKey = activeChatBotKey",
            "identityPicker = chatIdentityPicker",
            "openChatConversation(id",
            "pickChatIdentity(",
            'putString("activeChatBotKey"',
        ):
            self.assertIn(marker, sources)

    def test_android_images_section_is_wired(self) -> None:
        sources = read_android_sources()
        for marker in (
            'Images("Immagini"',
            "Tab.Images",
            "ImageScreen(",
            "ImageLibraryItem(",
            "loadImageBrowse(",
            "ImageBrowseFolder(",
            "browseSegments(",
            "encodeMediaPathSegment(",
            "ChatImageGalleryDialog(",
            "HorizontalPager(",
            "rememberLazyListState",
            "extraImageLibraryPaths",
            "parseExtraImageLibraryPaths(",
            'add_get("/v1/image/library", self._handle_image_library)',
            "def _hermes_hub_image_library_payload",
        ):
            if marker.startswith("add_get") or marker.startswith("def _hermes"):
                content = read("scripts/hermes_hub_gateway/adapters/hermes/legacy_patcher.py")
            else:
                content = sources
            self.assertIn(marker, content)

    def test_manager_browse_endpoint_is_guarded(self) -> None:
        manager = read("gpu-manager/manager.py")
        for marker in (
            '@app.get("/browse")',
            "Depends(require_key)",
            "_browse_roots()",
            "_browse_jail(",
            "_browse_scan(",
            "_BROWSE_IMAGE_EXTENSIONS",
            "browse path non consentito",
        ):
            self.assertIn(marker, manager)

    def test_github_repository_defaults_target_hermes_hub(self) -> None:
        expected_slug = "JackoPeru/HermesHub"
        obsolete_slug = "app-interazione-nemoclaw"
        files = (
            "AGENTS.md",
            "CHANGELOG.md",
            "scripts/hermes-hub-linux-update.service",
            "scripts/hermes-hub-linux-update.sh",
            "src/NemoclawChat.Windows/Services/AppUpdateService.cs",
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt",
        )

        contents = {path: read(path) for path in files}
        for path, content in contents.items():
            self.assertNotIn(obsolete_slug, content, path)

        self.assertIn(expected_slug, contents["AGENTS.md"])
        self.assertIn(expected_slug, contents["CHANGELOG.md"])
        self.assertIn(expected_slug, contents["scripts/hermes-hub-linux-update.service"])
        self.assertIn(
            'REPO="${HERMES_HUB_REPO:-JackoPeru/HermesHub}"',
            contents["scripts/hermes-hub-linux-update.sh"],
        )
        self.assertIn(
            'public const string RepositoryName = "HermesHub";',
            contents["src/NemoclawChat.Windows/Services/AppUpdateService.cs"],
        )
        self.assertIn(
            "https://api.github.com/repos/JackoPeru/HermesHub/releases/latest",
            contents[
                "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
            ],
        )

    def test_android_has_one_canonical_gradle_build(self) -> None:
        for stale_file in (
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
        ):
            self.assertFalse((ROOT / stale_file).exists(), stale_file)

        wrapper = read("src/NemoclawChat.Android/gradle/wrapper/gradle-wrapper.properties")
        self.assertIn("gradle-9.7.1-bin.zip", wrapper)
        self.assertIn(
            "distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a",
            wrapper,
        )
        plugins = read("src/NemoclawChat.Android/build.gradle.kts")
        self.assertIn('version "9.2.0"', plugins)
        self.assertIn('version "2.3.21"', plugins)

    def test_official_android_release_is_meta_dat_only_and_fail_closed(self) -> None:
        package_script = read("scripts/package-android-release.ps1")
        android_project = read("src/NemoclawChat.Android/app/build.gradle.kts")
        android_manifest = read("src/NemoclawChat.Android/app/src/main/AndroidManifest.xml")
        dat_frame_source = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesFrameSource.kt"
        )
        dat_setup_bridge = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesSetupBridgeImpl.kt"
        )
        dat_runtime = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesRuntime.kt"
        )
        workflow = read(".github/workflows/quality.yml")
        agents = read("AGENTS.md")

        self.assertIn("-PenableMetaDat=true", package_script)
        self.assertNotIn("allowStandardReleaseForDevelopment", package_script)
        self.assertIn("hermes-release.keystore", package_script)
        self.assertIn("-PhermesAllowDebugSigning=true", package_script)
        self.assertIn("[switch]$CiValidation", package_script)
        self.assertIn("android-DAT-validation-only.apk", package_script)
        self.assertIn("asset CI validation-only, non pubblicabile", package_script)
        for required_guard in (
            "GITHUB_TOKEN",
            "githubPackagesToken",
            "META_DAT_APPLICATION_ID",
            "META_DAT_CLIENT_TOKEN",
            "META_DAT_ENABLED=true",
            "minSdkVersion:'29'",
            "MetaWearablesFrameSource",
            "MetaWearablesSetupBridgeImpl",
            "certificate SHA-256 digest",
            "HermesHub-$Version-android.apk",
        ):
            self.assertIn(required_guard, package_script)
        self.assertIn(
            "2806aca1897c983ef5fe8c2758105e9473e6c682b3ef627ab1d4ec461cf1a5fc",
            package_script,
        )
        self.assertIn(
            "else {\n    $certificateMatch = [regex]::Match($signatureOutput",
            package_script,
        )
        self.assertIn(
            "digest storico non richiesto per artefatto non pubblicabile",
            package_script,
        )

        self.assertIn("allowStandardReleaseForDevelopment", android_project)
        self.assertIn("hermesAllowDebugSigning", android_project)
        self.assertIn('signingConfigs.getByName("hermes")', android_project)
        self.assertIn("containsReleaseOutput", android_project)
        self.assertIn("!enableMetaDat && !allowStandardReleaseForDevelopment", android_project)
        self.assertIn("L'APK standard non deve essere pubblicato", android_project)
        for required_dat_manifest_entry in (
            'android.permission.BLUETOOTH"',
            'android.permission.BLUETOOTH_CONNECT"',
            'com.meta.wearable.mwdat.DAM_ENABLED" android:value="false"',
        ):
            self.assertIn(required_dat_manifest_entry, android_manifest)
        self.assertIn("Wearables.checkPermissionStatus(Permission.CAMERA)", dat_frame_source)
        self.assertIn("createdStream.errorStream.collect", dat_frame_source)
        self.assertNotIn("error != StreamError.STREAM_ERROR", dat_frame_source)
        self.assertIn("DeviceCompatibility.COMPATIBLE", dat_frame_source)
        self.assertIn("DeviceSessionState.PAUSED", dat_frame_source)
        self.assertIn("streamGeneration", dat_frame_source)
        self.assertIn("operationMutex.withLock", dat_frame_source)
        self.assertIn("oldSession.removeStream()", dat_frame_source)
        self.assertIn("withTimeout(STREAM_READY_TIMEOUT_MILLIS)", dat_frame_source)
        self.assertIn("StreamState.STREAMING", dat_frame_source)
        self.assertIn("Wearables.registrationState.first", dat_frame_source)
        self.assertIn("Wearables.devices.flatMapLatest(::connectedDeviceEvents)", dat_frame_source)
        self.assertIn("emitAll(merge(*metadataFlows.toTypedArray()))", dat_frame_source)
        self.assertIn("LINK_STABILIZATION_MILLIS", dat_frame_source)
        self.assertIn("STREAM_READY_TIMEOUT_MILLIS", dat_frame_source)
        self.assertNotIn("STARTUP_ATTEMPTS", dat_frame_source)
        self.assertNotIn("STARTUP_RETRY_DELAY_MILLIS", dat_frame_source)
        self.assertIn("MetaWearablesRuntime.initialize(appContext)", dat_frame_source)
        self.assertIn("MetaWearablesRuntime.initialize(activity.applicationContext)", dat_setup_bridge)
        self.assertIn("WearablesError.ALREADY_INITIALIZED", dat_runtime)
        self.assertIn("AtomicBoolean(false)", dat_runtime)
        jarvis_controller = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/jarvis/JarvisSessionController.kt"
        )
        self.assertIn("routeVoiceBluetooth(context, false)", jarvis_controller)
        self.assertNotIn("routeVoiceBluetooth(context, true)", jarvis_controller)
        self.assertIn("FRAME_SOURCE_START_TIMEOUT_MILLIS = 60_000L", jarvis_controller)
        meta_dat_root = ROOT / "src/NemoclawChat.Android/app/src/metaDat/java"
        initialize_callers = sorted(
            path.relative_to(ROOT).as_posix()
            for path in meta_dat_root.rglob("*.kt")
            if "Wearables.initialize(" in path.read_text(encoding="utf-8")
        )
        self.assertEqual(
            [
                "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesRuntime.kt"
            ],
            initialize_callers,
        )

        self.assertIn("packages: read", workflow)
        self.assertIn("./scripts/package-android-release.ps1", workflow)
        self.assertIn("-CiValidation", workflow)
        self.assertIn("META_DAT_PACKAGES_TOKEN", workflow)
        self.assertNotIn("META_DAT_APPLICATION_ID:", workflow)
        self.assertNotIn("META_DAT_CLIENT_TOKEN:", workflow)
        self.assertNotIn(
            "run: ./gradlew --no-daemon lintRelease testDebugUnitTest assembleRelease",
            workflow,
        )

        self.assertIn(".\\scripts\\package-android-release.ps1", agents)
        self.assertIn("nessun fallback standard", agents)

    def test_fresh_install_contains_no_personal_gateway_defaults(self) -> None:
        defaults = json.loads(read("config/hermes-defaults.json"))
        self.assertEqual(defaults["hermes"]["autoDiscoveryUrls"], [])
        self.assertEqual(defaults["hermes"]["apiUrl"], "")
        self.assertEqual(defaults["hermes"]["healthUrl"], "")
        self.assertEqual(defaults["hermes"]["detailedHealthUrl"], "")

        windows_gateway = read("src/NemoclawChat.Windows/Services/GatewayService.cs")
        self.assertIn("PlugAndPlayGatewayHosts = [];", windows_gateway)

        android_main = read_android_sources()
        android_stream = read_android_stream_sources()
        self.assertIn("plugAndPlayGatewayRoots = emptyList<String>()", android_main)
        self.assertIn("plugAndPlayStreamGatewayRoots = emptyList<String>()", android_stream)

        public_runtime = "\n".join(
            (
                read("src/NemoclawChat.Windows/Services/AppSettings.cs"),
                windows_gateway,
                read("src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/HermesAuth.kt"),
                android_stream,
                android_main,
            )
        )
        self.assertNotIn("http://", read("src/NemoclawChat.Windows/Services/AppSettings.cs"))
        android_defaults = read("src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt")
        self.assertNotIn("http://", android_defaults.split("// private object AppDefaults", 1)[0])
        # Connessione preimpostata voluta: gli unici path personali ammessi
        # sono i default librerie (url/chiave mai hardcoded: vedi
        # test_android_fresh_install_has_preconfigured_connection).
        home_hits = [line for line in public_runtime.splitlines() if "/home/" in line]
        self.assertEqual(
            sorted(home_hits),
            sorted(
                [
                    line
                    for line in home_hits
                    if "/home/matteo/video" in line
                    or "/home/matteo/news" in line
                    or "/home/matteo/.hermes/hub_uploads/denudate" in line
                ]
            ),
        )

    def test_android_backup_never_exports_credentials(self) -> None:
        backup_rules = read("src/NemoclawChat.Android/app/src/main/res/xml/backup_rules.xml")
        extraction_rules = read("src/NemoclawChat.Android/app/src/main/res/xml/data_extraction_rules.xml")
        for rules in (backup_rules, extraction_rules):
            self.assertIn('domain="sharedpref" path="chatclaw_connection_secrets.xml"', rules)
            self.assertIn('domain="sharedpref" path="chatclaw_settings.xml"', rules)
            self.assertIn('domain="sharedpref" path="nemoclaw_settings.xml"', rules)
        exporter = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/LocalBackupExporter.kt"
        )
        main = read_android_sources()
        self.assertIn("if (isSensitiveBackupKey(key)) return@forEach", exporter)
        for marker in ("apikey", "token", "secret", "password", "credential", "authorization"):
            self.assertIn(f'"{marker}"', exporter)
        self.assertNotIn('.put("gatewayApiKey"', exporter)
        self.assertNotIn("apiKey: String?", exporter)
        self.assertIn("exportLocalBackup(context)", main)
        self.assertNotIn("exportLocalBackup(context, apiKey)", main)

    def test_activity_timeline_uses_redacted_cross_platform_wire_schema(self) -> None:
        android_stream = read_android_stream_sources()
        android_archive = read_android_sources()
        windows_archive = read("src/NemoclawChat.Windows/Services/ChatArchiveStore.cs")
        windows_sync = read("src/NemoclawChat.Windows/Services/GatewayService.cs")

        self.assertIn("safeToolPayloadSummary", android_stream)
        self.assertIn("SAFE_TOOL_ARGUMENTS_SUMMARY", android_stream)
        self.assertIn("SAFE_TOOL_RESULT_SUMMARY", android_stream)
        self.assertIn("redactToolRawEvent", android_stream)
        for field in ("kind", "text", "toolId", "toolName", "toolArguments", "toolResult", "toolStatus"):
            self.assertIn(f'.put("{field}"', android_archive)
            self.assertIn(f'JsonPropertyName("{field}")', windows_archive)
        self.assertIn('"reasoning"', android_archive)
        self.assertIn('"progress"', android_archive)
        self.assertIn('"tool"', android_archive)
        self.assertIn("AssistantActivityRedaction.CanonicalizeTimeline", windows_sync)
        self.assertIn("RedactRawEvent", windows_archive)

    def test_activity_timeline_sync_raw_event_and_checkpoint_recovery_are_fail_closed(self) -> None:
        android_stream = read_android_stream_sources()
        android_archive = read_android_sources()
        windows_archive = read("src/NemoclawChat.Windows/Services/ChatArchiveStore.cs")
        windows_sync = read("src/NemoclawChat.Windows/Services/GatewayService.cs")
        windows_home = read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs")

        self.assertIn(
            "activityTimeline = AssistantActivityRedaction.CanonicalizeTimeline(message.ActivityTimeline)",
            windows_sync,
        )
        self.assertIn("rawEvents = (message.RawEvents ?? [])", windows_sync)
        self.assertIn(".Select(AssistantActivityRedaction.RedactRawEvent)", windows_sync)
        self.assertIn('kind = kind == "promptprogress" ? "progress" : kind;', windows_archive)
        self.assertIn("Name = SafeRawEventName", windows_archive)
        self.assertIn("Json = SafeRawEventJson", windows_archive)
        self.assertIn("SanitizeActivityPayloads(ordered);", windows_archive)

        self.assertIn("internal const val SAFE_RAW_EVENT_JSON", android_stream)
        self.assertIn("return SAFE_RAW_EVENT_JSON", android_stream)
        self.assertIn("private fun safeRawHermesEvent", android_archive)
        self.assertIn("rawEvents += safeRawHermesEvent()", android_archive)
        self.assertIn('obj.optJSONArray("RawEvents")', android_archive)
        self.assertIn("SAFE_RAW_EVENT_NAME", android_archive)
        self.assertIn('sourceRunId = "",', android_archive)
        self.assertNotIn("sourceRunId = message.rawEvents", android_archive)
        self.assertIn('!message.TryGetProperty("RawEvents", out rawEvents)', windows_sync)
        self.assertIn("?.Select(AssistantActivityRedaction.RedactRawEvent)", windows_sync)

        self.assertIn("bubble.ActivityTimeline.Count > 0 || finalTextBuilder.Length > 0", windows_home)
        self.assertIn("localState.activityTimeline.isNotEmpty() || localState.text.isNotBlank()", android_archive)
        self.assertIn("if (finalState.activityTimeline.isNotEmpty() || finalText.isNotEmpty()", android_archive)

    def test_android_gateway_secret_storage_fails_closed(self) -> None:
        main = read_android_sources()
        self.assertIn("private fun saveGatewaySecret(context: Context, secret: String?): Boolean", main)
        self.assertIn("}.getOrNull() ?: return false", main)
        self.assertIn("val saved = withContext(Dispatchers.IO) {", main)
        self.assertIn("val secretSaved = saveGatewaySecret(context, candidateSecret)", main)
        secret_save = main.index("val secretSaved = saveGatewaySecret(context, candidateSecret)")
        save_start = main.rfind("val saved = withContext(Dispatchers.IO) {", 0, secret_save)
        # Anchor alla fine del blocco salvataggio: prima il bottone testuale
        # "Salva", ora l'icona con contentDescription (stesso intento fail-closed).
        save_end_markers = ['Text("Salva")', '"Salva impostazioni"']
        save_end = min(
            (main.index(marker, secret_save) for marker in save_end_markers if marker in main[secret_save:]),
            default=-1,
        )
        self.assertGreater(save_end, secret_save)
        save_block = main[save_start:save_end]
        self.assertIn("if (!saved) {", save_block)
        self.assertIn("onSave(candidate)", save_block)
        self.assertLess(save_block.index("if (!saved) {"), save_block.index("onSave(candidate)"))
        self.assertIn("Chiavi non salvate: Android Keystore non disponibile. Credenziali non scritte in chiaro.", main)
        self.assertIn("saveGpuAppSecret", main)
        self.assertNotIn("}.getOrDefault(normalized)", main)

    def test_android_network_badge_requires_real_gateway_probe(self) -> None:
        main = read_android_sources()
        self.assertIn("probeHermesGatewayDetailed(probeSettings, botApiKey)", main)
        self.assertIn("val botAllowCompatAuth = !remoteBot", main)
        self.assertIn('resolveHermesUrl(settings, "/v1/capabilities")', main)
        self.assertIn('if (!isValidGatewayProbeUrl(url)) return false to "', main)
        self.assertIn("connected = gatewayAvailable", main)
        self.assertNotIn("connected = online", main)
        self.assertIn("gatewayRuntimeLabel(connected, gatewayRuntime)", main)
        self.assertIn('resolveHermesUrl(settings, "/v1/hub/runtime")', main)
        self.assertIn(
            "if (connected) AppColors.Success else AppColors.Error",
            main,
        )

    def test_android_media_auth_is_scoped_to_configured_hermes_origin(self) -> None:
        main = read_android_sources()
        self.assertIn(
            "val needsHermesAuth = shouldAuthenticateHermesUrl(settings, candidateUrl)",
            main,
        )
        self.assertIn(
            "val needsHermesAuth = parsed != null && shouldAuthenticateHermesUrl(settings, url)",
            main,
        )
        self.assertIn(
            "if (token.isBlank() || !shouldAuthenticateHermesUrl(settings, url))",
            main,
        )
        security = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/HermesUrlSecurity.kt"
        )
        self.assertIn("sameHttpOrigin(target, URI(configured", security)
        self.assertIn("effectivePort(left) == effectivePort(right)", security)
        self.assertIn('ClipData.newPlainText("hermes-media-url", url)', main)

        windows_home = read("src/NemoclawChat.Windows/Pages/HomePage.xaml.cs")
        self.assertIn("ResolveMediaUri(value, includeQueryToken: false)", windows_home)

    def test_meta_dat_registration_is_not_repeated_and_runtime_is_not_reset(self) -> None:
        bridge = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesSetupBridgeImpl.kt"
        )
        frame = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesFrameSource.kt"
        )
        runtime = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesRuntime.kt"
        )
        self.assertIn('registrationStatus.equals("REGISTERED"', bridge)
        self.assertIn("App gia registrata", bridge)
        self.assertIn("MetaWearablesRuntime.initialize", frame)
        self.assertIn("Wearables.initialize(", runtime)
        self.assertNotIn("Wearables.initialize(", bridge)
        self.assertNotIn("Wearables.initialize(", frame)
        self.assertNotIn("MetaWearablesRuntime.reset", bridge + frame + runtime)

    def test_meta_dat_device_selection_requires_connected_and_started_stream(self) -> None:
        frame = read(
            "src/NemoclawChat.Android/app/src/metaDat/java/com/nemoclaw/chat/jarvis/meta/MetaWearablesFrameSource.kt"
        )
        self.assertIn("LinkState.CONNECTED", frame)
        self.assertIn("SpecificDeviceSelector(deviceId)", frame)
        self.assertIn("Wearables.createSession(", frame)
        self.assertIn("DeviceSessionState.STARTED", frame)

    def test_android_manifest_declares_network_camera_and_meta_dat_integration(self) -> None:
        manifest = read("src/NemoclawChat.Android/app/src/main/AndroidManifest.xml")
        self.assertIn("android.permission.INTERNET", manifest)
        self.assertIn("android.permission.CAMERA", manifest)
        self.assertIn("android.permission.BLUETOOTH\"", manifest)
        self.assertIn("android.permission.BLUETOOTH_CONNECT", manifest)
        self.assertIn("mwdat.DAM_ENABLED", manifest)
        self.assertIn('android:value="false"', manifest)

    def test_linux_updater_uses_explicit_lock_and_refuses_downgrade_and_quarantines(self) -> None:
        updater = read("scripts/hermes-hub-linux-update.sh")
        self.assertIn("LOCK_FILE", updater)
        self.assertIn("flock -n", updater)
        self.assertIn("ALLOW_DOWNGRADE", updater)
        self.assertIn("downgrade refused", updater)
        self.assertIn("FAILED_RELEASE_FILE", updater)
        self.assertIn("record_failed_release", updater)
        self.assertIn("Quarantined failed release", updater)
        # Lockdown 0.6.293+: la chiave gateway e' revocata sul manager;
        # il busy check usa la chiave app root-only via sudo.
        self.assertIn("/etc/hermes/keys/app.key", updater)
        self.assertIn("sudo -n cat", updater)
        # Status lenta sotto carico: timeout lungo + fallback su /jobs.
        self.assertIn("manager_jobs_fallback_reason", updater)
        self.assertIn("MANAGER_STATUS_MAX_TIME", updater)

    def test_android_fresh_install_has_preconfigured_connection(self) -> None:
        app_settings = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/settings/AppSettings.kt"
        )
        self.assertIn('gatewayUrl = "http://100.94.223.14:8642"', app_settings)
        self.assertIn('localGatewayUrl = "http://192.168.1.6:8642"', app_settings)
        self.assertIn('videoLibraryPath = "/home/matteo/video"', app_settings)
        self.assertIn('newsLibraryPath = "/home/matteo/news"', app_settings)
        self.assertIn('imageLibraryPath = "/home/matteo/.hermes/hub_uploads/denudate"', app_settings)
        android_project = read("src/NemoclawChat.Android/app/build.gradle.kts")
        # Audit 0.6.277 (decisione utente: "Rimuovi chiave"): nessuna chiave
        # di default nel APK. Il pairing avviene dall'app (KeyStore/SecretStore).
        self.assertNotIn("HERMES_DEFAULT_API_KEY", android_project)
        secret_store = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/auth/GatewaySecretStore.kt"
        )
        self.assertNotIn("defaultGatewayApiKey()", secret_store)
        # Il valore vero vive solo nell'app dopo il pairing: mai nel APK,
        # mai nel codice (vedi test_git_tracks_no_forbidden_artifacts_or_secrets).
        android_sources = read_android_sources()
        self.assertNotIn("hermes.defaultApiKey=", android_sources)
        self.assertNotIn("hermes.defaultApiKeyJavaString", android_sources)

    def test_git_tracks_no_forbidden_artifacts_or_secrets(self) -> None:
        tracked = (
            subprocess.run(
                ["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True
            )
            .stdout.decode("utf-8", errors="replace")
            .split("\0")
        )
        forbidden = (
            ".apk",
            ".msix",
            ".tar.gz",
            ".tgz",
            ".jks",
            ".keystore",
            ".p12",
            ".pem",
            ".env",
            "local.properties",
            "__pycache__",
        )
        bad = [path for path in tracked if path.endswith(forbidden)]
        self.assertEqual([], bad)

    def test_jarvis_single_tap_uses_single_startup_job_under_lifecycle_mutex(self) -> None:
        controller = read(
            "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/jarvis/JarvisSessionController.kt"
        )
        self.assertIn("private val lifecycleMutex = Mutex()", controller)
        self.assertIn("startupJob?.cancel()", controller)
        self.assertIn("lifecycleMutex.withLock", controller)
        self.assertIn("startupJob = job", controller)
        self.assertIn("job.start()", controller)
        self.assertIn("if (startupJob === job) startupJob = null", controller)
        self.assertNotIn("STARTUP_ATTEMPTS", controller)
        self.assertNotIn("STARTUP_RETRY_DELAY", controller)

    def test_android_version_code_never_decreases_in_git_history(self) -> None:
        # Play-Store monotonicita: nessun versionCode mai committato puo
        # superare quello atteso (downgrade = update rifiutato).
        log = (
            subprocess.run(
                ["git", "log", "-p", "--follow", "--", "src/NemoclawChat.Android/app/build.gradle.kts"],
                cwd=ROOT,
                capture_output=True,
                check=True,
            )
            .stdout.decode("utf-8", errors="replace")
        )
        codes = [int(match) for match in re.findall(r"^[+-]\s*versionCode\s*=\s*(\d+)", log, re.MULTILINE)]
        self.assertGreater(len(codes), 0)
        self.assertLessEqual(max(codes), EXPECTED_ANDROID_VERSION_CODE)

    def test_release_notes_assets_exist_as_build_files(self) -> None:
        # Ogni asset citato in release_notes.txt per la versione corrente
        # deve esistere come file di build in artifacts/ o release-assets/.
        notes = read("release_notes.txt")
        expected_assets = {
            f"HermesHub-{EXPECTED_VERSION}-android.apk",
            f"HermesHub-{EXPECTED_VERSION}-linux-gateway.tar.gz",
            f"NemoclawChat.Windows_{EXPECTED_VERSION}.0_x64.msix",
        }
        for asset in sorted(expected_assets):
            self.assertIn(asset, notes)
        names: set[str] = set()
        for root in (ROOT / "artifacts", ROOT / "release-assets"):
            if root.is_dir():
                names.update(path.name for path in root.rglob("*") if path.is_file())
        self.assertEqual([], sorted(a for a in expected_assets if a not in names))


if __name__ == "__main__":
    unittest.main()


