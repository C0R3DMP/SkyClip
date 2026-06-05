"""
Unit tests for safe_download_filename — the guard against path-traversal /
arbitrary-file-write via untrusted remote filenames in clipboard "files"
payloads.
"""

import os
import sys
import unittest

# Add src to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from utils.path_safety import safe_download_filename


class TestSafeDownloadFilename(unittest.TestCase):

    def test_plain_filename_unchanged(self):
        self.assertEqual(safe_download_filename("report.pdf"), "report.pdf")

    def test_strips_unix_traversal(self):
        result = safe_download_filename("../../etc/passwd")
        self.assertEqual(result, "passwd")
        self.assertNotIn("/", result)

    def test_strips_windows_traversal(self):
        result = safe_download_filename("..\\..\\Windows\\System32\\evil.dll")
        self.assertEqual(result, "evil.dll")
        self.assertNotIn("\\", result)

    def test_absolute_path_reduced_to_basename(self):
        self.assertEqual(safe_download_filename("/home/user/.bashrc"), ".bashrc")

    def test_dot_and_dotdot_fall_back(self):
        self.assertEqual(safe_download_filename(".."), "downloaded_file")
        self.assertEqual(safe_download_filename("."), "downloaded_file")

    def test_empty_and_none_fall_back(self):
        self.assertEqual(safe_download_filename(""), "downloaded_file")
        self.assertEqual(safe_download_filename(None), "downloaded_file")

    def test_control_and_illegal_chars_removed(self):
        result = safe_download_filename("a\x00b:c*d?.txt")
        self.assertNotIn("\x00", result)
        self.assertNotIn(":", result)
        self.assertNotIn("*", result)

    def test_result_never_contains_separators(self):
        for evil in [
            "../../../../tmp/x",
            "....//....//x",
            "/abs/path/x",
            "dir/sub/x",
            "a\\b\\c",
        ]:
            result = safe_download_filename(evil)
            self.assertNotIn("/", result)
            self.assertNotIn("\\", result)
            # And joining stays within the target directory.
            joined = os.path.normpath(os.path.join("/downloads", result))
            self.assertTrue(joined.startswith(os.path.normpath("/downloads") + os.sep))


if __name__ == "__main__":
    unittest.main()
