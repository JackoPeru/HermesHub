"""Unit tests for the display bridge framing + tickets (no gateway, no RFB server)."""

import struct
import sys
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "gpu-manager"))

import display_bridge as bridge


class FramingTests(unittest.TestCase):
    def test_key_and_pointer_events(self):
        consumed, is_input, complete, fatal = bridge.parse_client_message(bytes([4]) + b"\x00" * 7)
        self.assertEqual((consumed, is_input, complete, fatal), (8, True, True, False))
        consumed, is_input, complete, fatal = bridge.parse_client_message(bytes([5]) + b"\x00" * 5)
        self.assertEqual((consumed, is_input, complete, fatal), (6, True, True, False))

    def test_partial_frames_wait(self):
        self.assertEqual(bridge.parse_client_message(b"\x04\x00"), (0, True, False, False))
        self.assertEqual(bridge.parse_client_message(b""), (0, False, False, False))
        self.assertEqual(bridge.parse_client_message(bytes([2, 0])), (0, False, False, False))

    def test_handshake_messages_not_input(self):
        set_enc = struct.pack("!BBH", 2, 0, 1) + struct.pack("!i", 0)
        consumed, is_input, complete, fatal = bridge.parse_client_message(set_enc)
        self.assertEqual((consumed, is_input, complete, fatal), (8, False, True, False))
        fur = struct.pack("!BBHHHH", 3, 0, 0, 0, 100, 100)
        consumed, is_input, complete, fatal = bridge.parse_client_message(fur)
        self.assertEqual((consumed, is_input, complete, fatal), (10, False, True, False))

    def test_cut_text(self):
        payload = struct.pack("!i", 5) + b"hello"
        consumed, is_input, complete, fatal = bridge.parse_client_message(bytes([6]) + b"\x00\x00\x00" + payload)
        self.assertEqual((consumed, is_input, complete, fatal), (13, True, True, False))

    def test_unknown_and_absurd_are_fatal(self):
        self.assertEqual(bridge.parse_client_message(bytes([200])), (0, True, True, True))
        self.assertEqual(bridge.parse_client_message(bytes([6, 0, 0, 0]) + struct.pack("!i", -1)), (0, True, True, True))
        self.assertEqual(
            bridge.parse_client_message(bytes([2, 0]) + struct.pack("!H", 5000)),
            (0, False, True, True),
        )

    def test_coalesced_stream_parses_sequentially(self):
        stream = bytes([4]) + b"\x01" * 7 + bytes([5]) + b"\x02" * 5
        first = bridge.parse_client_message(stream)
        self.assertEqual(first, (8, True, True, False))
        second = bridge.parse_client_message(stream[8:])
        self.assertEqual(second, (6, True, True, False))


class TicketTests(unittest.TestCase):
    def test_mint_consume_single_use(self):
        ticket, viewer, ttl = bridge.mint_ticket()
        self.assertTrue(ticket)
        self.assertTrue(viewer)
        self.assertEqual(ttl, 30.0)
        self.assertEqual(bridge.consume_ticket(ticket), viewer)
        self.assertIsNone(bridge.consume_ticket(ticket))

    def test_unknown_ticket_rejected(self):
        self.assertIsNone(bridge.consume_ticket("nope"))

    def test_viewer_ids_unique(self):
        _, first, _ = bridge.mint_ticket()
        _, second, _ = bridge.mint_ticket()
        self.assertNotEqual(first, second)

    def test_expired_ticket_rejected(self):
        ticket, _, _ = bridge.mint_ticket()
        with bridge._tickets_lock:
            viewer, _ = bridge._tickets[ticket]
            bridge._tickets[ticket] = (viewer, time.time() - 1.0)
        self.assertIsNone(bridge.consume_ticket(ticket))


if __name__ == "__main__":
    unittest.main()
