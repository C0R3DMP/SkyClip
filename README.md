# SkyClip

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Linux%20%7C%20Windows%20%7C%20Android-lightgrey)](#platforms)
[![Self-hosted](https://img.shields.io/badge/self--hosted-yes-green)](#quick-start)

**Self-hosted, end-to-end encrypted clipboard sync — your data never touches a third-party server.**

---

## ⚠️ Security Advisory — v1.0.0 Users

**A critical bug in v1.0.0 hardcoded the admin password to `admin123` and silently ignored
`CC_ADMIN_PASSWORD`.** Every v1.0.0 deployment — regardless of how `CC_ADMIN_PASSWORD` was
configured — has admin credentials of `admin` / `admin123` with no warning in the UI or logs.

**v1.0.1 fixes this.** `CC_ADMIN_PASSWORD` is now fully honored. If unset, a strong random
password is generated at startup and printed once to the server log — look for the line marked
**`GENERATED ADMIN PASSWORD`**.

**Action required if you ran v1.0.0:**
1. Upgrade to v1.0.1 immediately.
2. **Existing database:** the admin password is still `admin123` in your stored data — log in
   with `admin` / `admin123` and change it immediately. **Fresh install:** set
   `CC_ADMIN_PASSWORD` before first launch; `admin123` will not work.
3. Review your server access logs for unauthorized logins during the exposure window.

---

## What's New in v1.0.2

**Security**
- Trusted proxy hardening (S-01): `X-Forwarded-For` restricted to configured upstream only
- Session security (S-03): secure cookie flags, idle timeout, session fixation protection
- Content Security Policy (S-05): strict CSP headers on all server responses
- WebSocket broker hardening (S-06): destination prefix restrictions enforced
- Mobile credential storage (S-02 + S-04): `password` and `hashed_password` migrated from
  AsyncStorage (plaintext) to Android `EncryptedStorage` (hardware-backed AES-256)

**Bug fixes**
- Version check no longer shows false "update available" (was comparing against upstream ClipCascade)
- `UserService.updateUsername`: silent crash on missing user replaced with clear exception
- `fetchTimeout`: timer leak on successful requests fixed (try/finally)
- Three implicit global variable declarations (`validResult`, `hashResult`, `wsIsRunning_s`)

**Branding**
- Server web UI (login, signup, admin pages) fully rebranded to SkyClip
- All remaining upstream ClipCascade URLs replaced with C0R3DMP/SkyClip
- macOS PyInstaller spec renamed `SkyClip_macos.spec`; output binary named `SkyClip`

---

## What's New in v1.0.1

**Security**
- Fixed critical bug: admin password was hardcoded to `admin123`; `CC_ADMIN_PASSWORD` is now
  honored (if unset, a random password is generated and logged at startup)

**Android**
- Session persistence: app stays logged in across restarts (cookie jar fix)
- Login reliability: resolved authentication failures caused by missing session cookies
- Clearer error messages when login fails or the server is unreachable

**Branding**
- Mobile app UI rebranded to SkyClip (login screen, main screen, notifications)

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
| **Server auth: BCrypt + SHA3-512** | Login password hashed with SHA3-512 client-side, stored as BCrypt on the server |
| **Desktop E2E key: Argon2id** | Encryption key derived via Argon2id — GPU-resistant, memory-hard, replaces PBKDF2 |
| **ECDH key exchange (server-side, dormant)** | The `/api/ecdh/handshake` endpoint and key derivation exist and are correct, but no client performs the handshake and the server never applies the resulting session key to real traffic — Perfect Forward Secrecy is not yet active end-to-end. See [SECURITY.md](SECURITY.md). |
| **System keyring storage** | Credentials stored in OS keychain (Windows Credential Manager / macOS Keychain / libsecret) |
| **API rate limiting** | Per-username + per-IP lockout, DB-persistent, configurable thresholds |
| **PostgreSQL** | Production database with HikariCP connection pooling; schema applied via `schema.sql` on startup |

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
export CC_ADMIN_PASSWORD=your_secure_admin_password
export CC_SERVER_DB_URL=jdbc:postgresql://postgres:5432/clipcascade
export CC_SERVER_DB_DRIVER=org.postgresql.Driver
export CC_SERVER_DB_HIBERNATE_DIALECT=org.hibernate.dialect.PostgreSQLDialect

docker compose up -d
```

The server starts at `http://localhost:8090`. Change the host port in `docker-compose.yml` if 8090 conflicts.

If `CC_ADMIN_PASSWORD` is unset, a random password is generated at startup — check the log for the line marked **`GENERATED ADMIN PASSWORD`**.

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
┌──────────────┐   AES-256-GCM/STOMP  ┌─────────────────────┐
│ Desktop      │ ─────────────────── │ Spring Boot Server   │
│ (Python)     │                      │ + PostgreSQL         │
└──────────────┘                      └─────────────────────┘
                                               │
┌──────────────┐    AES-256-GCM/WS    ─────────┘
│ Android      │ ────────────────────
│ (React Native│
└──────────────┘
```

- **At-rest encryption:** Argon2id- or PBKDF2-derived master key (per device, never transmitted) — this is what actually protects clipboard content in transit today; the server only ever relays ciphertext it cannot read.
- **In-transit key exchange (dormant):** P-256 ECDH → HKDF(SHA-256) → AES-256-GCM session key. The server-side implementation is correct, but no client performs the handshake and the server never applies the derived key to real messages, so it provides no protection yet — see [SECURITY.md](SECURITY.md).

---

## Contributing

1. Fork the repo and create a feature branch
2. Run existing tests: `cd desktop && pytest`
3. Open a pull request — all security-related changes require review

---

## License

GPL-3.0 — see [LICENSE](LICENSE).
