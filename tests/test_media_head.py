"""Media proxy HEAD: i player nativi (WinUI MediaPlayerElement) sondano con
HEAD prima di aprire lo stream. Senza rotta HEAD il gateway rispondeva 405
e il player non partiva mai (il browser con GET+Range funzionava).

Il ramo HEAD risponde solo header (mai transcodifica: un HEAD su
?format=mp4 non deve accodare un job ffmpeg).
"""

import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PATCHER = REPO / "scripts/hermes_hub_gateway/adapters/hermes/legacy_patcher.py"
VIDEO_PAGE = REPO / "src/NemoclawChat.Windows/Pages/VideoPage.xaml.cs"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class MediaHeadTests(unittest.TestCase):
    def test_head_route_registered(self) -> None:
        src = text(PATCHER)
        self.assertIn('("HEAD", "/v1/media/{media_id:.*}", self._handle_hub_media)', src)
        self.assertIn('("media_proxy", ("GET/HEAD", "/v1/media/{media_id}"))', src)

    def test_head_branch_answers_headers_without_transcode(self) -> None:
        src = text(PATCHER)
        head = src.index('getattr(request, "method", "GET") == "HEAD"')
        # Il ramo HEAD precede la transcodifica: mai ffmpeg su una sonda.
        transcode = src.index('_hermes_hub_transcode_mp4, path', head)
        self.assertGreater(transcode, head)
        branch = src[head:transcode]
        self.assertIn("Accept-Ranges", branch)
        self.assertIn("Content-Length", branch)
        self.assertIn("video/mp4", branch)

    def test_windows_player_logs_failures(self) -> None:
        src = text(VIDEO_PAGE)
        self.assertIn("MediaFailed url=", src)
        self.assertIn("[VideoPage] Feed:", src)


if __name__ == "__main__":
    unittest.main()
