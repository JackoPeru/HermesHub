"""Firme dispositivo + push FCM fine-job: il telefono firma le azioni server
(P-256, anti-replay) e riceve una notifica quando un job finisce.

v1: enforcement sul gateway per server action/maintenance (solo Android le
chiama; Windows non le usa), registro device, FCM via service account
operatore (opzionale), notifica manager->gateway a fine job.
"""

import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

PATCHER = REPO / "scripts/hermes_hub_gateway/adapters/hermes/legacy_patcher.py"
MANAGER = REPO / "gpu-manager/manager.py"
DEVICE_KEYS = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/security/DeviceKeys.kt"
HTTP_CLIENT = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/core/network/HermesHttpClient.kt"
SERVER_UI = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/features/server/ServerFeature.kt"
PUSH_SERVICE = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/HermesPushService.kt"
MANIFEST = REPO / "src/NemoclawChat.Android/app/src/main/AndroidManifest.xml"
GRADLE = REPO / "src/NemoclawChat.Android/app/build.gradle.kts"
MAIN_ACTIVITY = REPO / "src/NemoclawChat.Android/app/src/main/java/com/nemoclaw/chat/MainActivity.kt"
AND_TEST = REPO / "src/NemoclawChat.Android/app/src/test/java/com/nemoclaw/chat/DeviceKeysTest.kt"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class DeviceSigningTests(unittest.TestCase):
    def test_gateway_device_registry_and_verify(self) -> None:
        src = text(PATCHER)
        for marker in (
            "_hermes_hub_device_store_path",
            "_hermes_hub_device_verify_signature",
            "_hermes_hub_require_device_signature",
            "X-Hermes-Signature",
            "X-Hermes-Nonce",
            "X-Hermes-Timestamp",
            "X-Hermes-Key-Id",
            "openssl",
            '"dgst", "-sha256", "-verify"',
            "device signature required",
            "HERMES_HUB_DEVICE_SIGNATURE",
        ):
            self.assertIn(marker, src)

    def test_gateway_enforces_signature_on_dangerous_routes(self) -> None:
        src = text(PATCHER)
        # Un'unica replacement condivisa, applicata a entrambe le rotte.
        self.assertIn("device_error = await _hermes_hub_require_device_signature(request)", src)
        self.assertIn('for handler_name in ("_handle_hub_server_action", "_handle_hub_server_maintenance")', src)
        for marker in (
            '("GET", "/v1/hub/devices", self._handle_hub_devices)',
            '("POST", "/v1/hub/devices/register", self._handle_hub_device_register)',
            '("POST", "/v1/hub/devices/{device_id}/token", self._handle_hub_device_token)',
            '("DELETE", "/v1/hub/devices/{device_id}", self._handle_hub_device_revoke)',
        ):
            self.assertIn(marker, src)

    def test_android_signs_with_keystore_and_biometric(self) -> None:
        keys = text(DEVICE_KEYS)
        for marker in (
            "AndroidKeyStore",
            "secp256r1",
            "SHA256withECDSA",
            "canonicalDevicePayload",
            "authenticateDeviceOwner",
            "BiometricPrompt",
            "DEVICE_CREDENTIAL",
            "newDeviceId",
        ):
            self.assertIn(marker, keys)
        client = text(HTTP_CLIENT)
        self.assertIn("postDeviceSignedJson", client)
        ui = text(SERVER_UI)
        for marker in (
            "postServerControlSigned",
            "postDeviceSignedJson",
            "TELEFONO SICURO",
            "Registra telefono",
        ):
            self.assertIn(marker, ui)
        self.assertIn("FragmentActivity", text(MAIN_ACTIVITY))

    def test_android_unit_test_covers_canonical(self) -> None:
        self.assertIn("canonical payload joins five lines", text(AND_TEST))

    def test_r8_keeps_security_classes(self) -> None:
        # 0.6.291: R8 full mode aveva rimosso BiometricPrompt dalla release.
        proguard = (
            REPO / "src/NemoclawChat.Android/app/proguard-rules.pro"
        ).read_text(encoding="utf-8")
        for marker in (
            "androidx.biometric.**",
            "androidx.fragment.app.FragmentActivity",
            "com.google.android.gms.tasks.**",
            "com.nemoclaw.chat.HermesPushService",
            "com.nemoclaw.chat.core.security.DeviceKeysKt",
        ):
            self.assertIn(marker, proguard)


class JobPushTests(unittest.TestCase):
    def test_gateway_fcm_fanout(self) -> None:
        src = text(PATCHER)
        for marker in (
            "_hermes_hub_fcm_access_token",
            "_hermes_hub_fcm_send",
            "_hermes_hub_fanout_job_notify",
            '("GET", "/v1/hub/push-config", self._handle_hub_push_config)',
            '("POST", "/v1/hub/jobs/notify", self._handle_hub_job_notify)',
            "HERMES_HUB_FCM_SERVICE_ACCOUNT",
            "HERMES_HUB_FCM_CLIENT_JSON",
            "fcm.googleapis.com",
            "oauth2.googleapis.com",
        ):
            self.assertIn(marker, src)

    def test_manager_notifies_gateway_on_terminal_states(self) -> None:
        src = text(MANAGER)
        self.assertIn("def notify_gateway_job", src)
        self.assertIn("_JOB_NOTIFY_SENT", src)
        self.assertIn("/v1/hub/jobs/notify", src)

    def test_android_receives_push(self) -> None:
        service = text(PUSH_SERVICE)
        for marker in (
            "FirebaseMessagingService",
            "onMessageReceived",
            "onNewToken",
            "hermes_jobs",
        ):
            self.assertIn(marker, service)
        manifest = text(MANIFEST)
        self.assertIn("HermesPushService", manifest)
        self.assertIn("com.google.firebase.MESSAGING_EVENT", manifest)
        gradle = text(GRADLE)
        self.assertIn("firebase-messaging", gradle)
        self.assertIn("androidx.biometric:biometric", gradle)
        ui = text(SERVER_UI)
        self.assertIn("NOTIFICHE PUSH", ui)
        self.assertIn("Attiva notifiche", ui)


if __name__ == "__main__":
    unittest.main()
