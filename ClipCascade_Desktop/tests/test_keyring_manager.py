"""Unit tests for KeyringManager with platform mocking"""

import unittest
import sys
import os
import json
import tempfile
from unittest.mock import patch, MagicMock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from core.config import Config
from utils.keyring_manager import KeyringManager


class TestKeyringManager(unittest.TestCase):
    """Test KeyringManager password storage and retrieval"""

    def setUp(self):
        """Set up test fixtures"""
        self.temp_dir = tempfile.mkdtemp()
        self.config_file = os.path.join(self.temp_dir, "test_config.json")

        self.config = Config(file_name=self.config_file)
        self.config.data["keyring_enabled"] = True

        # Mock keyring library availability
        self.keyring_manager = KeyringManager(self.config)

    def tearDown(self):
        """Clean up"""
        import shutil
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    @patch('utils.keyring_manager.logging')
    def test_store_password_success(self, mock_logging):
        """Test storing password in keyring"""
        # Mock the keyring library
        with patch.object(self.keyring_manager, 'keyring') as mock_keyring:
            mock_keyring.set_password = MagicMock()
            self.keyring_manager.keyring = mock_keyring
            self.keyring_manager.keyring_available = True

            success = self.keyring_manager.store_password("testuser", "testpass123")

            self.assertTrue(success)
            mock_keyring.set_password.assert_called_once_with(
                "SkyClip", "testuser", "testpass123"
            )

    @patch('utils.keyring_manager.logging')
    def test_retrieve_password_success(self, mock_logging):
        """Test retrieving password from keyring"""
        with patch.object(self.keyring_manager, 'keyring') as mock_keyring:
            mock_keyring.get_password = MagicMock(return_value="testpass123")
            self.keyring_manager.keyring = mock_keyring
            self.keyring_manager.keyring_available = True

            password = self.keyring_manager.retrieve_password("testuser")

            self.assertEqual(password, "testpass123")
            mock_keyring.get_password.assert_called_once_with("SkyClip", "testuser")

    @patch('utils.keyring_manager.logging')
    def test_delete_password_success(self, mock_logging):
        """Test deleting password from keyring"""
        with patch.object(self.keyring_manager, 'keyring') as mock_keyring:
            mock_keyring.delete_password = MagicMock()
            self.keyring_manager.keyring = mock_keyring
            self.keyring_manager.keyring_available = True

            success = self.keyring_manager.delete_password("testuser")

            self.assertTrue(success)
            mock_keyring.delete_password.assert_called_once_with("SkyClip", "testuser")

    def test_store_password_empty_username(self):
        """Test that empty username is rejected"""
        success = self.keyring_manager.store_password("", "password")
        self.assertFalse(success)

    def test_store_password_empty_password(self):
        """Test that empty password is rejected"""
        success = self.keyring_manager.store_password("user", "")
        self.assertFalse(success)

    def test_retrieve_password_empty_username(self):
        """Test that empty username is rejected"""
        password = self.keyring_manager.retrieve_password("")
        self.assertIsNone(password)

    def test_delete_password_empty_username(self):
        """Test that empty username is rejected"""
        success = self.keyring_manager.delete_password("")
        self.assertFalse(success)

    @patch('utils.keyring_manager.logging')
    def test_keyring_disabled_in_config(self, mock_logging):
        """Test that operations skip when keyring_enabled=False"""
        self.config.data["keyring_enabled"] = False

        success_store = self.keyring_manager.store_password("user", "pass")
        password_retrieve = self.keyring_manager.retrieve_password("user")
        success_delete = self.keyring_manager.delete_password("user")

        self.assertFalse(success_store)
        self.assertIsNone(password_retrieve)
        self.assertTrue(success_delete)  # Still returns True (no-op)

    @patch('utils.keyring_manager.logging')
    def test_keyring_unavailable_fallback(self, mock_logging):
        """Test behavior when keyring is unavailable"""
        # Simulate keyring unavailable
        self.keyring_manager.keyring = None
        self.keyring_manager.keyring_available = False

        success = self.keyring_manager.store_password("user", "pass")

        # Should warn and return False
        self.assertFalse(success)

    @patch('utils.keyring_manager.logging')
    def test_retrieve_password_not_found(self, mock_logging):
        """Test retrieving non-existent password"""
        with patch.object(self.keyring_manager, 'keyring') as mock_keyring:
            mock_keyring.get_password = MagicMock(return_value=None)
            self.keyring_manager.keyring = mock_keyring
            self.keyring_manager.keyring_available = True

            password = self.keyring_manager.retrieve_password("nonexistent_user")

            self.assertIsNone(password)

    @patch('utils.keyring_manager.logging')
    def test_delete_password_multiple_calls(self, mock_logging):
        """Test that password is deleted cleanly on second call"""
        with patch.object(self.keyring_manager, 'keyring') as mock_keyring:
            mock_keyring.delete_password = MagicMock()
            self.keyring_manager.keyring = mock_keyring
            self.keyring_manager.keyring_available = True

            # First delete
            success1 = self.keyring_manager.delete_password("user")
            # Second delete (already deleted)
            success2 = self.keyring_manager.delete_password("user")

            self.assertTrue(success1)
            self.assertTrue(success2)
            # Both calls should succeed (idempotent)

    @patch('utils.keyring_manager.logging')
    def test_store_then_retrieve_roundtrip(self, mock_logging):
        """Test store → retrieve roundtrip"""
        with patch.object(self.keyring_manager, 'keyring') as mock_keyring:
            stored_password = None

            def mock_set_password(service, user, pwd):
                nonlocal stored_password
                stored_password = pwd

            def mock_get_password(service, user):
                return stored_password

            mock_keyring.set_password = MagicMock(side_effect=mock_set_password)
            mock_keyring.get_password = MagicMock(side_effect=mock_get_password)
            self.keyring_manager.keyring = mock_keyring
            self.keyring_manager.keyring_available = True

            # Store
            self.keyring_manager.store_password("user", "mypass")

            # Retrieve
            retrieved = self.keyring_manager.retrieve_password("user")

            self.assertEqual(retrieved, "mypass")


if __name__ == '__main__':
    unittest.main(verbosity=2)
