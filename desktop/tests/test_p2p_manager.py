"""
Unit tests for P2PManager.fragment_string — the UTF-8-safe chunker used to
split large clipboard payloads into WebRTC data-channel messages.

`p2p_manager.py` transitively imports the GUI tray stack (tkinter, pystray),
which needs a real display server and isn't available in a headless test
environment. Those two modules are unrelated to fragment_string, so they are
stubbed out here (only if not already importable) purely to make the module
importable for testing — no production code is touched.
"""

import os
import sys
import unittest
from unittest.mock import MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

for _mod in ("tkinter", "tkinter.filedialog", "pystray"):
    if _mod in sys.modules:
        continue
    try:
        __import__(_mod)
    except Exception:
        # pystray in particular doesn't fail with a clean ImportError when
        # unusable headless — it imports fine but then opens an X display as
        # a side effect and raises Xlib's DisplayNameError. Anything going
        # wrong here means "can't use the real module in this environment",
        # so stub it out regardless of the exact exception type.
        sys.modules[_mod] = MagicMock()

from p2p.p2p_manager import P2PManager


class TestFragmentString(unittest.TestCase):
    """
    fragment_string() used to slice the UTF-8 byte string at raw
    `fragment_size` byte offsets and decode each slice with
    errors="ignore". Any multi-byte character (Arabic, emoji, CJK, accented
    Latin, ...) whose bytes straddled a fragment boundary had its orphaned
    bytes silently dropped by both fragments touching the cut — the receiving
    device would show clipboard text with characters missing, with no error
    anywhere. This only affected P2P mode with cipher_enabled=False (a
    ciphered payload is base64/JSON, which is pure ASCII, so a byte cut is
    always a character cut there too — but decode() never has anything
    non-ASCII to mangle).
    """

    def test_no_multibyte_char_split_across_boundary(self):
        # 'م' (2-byte UTF-8) starts exactly at the fragment_size=16 boundary.
        s = "A" * 15 + "مرحبا" + "B" * 15
        fragments = P2PManager.fragment_string(s, fragment_size=16)
        self.assertEqual("".join(fragments), s)

    def test_four_byte_emoji_at_every_boundary_offset(self):
        base = "X" * 20 + "🚀" * 5 + "Y" * 20
        for fragment_size in range(4, 30):
            with self.subTest(fragment_size=fragment_size):
                fragments = P2PManager.fragment_string(base, fragment_size)
                self.assertEqual("".join(fragments), base)

    def test_arabic_text_round_trips_at_various_sizes(self):
        arabic = "مرحبا بكم في العالم " * 50
        for fragment_size in (4, 5, 7, 10, 16, 100, len(arabic.encode("utf-8"))):
            with self.subTest(fragment_size=fragment_size):
                fragments = P2PManager.fragment_string(arabic, fragment_size)
                self.assertEqual("".join(fragments), arabic)

    def test_ascii_only_unaffected(self):
        s = "hello world, this is plain ascii text" * 5
        fragments = P2PManager.fragment_string(s, fragment_size=10)
        self.assertEqual("".join(fragments), s)

    def test_production_fragment_size_with_large_mixed_text(self):
        big = "مرحبا🚀 " * 5000
        fragments = P2PManager.fragment_string(big)  # default FRAGMENT_SIZE
        self.assertEqual("".join(fragments), big)
        self.assertGreater(len(fragments), 1, "test text should actually need multiple fragments")

    def test_tiny_fragment_size_terminates_without_hanging(self):
        """
        fragment_size smaller than a character's byte width (never happens
        with the real FRAGMENT_SIZE=15360) must still terminate rather than
        loop forever backing off to a boundary it can never reach.
        """
        base = "🚀" * 10
        for fragment_size in (1, 2, 3):
            with self.subTest(fragment_size=fragment_size):
                fragments = P2PManager.fragment_string(base, fragment_size)
                self.assertIsInstance(fragments, list)


if __name__ == "__main__":
    unittest.main()
