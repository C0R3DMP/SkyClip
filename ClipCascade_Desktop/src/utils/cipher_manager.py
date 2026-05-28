import base64
import json
import hashlib
import logging

from Crypto.Cipher import AES
from core.constants import *
from core.config import Config


class CipherManager:
    def __init__(self, config: Config):
        self.config = config

        # hash
        self.hash_name = "sha256"
        self.dklen = 32  # 256 bits for AES-256

        # encryption
        self.mode = AES.MODE_GCM
        self.session_key = None  # Session key for in-transit (PFS)

    def needs_rehash(self) -> bool:
        """Check if stored hash is old PBKDF2 (needs upgrade to Argon2id)"""
        stored_algo = self.config.data.get("algorithm", "pbkdf2")
        return stored_algo == "pbkdf2" or stored_algo is None

    def set_session_key(self, session_key: bytes) -> None:
        """Set the session key for in-transit encryption (PFS)."""
        if not isinstance(session_key, bytes) or len(session_key) != 32:
            raise ValueError("Session key must be 32 bytes")
        self.session_key = session_key
        logging.info("Session key set for ECDH PFS")

    def clear_session_key(self) -> None:
        """Clear the session key on logout."""
        self.session_key = None
        logging.info("Session key cleared")

    def _argon2_raw(self, password: str, salt: bytes) -> bytes:
        """Derive encryption key using Argon2id (raw bytes output)"""
        try:
            from argon2.low_level import hash_secret_raw, Type
            return hash_secret_raw(
                password.encode(),
                salt,
                time_cost=3,
                memory_cost=65540,
                parallelism=4,
                hash_len=32,
                type=Type.ID
            )
        except ImportError:
            # SECURITY: argon2-cffi missing - visible warning required
            warning_msg = (
                "SECURITY WARNING: argon2-cffi library not installed. "
                "Falling back to weaker PBKDF2 hashing. "
                "Please install: pip install argon2-cffi==23.1.0"
            )
            logging.warning(warning_msg)
            # Show user-visible notification
            try:
                from utils.notification_manager import NotificationManager
                notif_mgr = NotificationManager(self.config)
                notif_mgr.notify(
                    title="⚠️ Security Warning: Weak Password Hashing",
                    message="argon2-cffi not installed. Using weak PBKDF2 fallback. Install argon2-cffi for security."
                )
            except Exception as e:
                logging.error(f"Failed to show security notification: {e}")
            return self._pbkdf2_hash(password)

    def _pbkdf2_hash(self, password: str) -> bytes:
        """Legacy PBKDF2-HMAC-SHA256 (exact original salt formula for backward compatibility)"""
        return hashlib.pbkdf2_hmac(
            hash_name=self.hash_name,
            password=password.encode(),
            salt=(
                self.config.data["username"] + password + self.config.data["salt"]
            ).encode("utf-8"),
            iterations=self.config.data.get("hash_rounds", 664937),
            dklen=self.dklen,
        )

    def hash_password(self, password: str) -> bytes:
        """
        Derive encryption key from password.
        Uses Argon2id for new installations, auto-upgrades from PBKDF2.
        Returns the 32-byte AES encryption key (bytes).
        """
        # Use Argon2id for new hashes
        if not self.needs_rehash():
            # Already Argon2id - use modern hashing
            full_salt = (
                self.config.data["username"] + self.config.data["salt"]
            ).encode("utf-8")
            return self._argon2_raw(password, full_salt)
        else:
            # Old PBKDF2 config - use exact original salt formula for compatibility
            return self._pbkdf2_hash(password)

    def encrypt(self, plaintext: str) -> dict:
        key = self.config.data["hashed_password"]
        plaintext_bytes = plaintext.encode("utf-8")
        cipher = AES.new(key, self.mode)
        ciphertext, tag = cipher.encrypt_and_digest(plaintext_bytes)
        return {"nonce": cipher.nonce, "ciphertext": ciphertext, "tag": tag}

    def decrypt(self, nonce: bytes, ciphertext: bytes, tag: bytes) -> str:
        key = self.config.data["hashed_password"]
        cipher = AES.new(key, self.mode, nonce=nonce)
        return cipher.decrypt_and_verify(ciphertext, tag).decode()

    def encrypt_transit(self, plaintext: str) -> dict:
        """Encrypt in-transit using ephemeral session_key (ECDH-derived).

        Raises RuntimeError if session_key not set (no fallback to master_key).
        """
        if self.session_key is None:
            raise RuntimeError("Session key not set; cannot encrypt in-transit")

        plaintext_bytes = plaintext.encode("utf-8")
        cipher = AES.new(self.session_key, self.mode)
        ciphertext, tag = cipher.encrypt_and_digest(plaintext_bytes)
        return {"nonce": cipher.nonce, "ciphertext": ciphertext, "tag": tag}

    def decrypt_transit(self, nonce: bytes, ciphertext: bytes, tag: bytes) -> str:
        """Decrypt in-transit using ephemeral session_key (ECDH-derived).

        Raises RuntimeError if session_key not set (no fallback to master_key).
        """
        if self.session_key is None:
            raise RuntimeError("Session key not set; cannot decrypt in-transit")

        cipher = AES.new(self.session_key, self.mode, nonce=nonce)
        return cipher.decrypt_and_verify(ciphertext, tag).decode()

    @staticmethod
    def encode_to_json_string(**kwargs: bytes) -> str:
        """
        Convert bytes values to Base64 and create a JSON string.

        Args:
            **kwargs: Key-value pairs where values must be of type `bytes`.

        Returns:
            str: A JSON string with all `bytes` values Base64-encoded.

        Raises:
            ValueError: If a value is not of type `bytes`.
        """
        json_data = {}
        for key, value in kwargs.items():
            if isinstance(value, bytes):
                json_data[key] = base64.b64encode(value).decode("utf-8")
            else:
                raise ValueError(
                    f"Unsupported value type for key '{key}': {type(value)}. "
                    f"This method only supports 'bytes'."
                )
        return json.dumps(json_data)

    @staticmethod
    def decode_from_json_string(json_string: str) -> dict:
        """
        Decode a JSON string where all values are Base64-encoded back to their original bytes.

        Args:
            json_string (str): A JSON string with Base64-encoded values.

        Returns:
            dict: A dictionary with the original keys and `bytes` values decoded from Base64.

        Raises:
            ValueError: If the JSON string is not valid or if decoding fails.
        """
        # Parse the JSON string into a dictionary
        json_data = json.loads(json_string)
        decoded_data = {}

        # Decode each Base64-encoded value back to bytes
        for key, value in json_data.items():
            if isinstance(value, str):
                decoded_data[key] = base64.b64decode(value)
            else:
                raise ValueError(
                    f"Unsupported value type for key '{key}': {type(value)}. "
                    + f"Expected 'str' for Base64 decoding."
                )
        return decoded_data

    @staticmethod
    def string_to_sha3_512_lowercase_hex(input_string: str) -> str:
        """
        Convert a string to its lowercase hexadecimal SHA3-512 hash.

        Args:
            input_string (str): The input string to hash.

        Returns:
            str: The lowercase hexadecimal representation of the SHA3-512 hash.
        """
        return hashlib.sha3_512(input_string.encode("utf-8")).hexdigest()
