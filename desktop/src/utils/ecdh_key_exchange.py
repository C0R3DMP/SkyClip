import logging
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.backends import default_backend


class ECDHKeyExchange:
    """
    Elliptic Curve Diffie-Hellman (ECDH) key exchange for Perfect Forward Secrecy.
    Uses P-256 (secp256r1) curve for compatibility.
    """

    CURVE = ec.SECP256R1()  # P-256 / secp256r1
    BACKEND = default_backend()
    KEY_SIZE = 32  # 256 bits for AES-256
    INFO = b"clipboard-session"  # KDF info parameter

    @staticmethod
    def generate_keypair() -> tuple:
        """
        Generate ephemeral ECDH keypair (P-256).

        Returns:
            (private_key_bytes, public_key_bytes): Both in PEM format (bytes)

        Raises:
            RuntimeError: If key generation fails
        """
        try:
            private_key = ec.generate_private_key(
                ECDHKeyExchange.CURVE, ECDHKeyExchange.BACKEND
            )

            # Serialize private key (PEM format)
            private_pem = private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption()
            )

            # Serialize public key (PEM format)
            public_key = private_key.public_key()
            public_pem = public_key.public_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PublicFormat.SubjectPublicKeyInfo
            )

            return private_pem, public_pem
        except Exception as e:
            raise RuntimeError(f"Failed to generate ECDH keypair: {e}")

    @staticmethod
    def compute_shared_secret(
        private_key_pem: bytes, public_key_pem: bytes
    ) -> bytes:
        """
        Perform ECDH key agreement to compute shared secret.

        Args:
            private_key_pem: Our private key (PEM format, bytes)
            public_key_pem: Peer's public key (PEM format, bytes)

        Returns:
            shared_secret: 32-byte shared secret

        Raises:
            RuntimeError: If key agreement fails
        """
        try:
            # Load private key from PEM
            private_key = serialization.load_pem_private_key(
                private_key_pem, password=None, backend=ECDHKeyExchange.BACKEND
            )

            # Load public key from PEM
            public_key = serialization.load_pem_public_key(
                public_key_pem, backend=ECDHKeyExchange.BACKEND
            )

            # Compute shared secret
            shared_secret = private_key.exchange(
                ec.ECDH(), public_key
            )

            return shared_secret
        except Exception as e:
            raise RuntimeError(f"Failed to compute ECDH shared secret: {e}")

    @staticmethod
    def derive_session_key(shared_secret: bytes) -> bytes:
        """
        Derive session key from ECDH shared secret using HKDF.

        Args:
            shared_secret: 32-byte shared secret from ECDH key agreement

        Returns:
            session_key: 32-byte AES-256 encryption key

        Raises:
            RuntimeError: If key derivation fails
        """
        try:
            if not shared_secret or len(shared_secret) != 32:
                raise ValueError("Shared secret must be 32 bytes")
            hkdf = HKDF(
                algorithm=hashes.SHA256(),
                length=ECDHKeyExchange.KEY_SIZE,
                salt=None,
                info=ECDHKeyExchange.INFO,
                backend=ECDHKeyExchange.BACKEND
            )
            session_key = hkdf.derive(shared_secret)
            return session_key
        except Exception as e:
            raise RuntimeError(f"Failed to derive session key: {e}")

    @staticmethod
    def perform_key_exchange(
        private_key_pem: bytes, peer_public_key_pem: bytes
    ) -> bytes:
        """
        Perform full ECDH key exchange: compute shared secret and derive session key.

        Args:
            private_key_pem: Our private key (PEM format, bytes)
            peer_public_key_pem: Peer's public key (PEM format, bytes)

        Returns:
            session_key: 32-byte AES-256 encryption key

        Raises:
            RuntimeError: If any step of the exchange fails
        """
        try:
            shared_secret = ECDHKeyExchange.compute_shared_secret(
                private_key_pem, peer_public_key_pem
            )
            session_key = ECDHKeyExchange.derive_session_key(shared_secret)
            return session_key
        except Exception as e:
            raise RuntimeError(f"ECDH key exchange failed: {e}")
