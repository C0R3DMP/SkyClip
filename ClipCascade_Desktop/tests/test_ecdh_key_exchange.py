import unittest
import sys
import os

# Add src directory to path so we can import modules
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from utils.ecdh_key_exchange import ECDHKeyExchange


class TestECDHKeyExchange(unittest.TestCase):
    """Tests for Perfect Forward Secrecy (ECDH) key exchange."""

    def test_generate_keypair(self):
        """Keypair generation produces valid PEM-formatted keys."""
        private_pem, public_pem = ECDHKeyExchange.generate_keypair()

        self.assertIsInstance(private_pem, bytes)
        self.assertIsInstance(public_pem, bytes)
        self.assertTrue(private_pem.startswith(b"-----BEGIN PRIVATE KEY-----"))
        self.assertTrue(public_pem.startswith(b"-----BEGIN PUBLIC KEY-----"))

    def test_generate_keypair_unique(self):
        """Multiple calls to generate_keypair produce different keys."""
        private1, public1 = ECDHKeyExchange.generate_keypair()
        private2, public2 = ECDHKeyExchange.generate_keypair()

        self.assertNotEqual(private1, private2)
        self.assertNotEqual(public1, public2)

    def test_compute_shared_secret(self):
        """ECDH key agreement produces valid shared secret."""
        # Generate two keypairs
        alice_private, alice_public = ECDHKeyExchange.generate_keypair()
        bob_private, bob_public = ECDHKeyExchange.generate_keypair()

        # Compute shared secrets (both should be identical)
        alice_secret = ECDHKeyExchange.compute_shared_secret(alice_private, bob_public)
        bob_secret = ECDHKeyExchange.compute_shared_secret(bob_private, alice_public)

        # Both sides derive the same shared secret
        self.assertEqual(alice_secret, bob_secret)
        self.assertEqual(len(alice_secret), 32)  # P-256 produces 32-byte secret

    def test_derive_session_key(self):
        """Session key derivation produces 32-byte key."""
        alice_private, alice_public = ECDHKeyExchange.generate_keypair()
        bob_private, bob_public = ECDHKeyExchange.generate_keypair()

        shared_secret = ECDHKeyExchange.compute_shared_secret(alice_private, bob_public)
        session_key = ECDHKeyExchange.derive_session_key(shared_secret)

        self.assertIsInstance(session_key, bytes)
        self.assertEqual(len(session_key), 32)  # AES-256 key

    def test_derive_session_key_deterministic(self):
        """Same shared secret → same session key."""
        shared_secret = b"fixed_secret_for_testing" + b"\x00" * 8  # 32 bytes

        key1 = ECDHKeyExchange.derive_session_key(shared_secret)
        key2 = ECDHKeyExchange.derive_session_key(shared_secret)

        self.assertEqual(key1, key2)

    def test_perform_key_exchange_end_to_end(self):
        """Full key exchange: both parties derive identical session keys."""
        # Alice generates keypair
        alice_private, alice_public = ECDHKeyExchange.generate_keypair()

        # Bob generates keypair
        bob_private, bob_public = ECDHKeyExchange.generate_keypair()

        # Alice performs key exchange with Bob's public key
        alice_session_key = ECDHKeyExchange.perform_key_exchange(alice_private, bob_public)

        # Bob performs key exchange with Alice's public key
        bob_session_key = ECDHKeyExchange.perform_key_exchange(bob_private, alice_public)

        # Both derive the same session key
        self.assertEqual(alice_session_key, bob_session_key)
        self.assertEqual(len(alice_session_key), 32)

    def test_invalid_private_key_raises_error(self):
        """Invalid private key format raises RuntimeError."""
        with self.assertRaises(RuntimeError):
            ECDHKeyExchange.compute_shared_secret(
                b"invalid_key_data",
                b"-----BEGIN PUBLIC KEY-----\ninvalid\n-----END PUBLIC KEY-----"
            )

    def test_invalid_session_key_length_raises_error(self):
        """Session key with invalid length raises RuntimeError."""
        with self.assertRaises(RuntimeError):
            ECDHKeyExchange.derive_session_key(b"short")  # Not 32 bytes

    def test_perform_key_exchange_invalid_peer_key_raises_error(self):
        """Key exchange with invalid peer key raises RuntimeError."""
        alice_private, _ = ECDHKeyExchange.generate_keypair()

        with self.assertRaises(RuntimeError):
            ECDHKeyExchange.perform_key_exchange(
                alice_private,
                b"invalid_public_key"
            )


if __name__ == "__main__":
    unittest.main()
