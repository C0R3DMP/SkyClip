"""
Unit tests for CipherManager with Argon2id password hashing.
Tests backward compatibility with PBKDF2 and new Argon2id upgrade path.
"""

import builtins
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

    def test_missing_argon2_raises_instead_of_deriving_a_different_key(self):
        """
        With algorithm="argon2id" and argon2-cffi unavailable, key derivation
        must fail loudly.

        The old behaviour silently fell back to PBKDF2, which yields a
        *different* 32-byte key. That is worse than an error: the clipboard
        gets encrypted under a key no other device can reproduce, previously
        synced data stops decrypting, and the config still advertises
        algorithm="argon2id" so nothing signals the divergence.
        """
        self.config.data["algorithm"] = "argon2id"

        real_import = builtins.__import__

        def blocked_import(name, *args, **kwargs):
            if name.startswith("argon2"):
                raise ImportError("No module named 'argon2'")
            return real_import(name, *args, **kwargs)

        with patch.object(builtins, "__import__", side_effect=blocked_import):
            with self.assertRaises(RuntimeError) as ctx:
                self.cipher_manager.hash_password("testpass")

        self.assertIn("argon2-cffi", str(ctx.exception))

    def test_argon2id_and_pbkdf2_derive_different_keys(self):
        """
        Guards the reason the fallback above must not be silent: the two KDFs
        genuinely disagree, so substituting one for the other breaks sync.
        """
        self.config.data["algorithm"] = "argon2id"
        argon_key = self.cipher_manager.hash_password("testpass")

        self.config.data["algorithm"] = "pbkdf2"
        pbkdf2_key = self.cipher_manager.hash_password("testpass")

        self.assertNotEqual(
            argon_key, pbkdf2_key,
            "Argon2id and PBKDF2 must not be treated as interchangeable"
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
        """
        A fresh config must default to PBKDF2.

        Argon2id is stronger, but the mobile client is PBKDF2-only and every
        device in a sync group has to derive the same key. Defaulting to
        Argon2id would silently break desktop<->mobile sync, so it stays an
        explicit, coordinated opt-in (see SECURITY.md).
        """
        config = Config(file_name=self.config_file)

        self.assertIn("algorithm", config.data)
        self.assertEqual(
            config.data["algorithm"], "pbkdf2",
            "New config must default to PBKDF2 for mobile key compatibility"
        )

    def test_save_persists_key_when_cipher_disabled(self):
        """
        save() must serialize a bytes hashed_password regardless of
        cipher_enabled.

        save() used to Base64-encode the key only when cipher_enabled was
        truthy, while load() decoded unconditionally. Turning encryption off
        while a key was still in memory therefore left raw bytes in the dict,
        json.dump raised TypeError, and the except branch swallowed it — so the
        DATA file silently kept the *old* settings and nothing the user changed
        was ever written.
        """
        config = Config(file_name=self.config_file)
        config.data["username"] = "testuser"
        config.data["cipher_enabled"] = False
        config.data["hashed_password"] = b"\x01" * 32
        config.save()

        self.assertTrue(
            os.path.exists(self.config_file),
            "save() must write the DATA file even with cipher_enabled=False"
        )

        with open(self.config_file) as f:
            on_disk = json.load(f)
        self.assertEqual(on_disk["username"], "testuser")
        self.assertEqual(on_disk["cipher_enabled"], False)

        config2 = Config(file_name=self.config_file)
        config2.load()
        self.assertEqual(
            config2.data["hashed_password"], b"\x01" * 32,
            "Key must survive a save/load round-trip unchanged"
        )

    def test_save_load_round_trip_is_idempotent(self):
        """Saving an already-loaded config must not double-encode the key."""
        config = Config(file_name=self.config_file)
        config.data["hashed_password"] = b"\x02" * 32
        config.save()

        config2 = Config(file_name=self.config_file)
        config2.load()
        config2.save()

        config3 = Config(file_name=self.config_file)
        config3.load()
        self.assertEqual(config3.data["hashed_password"], b"\x02" * 32)

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
