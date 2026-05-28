# SkyClip

**Self-hosted cross-platform clipboard sync with security hardening.**

SkyClip is a lightweight, open-source utility that automatically syncs your clipboard across multiple devices with end-to-end encryption and production-grade security.

## Security Features

| Feature | Details |
|---------|---------|
| **Argon2id Password Hashing** | GPU-resistant, memory-hard KDF (replaces PBKDF2) |
| **ECDH Perfect Forward Secrecy** | Ephemeral P-256 session keys — compromising one session exposes nothing else |
| **System Keyring Storage** | Credentials stored in OS keychain (Windows Credential Manager / macOS Keychain / libsecret) |
| **API Rate Limiting** | Per-username + per-IP lockout with configurable thresholds and DB-persistent tracking |
| **PostgreSQL** | Production database with HikariCP connection pooling and Flyway migrations |

## Quick Start (Docker Compose)

```bash
git clone https://github.com/C0R3DMP/SkyClip.git
cd SkyClip

# Set required environment variables
export POSTGRES_PASSWORD=your_secure_password
export SPRING_SECURITY_USER_NAME=admin
export SPRING_SECURITY_USER_PASSWORD=your_admin_password
export MAX_CLIPBOARD_SIZE=1048576
export MAX_MESSAGE_SIZE=1048576
export MAX_CONNECTIONS=10
export STOMP_ENDPOINT=wss://your-domain/clipsocket
export CIPHER_SECRET_KEY=your_32_byte_secret_key

docker compose up -d
```

The server starts at `http://localhost:8080`.

For H2 (development, no PostgreSQL):

```bash
docker compose -f docker-compose-h2.yml up -d
```

## Platforms

- **Linux** — system tray desktop client (Python)
- **Windows** — system tray desktop client (Python)
- **Android** — mobile client

## Configuration

See [POSTGRESQL_SETUP.md](ClipCascade_Server/POSTGRESQL_SETUP.md) for full PostgreSQL setup, data migration from H2, backup/restore procedures, and environment variable reference.

## Architecture

- **Desktop client:** Python with PyQt/PySide, Argon2id key derivation, ECDH handshake, AES-256-GCM encryption
- **Server:** Spring Boot + WebSocket (STOMP), Spring Security, Flyway, PostgreSQL
- **Mobile:** Android client

## License

MIT
