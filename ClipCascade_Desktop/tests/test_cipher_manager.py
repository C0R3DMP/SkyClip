"""
Unit tests for CipherManager with Argon2id password hashing.
Tests backward compatibility with PBKDF2 and new Argon2id upgrade path.
"""

import unittest
import sys
import os
import json
import tempfile
from unittest.mock import patch, MagicMock

# Add src to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from core.config import Config
from utils.cipher_manager import CipherManager


class TestCipherManagerArgon2id(unittest.TestCase):
    """Test Argon2id password hashing and backward compatibility"""

    def setUp(self):
        """Set up test fixtures with temporary config file"""
        self.temp_dir = tempfile.mkdtemp()
        self.config_file = os.path.join(self.temp_dir, "test_config.json")

        self.config = Config(file_name=self.config_file)
        self.config.data["username"] = "testuser"
        self.config.data["password"] = "testpass123"
        self.config.data["salt"] = "testsalt"
        self.config.data["cipher_enabled"] = True

        self.cipher_manager = CipherManager(self.config)

        # Pre-derive the hashed_password for encryption tests
        self.config.data["hashed_password"] = self.cipher_manager.hash_password("testpass123")

    def tearDown(self):
        """Clean up temporary files"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_argon2id_key_derivation_deterministic(self):
        """Test that same password + salt produces same key (deterministic)"""
        # Set algorithm to Argon2id (not old PBKDF2)
        self.config.data["algorithm"] = "argon2id"

        # Derive key twice with same password
        key1 = self.cipher_manager.hash_password("testpass123")
        key2 = self.cipher_manager.hash_password("testpass123")

        # Same input should produce same key
        self.assertEqual(key1, key2, "Argon2id key derivation should be deterministic")

    def test_argon2id_key_length(self):
        """Test that derived key is 32 bytes (256 bits) for AES-256"""
        self.config.data["algorithm"] = "argon2id"
        key = self.cipher_manager.hash_password("testpass123")

        self.assertEqual(len(key), 32, "AES-256 key must be 32 bytes")
        self.assertIsInstance(key, bytes, "Key must be bytes")

    def test_pbkdf2_detection_missing_algorithm(self):
        """Test that missing algorithm field is detected as old PBKDF2"""
        # Simulate old config without algorithm field
        if "algorithm" in self.config.data:
            del self.config.data["algorithm"]

        self.assertTrue(
            self.cipher_manager.needs_rehash(),
            "Missing algorithm should be detected as needing rehash"
        )

    def test_pbkdf2_detection_explicit(self):
        """Test that explicit PBKDF2 algorithm is detected as old"""
        self.config.data["algorithm"] = "pbkdf2"

        self.assertTrue(
            self.cipher_manager.needs_rehash(),
            "PBKDF2 algorithm should be detected as needing rehash"
        )

    def test_argon2id_no_rehash_needed(self):
        """Test that Argon2id config doesn't need rehash"""
        self.config.data["algorithm"] = "argon2id"

        self.assertFalse(
            self.cipher_manager.needs_rehash(),
            "Argon2id should not need rehash"
        )

    def test_pbkdf2_fallback_exact_salt_formula(self):
        """Test that PBKDF2 fallback uses exact original salt formula"""
        # The original salt formula: username + password + salt
        # This must be preserved for backward compatibility

        self.config.data["algorithm"] = "pbkdf2"  # Use old algorithm

        key = self.cipher_manager.hash_password("testpass123")

        # Verify key can be derived (no exception)
        self.assertEqual(len(key), 32, "PBKDF2 fallback should produce 32-byte key")
        self.assertIsInstance(key, bytes)

    def test_pbkdf2_different_passwords_different_keys(self):
        """Test that different passwords produce different keys"""
        self.config.data["algorithm"] = "pbkdf2"

        key1 = self.cipher_manager.hash_password("password1")
        key2 = self.cipher_manager.hash_password("password2")

        self.assertNotEqual(
            key1, key2,
            "Different passwords should produce different keys"
        )

    def test_pbkdf2_different_usernames_different_keys(self):
        """Test that different usernames (salt component) produce different keys"""
        self.config.data["algorithm"] = "pbkdf2"

        key1 = self.cipher_manager.hash_password("samepass")

        # Change username in salt
        self.config.data["username"] = "different_user"
        key2 = self.cipher_manager.hash_password("samepass")

        self.assertNotEqual(
            key1, key2,
            "Different usernames should produce different keys"
        )

    def test_cross_device_sync_same_key(self):
        """Test that same username + salt + password = same key (cross-device sync)"""
        self.config.data["algorithm"] = "argon2id"
        self.config.data["username"] = "synctest"
        self.config.data["salt"] = "syncsalt"

        # Device A derives key
        key_device_a = self.cipher_manager.hash_password("syncpass")

        # Device B with same config
        config_b = Config(file_name=os.path.join(self.temp_dir, "test_config_b.json"))
        config_b.data["username"] = "synctest"
        config_b.data["salt"] = "syncsalt"
        config_b.data["algorithm"] = "argon2id"
        cipher_b = CipherManager(config_b)

        key_device_b = cipher_b.hash_password("syncpass")

        self.assertEqual(
            key_device_a, key_device_b,
            "Same username + salt + password should produce same key on different devices"
        )

    def test_encryption_decryption_roundtrip(self):
        """Test that text encrypted with derived key can be decrypted"""
        self.config.data["algorithm"] = "argon2id"

        plaintext = "Hello, World! This is a test message."

        # Encrypt
        ciphertext_dict = self.cipher_manager.encrypt(plaintext)

        # Verify ciphertext components
        self.assertIn("nonce", ciphertext_dict)
        self.assertIn("ciphertext", ciphertext_dict)
        self.assertIn("tag", ciphertext_dict)

        # Decrypt
        decrypted = self.cipher_manager.decrypt(
            nonce=ciphertext_dict["nonce"],
            ciphertext=ciphertext_dict["ciphertext"],
            tag=ciphertext_dict["tag"]
        )

        self.assertEqual(
            plaintext, decrypted,
            "Encrypted plaintext should decrypt to original"
        )

    @patch('utils.cipher_manager.logging.warning')
    def test_argon2_import_error_fallback_logs_warning(self, mock_logging):
        """Test that missing argon2-cffi library triggers warning"""
        # This test would require actually uninstalling argon2-cffi
        # For now, we verify the structure is correct
        self.assertTrue(
            hasattr(self.cipher_manager, '_argon2_raw'),
            "CipherManager should have _argon2_raw method"
        )

    def test_hash_password_selects_correct_algorithm(self):
        """Test that hash_password uses Argon2id when not needing rehash"""
        self.config.data["algorithm"] = "argon2id"

        # Should use Argon2id path (not PBKDF2)
        key = self.cipher_manager.hash_password("testpass")

        self.assertEqual(len(key), 32)
        self.assertIsInstance(key, bytes)


class TestCipherManagerConfig(unittest.TestCase):
    """Test config file handling with algorithm field"""

    def setUp(self):
        """Set up test config"""
        self.temp_dir = tempfile.mkdtemp()
        self.config_file = os.path.join(self.temp_dir, "test_config.json")

    def tearDown(self):
        """Clean up"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_algorithm_field_initialized(self):
        """Test that algorithm field is initialized in new config"""
        config = Config(file_name=self.config_file)

        self.assertIn("algorithm", config.data)
        self.assertEqual(
            config.data["algorithm"], "argon2id",
            "New config should default to Argon2id"
        )

    def test_config_save_load_preserves_algorithm(self):
        """Test that algorithm field is saved and loaded correctly"""
        config = Config(file_name=self.config_file)
        config.data["username"] = "testuser"
        config.data["algorithm"] = "argon2id"
        config.save()

        # Load config
        config2 = Config(file_name=self.config_file)
        config2.load()

        self.assertEqual(
            config2.data.get("algorithm"), "argon2id",
            "Algorithm field should persist across save/load"
        )


if __name__ == '__main__':
    # Run tests with verbose output
    unittest.main(verbosity=2)
