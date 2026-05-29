# SkyClip

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Linux%20%7C%20Windows%20%7C%20Android-lightgrey)](#platforms)
[![Self-hosted](https://img.shields.io/badge/self--hosted-yes-green)](#quick-start)

**Self-hosted, end-to-end encrypted clipboard sync — your data never touches a third-party server.**

---

## Why SkyClip?

Every mainstream clipboard sync tool (Apple Handoff, Windows Phone Link, Android apps) sends your clipboard through a vendor's cloud. You're trusting a company with every password, code snippet, and sensitive note you copy.

SkyClip runs entirely on hardware you control. Clipboard content is encrypted before it leaves your device, synced through your own server, and decrypted only on your other devices. No accounts, no telemetry, no vendor lock-in.

---

## Features

### Clipboard Sync
- Real-time sync across Linux, Windows, and Android
- Text, images, and files
- P2P mode for direct device-to-device transfer (no server relay)
- Configurable size limits per client

### Security
| Feature | Details |
|---------|---------|
| **Argon2id password hashing** | GPU-resistant, memory-hard KDF — replaces PBKDF2 |
| **ECDH Perfect Forward Secrecy** | Ephemeral P-256 session keys; compromising one session exposes nothing else |
| **System keyring storage** | Credentials stored in OS keychain (Windows Credential Manager / macOS Keychain / libsecret) |
| **API rate limiting** | Per-username + per-IP lockout, DB-persistent, configurable thresholds |
| **PostgreSQL** | Production database with HikariCP connection pooling and Flyway migrations |

See [SECURITY.md](SECURITY.md) for full details.

---

## Screenshots

> _Screenshots and demo GIF coming in v1.1._

---

## Quick Start (Docker Compose)

**Requirements:** Docker 24+, Docker Compose v2

```bash
git clone https://github.com/C0R3DMP/SkyClip.git
cd SkyClip/server/docker-compose

# Required environment variables
export CC_DB_USER=clipcascade
export CC_DB_PASSWORD=your_secure_db_password
export CC_SERVER_DB_URL=jdbc:postgresql://postgres:5432/clipcascade
export CC_SERVER_DB_DRIVER=org.postgresql.Driver
export CC_SERVER_DB_HIBERNATE_DIALECT=org.hibernate.dialect.PostgreSQLDialect

docker compose up -d
```

The server starts at `http://localhost:8090`. Change the host port in `docker-compose.yml` if 8090 conflicts.

Default admin credentials: **admin / admin123** — change immediately after first login.

**Development (H2, no PostgreSQL):**
```bash
docker compose -f docker-compose-h2.yml up -d
```

See [POSTGRESQL_SETUP.md](server/docker-compose/POSTGRESQL_SETUP.md) for full setup, data migration, backup/restore, and all environment variables.

---

## Desktop Client

Download the desktop client from [Releases](https://github.com/C0R3DMP/SkyClip/releases) or run from source:

```bash
cd desktop
pip install -r requirements_linux.txt   # or requirements_windows.txt
python src/main.py
```

Supported: **Linux** (system tray), **Windows** (system tray).

---

## Platforms

| Platform | Status | Tech |
|----------|--------|------|
| Linux desktop | ✅ Stable | Python, tkinter |
| Windows desktop | ✅ Stable | Python, tkinter |
| Android | ✅ Stable | React Native, Kotlin |
| macOS desktop | ⚠️ Beta | Python, tkinter |
| Server | ✅ Stable | Spring Boot, PostgreSQL |

---

## Architecture

```
┌──────────────┐    ECDH handshake    ┌─────────────────────┐
│ Desktop      │ ─────────────────── │ Spring Boot Server   │
│ (Python)     │   AES-256-GCM/STOMP │ + PostgreSQL         │
└──────────────┘                      └─────────────────────┘
                                               │
┌──────────────┐    AES-256-GCM/WS    ─────────┘
│ Android      │ ────────────────────
│ (React Native│
└──────────────┘
```

- **At-rest encryption:** Argon2id-derived master key (per device, never transmitted)
- **In-transit encryption:** ECDH-derived ephemeral session key (AES-256-GCM, unique per session)
- **Key agreement:** P-256 ECDH → HKDF(SHA-256) → AES-256-GCM with 128-bit auth tag

---

## Contributing

1. Fork the repo and create a feature branch
2. Run existing tests: `cd desktop && pytest`
3. Open a pull request — all security-related changes require review

---

## License

GPL-3.0 — see [LICENSE](LICENSE).
