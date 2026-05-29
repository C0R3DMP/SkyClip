"""
Secure password storage via system keyrings.
Uses the 'keyring' library (cross-platform) with platform-specific fallbacks.
"""

import logging
import sys

from core.constants import PLATFORM, WINDOWS, MACOS, LINUX


class KeyringManager:
    """
    Unified password storage interface.
    Uses 'keyring' library (cross-platform). On Windows, keyring uses Credential Manager.
    Falls back to platform-specific for Linux/macOS only if keyring unavailable.
    """

    SERVICE_NAME = "SkyClip"

    def __init__(self, config):
        self.config = config
        self.keyring_available = False
        self._init_keyring()

    def _init_keyring(self):
        """Initialize keyring library, detect if available"""
        try:
            import keyring
            self.keyring = keyring
            self.keyring_available = True
            logging.info(f"Keyring library initialized ({self.keyring.get_keyring().__class__.__name__})")
        except ImportError:
            logging.warning("keyring library not installed, will attempt platform-specific fallback")
            self.keyring = None
            self._init_platform_fallback()

    def _init_platform_fallback(self):
        """Initialize platform-specific fallback implementations (Linux/macOS only)"""
        try:
            if PLATFORM == WINDOWS:
                # Windows: keyring library handles Credential Manager, no fallback needed
                logging.error("Keyring library required on Windows. Install: pip install keyring")
                self.keyring_available = False
            elif PLATFORM == MACOS:
                self._init_macos_fallback()
            elif PLATFORM.startswith(LINUX):
                self._init_linux_fallback()
        except Exception as e:
            logging.warning(f"Platform-specific keyring fallback unavailable: {e}")
            self.keyring_available = False

    def _init_macos_fallback(self):
        """macOS Keychain via 'security' CLI"""
        try:
            import subprocess
            # Test if security command available
            subprocess.run(["security", "item"], capture_output=True, timeout=2)
            self.platform_impl = self._macos_keychain
            self.keyring_available = True
            logging.info("macOS Keychain fallback initialized")
        except Exception as e:
            logging.error(f"macOS Keychain initialization failed: {e}")

    def _init_linux_fallback(self):
        """Linux libsecret via dbus (secretstorage library)"""
        try:
            import secretstorage
            self.platform_impl = self._linux_libsecret
            self.keyring_available = True
            logging.info("Linux libsecret fallback initialized")
        except ImportError:
            logging.error("secretstorage library not installed for Linux keyring fallback")
            self.keyring_available = False

    def store_password(self, username: str, password: str) -> bool:
        """
        Store password in secure keyring.
        Returns: True if stored successfully, False if fallback to plaintext config warning.
        """
        if not username or not password:
            logging.error("Cannot store empty username or password")
            return False

        # If keyring disabled in config, skip storage
        if not self.config.data.get("keyring_enabled", True):
            logging.info("Keyring disabled in config, password will not be stored securely")
            return False

        try:
            if self.keyring:
                # Use standard keyring library (works on all platforms)
                self.keyring.set_password(self.SERVICE_NAME, username, password)
                logging.info(f"Password stored in keyring for user {username}")
                return True
            elif self.keyring_available and hasattr(self, 'platform_impl'):
                # Use platform-specific fallback (Linux/macOS only)
                self.platform_impl(action="store", username=username, password=password)
                logging.info(f"Password stored in platform keyring for user {username}")
                return True
            else:
                # Keyring unavailable - warn and allow plaintext fallback
                self._warn_keyring_unavailable("store")
                return False

        except Exception as e:
            logging.error(f"Failed to store password in keyring: {e}")
            self._warn_keyring_unavailable("store")
            return False

    def retrieve_password(self, username: str) -> str:
        """
        Retrieve password from secure keyring.
        Returns: Password string if found, None if not found or keyring unavailable.
        """
        if not username:
            logging.error("Cannot retrieve password with empty username")
            return None

        if not self.config.data.get("keyring_enabled", True):
            logging.warning("Keyring disabled, cannot retrieve password securely")
            return None

        try:
            if self.keyring:
                password = self.keyring.get_password(self.SERVICE_NAME, username)
                if password:
                    logging.info(f"Password retrieved from keyring for user {username}")
                    return password
                else:
                    logging.warning(f"No password found in keyring for user {username}")
                    return None
            elif self.keyring_available and hasattr(self, 'platform_impl'):
                password = self.platform_impl(action="retrieve", username=username)
                if password:
                    logging.info(f"Password retrieved from platform keyring for user {username}")
                    return password
                else:
                    logging.warning(f"No password found in platform keyring for user {username}")
                    return None
            else:
                self._warn_keyring_unavailable("retrieve")
                return None

        except Exception as e:
            logging.error(f"Failed to retrieve password from keyring: {e}")
            return None

    def delete_password(self, username: str) -> bool:
        """
        Delete password from secure keyring.
        Returns: True if deleted successfully, False if deletion failed or keyring unavailable.
        """
        if not username:
            logging.error("Cannot delete password with empty username")
            return False

        if not self.config.data.get("keyring_enabled", True):
            logging.info("Keyring disabled, skipping password deletion")
            return True

        try:
            if self.keyring:
                self.keyring.delete_password(self.SERVICE_NAME, username)
                logging.info(f"Password deleted from keyring for user {username}")
                return True
            elif self.keyring_available and hasattr(self, 'platform_impl'):
                self.platform_impl(action="delete", username=username)
                logging.info(f"Password deleted from platform keyring for user {username}")
                return True
            else:
                logging.warning("Keyring unavailable, password not deleted from keyring")
                return False

        except Exception as e:
            logging.error(f"Failed to delete password from keyring: {e}")
            return False

    def _warn_keyring_unavailable(self, action: str):
        """Show visible warning when keyring unavailable"""
        try:
            from gui.message_box import MessageBox
            MessageBox().showwarning(
                "Security Warning",
                f"Keyring storage unavailable.\n\n"
                f"Action: {action.capitalize()} password\n"
                f"Fallback: Plaintext storage in config file\n\n"
                f"Recommendation: Install 'keyring' package:\n"
                f"pip install keyring"
            )
        except Exception as e:
            logging.warning(f"Could not show keyring warning dialog: {e}")
            logging.warning("Keyring storage unavailable - falling back to plaintext config storage")

    # ========== Platform-specific fallback implementations (Linux/macOS only) ==========

    def _macos_keychain(self, action: str, username: str = None, password: str = None) -> str:
        """macOS Keychain via 'security' CLI"""
        import subprocess

        account = f"{self.SERVICE_NAME}_{username}"

        if action == "store":
            try:
                # Add to Keychain (interactive, may prompt user)
                process = subprocess.run(
                    ["security", "add-generic-password", "-s", self.SERVICE_NAME, "-a", account, "-w", password, "-U"],
                    capture_output=True,
                    timeout=10
                )
                if process.returncode == 0:
                    logging.info(f"Password stored in macOS Keychain for {username}")
                    return True
                else:
                    raise Exception(f"security command failed: {process.stderr.decode()}")
            except Exception as e:
                logging.error(f"macOS Keychain store failed: {e}")
                raise

        elif action == "retrieve":
            try:
                process = subprocess.run(
                    ["security", "find-generic-password", "-s", self.SERVICE_NAME, "-a", account, "-w"],
                    capture_output=True,
                    timeout=10
                )
                if process.returncode == 0:
                    return process.stdout.decode().strip()
                else:
                    return None
            except Exception as e:
                logging.error(f"macOS Keychain retrieve failed: {e}")
                raise

        elif action == "delete":
            try:
                process = subprocess.run(
                    ["security", "delete-generic-password", "-s", self.SERVICE_NAME, "-a", account],
                    capture_output=True,
                    timeout=10
                )
                if process.returncode == 0:
                    logging.info(f"Password deleted from macOS Keychain for {username}")
                    return True
                else:
                    return False
            except Exception as e:
                logging.error(f"macOS Keychain delete failed: {e}")
                raise

    def _linux_libsecret(self, action: str, username: str = None, password: str = None) -> str:
        """Linux libsecret via secretstorage (dbus)"""
        try:
            import secretstorage

            collection = secretstorage.get_default_collection()
            item_label = f"{self.SERVICE_NAME}_{username}"

            if action == "store":
                # Delete old entry if exists, then store new
                try:
                    for item in collection.get_all_items():
                        if item.get_label() == item_label:
                            item.delete()
                            logging.info(f"Deleted old libsecret entry for {username}")
                except Exception as e:
                    logging.warning(f"Could not delete old libsecret entry: {e}")

                collection.create_item(item_label, {"username": username}, password)
                logging.info(f"Password stored in libsecret for {username}")
                return True

            elif action == "retrieve":
                for item in collection.get_all_items():
                    if item.get_label() == item_label:
                        return item.get_secret().decode()
                return None

            elif action == "delete":
                for item in collection.get_all_items():
                    if item.get_label() == item_label:
                        item.delete()
                        logging.info(f"Password deleted from libsecret for {username}")
                        return True
                return False

        except Exception as e:
            logging.error(f"Linux libsecret operation failed: {e}")
            raise
