# SkyClip Architecture Documentation

**Project**: SkyClip — Self-Hosted Clipboard Synchronization  
**Architecture Version**: 1.0  
**Analysis Date**: 2026-05-28  

---

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          SKYSCAPE SYSTEM                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  CLIENTS                          SERVERS                           │
│  ─────────────                    ───────────                       │
│  ┌──────────────┐      WebSocket     ┌──────────────────┐          │
│  │ Windows      │◄────────────────►  │  Java Spring     │          │
│  │ Clipboard    │ (STOMP/RawWS)      │  Boot Backend    │          │
│  │ Monitor      │                    │                  │          │
│  └──────────────┘                    │  • Auth/Users    │          │
│                                      │  • Message Broker│          │
│  ┌──────────────┐                    │  • BFA Protection│          │
│  │ macOS        │◄────────────────►  │  • P2P Signaling │          │
│  │ Clipboard    │ AES-256-GCM        │  • DB (H2/PgSQL) │          │
│  │ Monitor      │ Encrypted          └──────────────────┘          │
│  └──────────────┘                                                  │
│                                      ┌──────────────────┐          │
│  ┌──────────────┐                    │  H2 Database     │          │
│  │ Linux (X11)  │◄────────────────►  │  (Encrypted)     │          │
│  │ Gtk Monitor  │                    │                  │          │
│  │ xclip/xsel   │                    │  Tables:         │          │
│  └──────────────┘                    │  • users         │          │
│                                      │  • user_info     │          │
│  ┌──────────────┐                    │  • sessions      │          │
│  │ Linux        │                    │  • BFA tracker   │          │
│  │ (Wayland)    │                    │  • IP attempts   │          │
│  │ wl-clipboard │                    └──────────────────┘          │
│  └──────────────┘                                                  │
│                                                                     │
│  ┌──────────────┐         Optional                                 │
│  │ Android      │     STUN/TURN Server                             │
│  │ ClipboardMgr │     (For P2P NAT traversal)                      │
│  └──────────────┘                                                  │
│                                                                     │
│  ┌──────────────────┐                                              │
│  │ React Native App │ (Mobile - iOS/Android)                       │
│  └──────────────────┘                                              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Data Flow: Clipboard Change → Sync → Other Device

### **Scenario: User copies text on Device A, receives on Device B**

```
DEVICE A (SENDER)
─────────────────────────────────────────────────────────

┌─ User copies "Hello World" ────┐
│                                 │
▼                                 │
[Clipboard Monitor]◄─────────────┘
│ (Platform-specific detection)
│
▼
[ClipboardManager]
│ • Hash clipboard content (xxHash64)
│ • Check if changed vs previous
│ • Validate size limits
│
▼
[Encrypt if enabled]
│ • Input: "Hello World"
│ • Salt: username + password + custom_salt
│ • PBKDF2-SHA256 (664,937 rounds)
│   → Derive 256-bit AES key
│ • AES-256-GCM encryption
│   → Generate random nonce
│   → Encrypt plaintext
│   → Generate auth tag
│ • Output: {nonce, ciphertext, tag}
│ • Base64-encode for JSON
│
▼
[JSON Message]
{
  "payload": "base64-encoded-encrypted-data",
  "type": "text"
}
│
▼
[WebSocket Send (STOMP)]
Destination: /app/clipboard
│
▼
[Java Server - Message Broker]
│ • Route message via STOMP
│ • All user's subscriptions receive
│ • Persist in queue if external broker

DEVICE B (RECEIVER)
─────────────────────────────────────────────────────────

[WebSocket Receive]
│
▼
[Message Parse]
{
  "payload": "base64-encrypted",
  "type": "text"
}
│
▼
[Decrypt if enabled]
│ • Base64-decode
│ • Extract: nonce, ciphertext, tag
│ • Same salt & key derivation
│ • AES-256-GCM decrypt_and_verify
│ • Output: "Hello World"
│
▼
[ClipboardManager.base64_to_clipboard()]
│ • Validate size vs local limits
│ • Set clipboard content
│
▼
[Platform-specific Paste]
│ Windows: win32clipboard.SetClipboardData()
│ macOS:   pasteboard.write()
│ Linux:   xclip / wl-copy
│ Android: ClipboardManager.setPrimaryClip()
│
▼
[System Tray Notification]
"Clipboard synced from Device A"
```

---

## 3. Core Components & File Structure

### **desktop/** (Python 3.12)
```
src/
├── main.py                          # Entry point
├── core/
│   ├── application.py               # Main app lifecycle
│   ├── config.py                    # Config parser (login credentials)
│   ├── constants.py                 # Platform detection, timeouts
│
├── clipboard/
│   ├── clipboard_manager.py         # Unified clipboard API
│   │   ├ clipboard_monitor_win.py   # Windows (win32 event-driven)
│   │   ├ clipboard_monitor_mac.py   # macOS (0.3s polling)
│   │   ├ clipboard_monitor_linux.py # Linux (GTK/wl-clipboard)
│   │   └ Methods: on_copy(), base64_to_clipboard(), convert_*()
│
├── stomp_ws/
│   ├── stomp_manager.py             # STOMP protocol handler
│   │                                 # (Inherits from WSInterface)
│   │   └ connect(), send(), _receive(), disconnect()
│   ├── client.py                    # WebSocket client
│   └── frame.py                     # STOMP frame parsing
│
├── p2p/
│   └── p2p_manager.py               # P2P WebRTC handler (if used)
│
├── utils/
│   ├── cipher_manager.py            # AES-256-GCM encryption
│   │   └ encrypt(), decrypt(), hash_password()
│   ├── notification_manager.py      # Desktop notifications
│   ├── ssl_helper.py                # SSL/TLS config
│   ├── request_manager.py           # HTTP requests
│   └── window_manager.py            # Window utilities
│
├── gui/
│   ├── tray.py                      # System tray (GTK)
│   ├── login.py                     # Login UI
│   └── message_box.py               # Dialogs
│
├── cli/
│   ├── tray.py                      # Terminal UI (Tkinter)
│   ├── login.py                     # CLI login
│   ├── info.py                      # Status display
│   └── echo.py                      # Terminal output
│
└── requirements_*.txt               # Dependencies per platform
```

**Key Dependencies:**
- `websocket-client`: WebSocket protocol
- `pycryptodomex`: AES-256-GCM
- `xxhash`: Fast content hashing
- `Pillow`: Image processing (send/receive)
- `pyperclip`: Clipboard (Windows/macOS)
- `pystray`: System tray icon
- `plyer`: Notifications
- `requests`: HTTP
- `bs4`: HTML parsing

---

### **server/** (Java 21 + Spring Boot 3.x)
```
backend/src/main/java/com/acme/clipcascade/
│
├── SkyClipApplication.java      # Main Spring Boot app
│
├── config/
│   ├── SecurityConfiguration.java   # Spring Security setup
│   ├── StompWebSocketConfig.java    # STOMP message broker (P2S mode)
│   ├── P2PWebSocketConfig.java      # Raw WebSocket (P2P signaling)
│   ├── P2PWebSocketHandler.java     # P2P connection management
│   ├── JacksonConfig.java           # JSON serialization
│   ├── HashConfig.java              # PBKDF2 hashing
│   ├── CacheConfig.java             # Redis/Caffeine cache
│   ├── AppProperties.java   # Configuration properties
│   └── CustomAuthenticationSuccessHandler.java
│
├── model/
│   ├── Users.java                   # User credentials
│   ├── UserInfo.java                # User metadata
│   ├── UserPrincipal.java           # Auth principal
│   ├── ClipboardData.java           # Message payload
│   ├── IpAttemptDetails.java        # BFA tracker
│   ├── UserAccessTracker.java       # Access log
│   └── Timeout.java                 # Session timeout
│
├── repo/
│   ├── UserRepo.java                # User CRUD
│   └── UserInfoRepo.java            # UserInfo CRUD
│
├── service/
│   ├── FacadeUserService.java       # User ops (composite)
│   ├── UserService.java             # User persistence
│   ├── UserInfoService.java         # User info ops
│   ├── SessionService.java          # Session lifecycle
│   ├── BruteForceProtectionService.java # BFA logic
│   ├── BruteForceScheduledTask.java     # BFA cleanup jobs
│   ├── CaptchaService.java          # CAPTCHA validation
│   ├── WebSocketStatsService.java   # Connection metrics
│   ├── DonationService.java         # Donation links
│   └── SystemInfoService.java       # System health
│
├── controller/
│   └── SkyClipController.java   # REST + STOMP endpoints
│       ├ @PostMapping /login        # Login
│       ├ @PostMapping /signup       # Register
│       ├ @MessageMapping /clipboard # STOMP message handler
│       ├ @SendTo /queue/[user-id]   # Send to specific user
│       └ Health/Admin endpoints
│
├── utils/
│   ├── HashingUtility.java          # PBKDF2 hashing
│   ├── IpAddressResolver.java       # Client IP extraction
│   ├── TimeUtility.java             # Timestamp utilities
│   ├── MapUtility.java              # Map operations
│   ├── ResponseEntityUtil.java      # Response formatting
│   └── UserValidator.java           # Input validation
│
├── constants/
│   ├── ServerConstants.java         # Timeouts, heartbeats
│   ├── RoleConstants.java           # Role definitions
│   └── IpResolverConstants.java     # IP header names
│
└── resources/
    └── schema.sql                   # Database schema
```

**Key Technologies:**
- Spring Boot 3.x (Web, WebSocket, Security)
- Spring Security (Authentication)
- Spring Data JPA (ORM)
- H2 Database (default, encrypted)
- Hibernate (ORM)
- Jackson (JSON)
- SLF4J/Logback (Logging)
- Caffeine (Local cache)
- Optional: PostgreSQL, RabbitMQ (external broker)

---

### **mobile/** (React Native)
```
src/
├── App.tsx                          # Root component
├── package.json                     # Dependencies
├── app.json                         # Expo config
│
├── screens/
│   ├── LoginScreen.tsx              # Auth UI
│   ├── ClipboardScreen.tsx          # Main clipboard UI
│   └── SettingsScreen.tsx           # Config UI
│
├── services/
│   ├── WebSocketService.ts          # STOMP/WebSocket
│   ├── ClipboardService.ts          # ClipboardManager
│   ├── EncryptionService.ts         # AES-256-GCM
│   └── StorageService.ts            # Secure storage
│
└── navigation/                      # React Navigation
```

**Tech Stack:**
- React Native 0.73+
- Expo (build system)
- TypeScript
- react-native-clipboard
- crypto-js or libsodium (AES)
- WebSocket API

---

## 4. Database Schema

### **users table** (Spring Security User)
```sql
CREATE TABLE users (
    username VARCHAR(255) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,              -- PBKDF2 hash
    role VARCHAR(255) NOT NULL DEFAULT 'USER',  -- USER, ADMIN
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
```

### **user_info table** (Extended Profile)
```sql
CREATE TABLE user_info (
    username VARCHAR(255) PRIMARY KEY,
    marked_for_deletion BOOLEAN DEFAULT FALSE,
    first_signup BIGINT,                         -- Unix timestamp
    last_login BIGINT,
    first_signup_ip VARCHAR(255),
    last_login_ip VARCHAR(255),
    failed_login_attempts INT DEFAULT 0,
    lockout_time VARCHAR(255),                  -- ISO format or millis
    password_changed_at BIGINT,
    email VARCHAR(255),
    otp VARCHAR(255),                           -- 2FA (if enabled)
    phone VARCHAR(20),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    notes TEXT
);
```

### **Implicit Tables** (Runtime)
- **SPRING_SESSION**: HTTP session store (if DB-backed)
- **SPRING_SESSION_ATTRIBUTES**: Session attributes
- **BFA Tracker**: In-memory map or disk cache (configurable)

---

## 5. Communication Protocols

### **Protocol 1: STOMP over WebSocket (P2S Mode - Server-Based)**

**Endpoint:** `ws://server:8080/clipsocket`

**Handshake:**
```
CLIENT → SERVER:
CONNECT
login:username
passcode:encoded-session-token
accept-version:1.0,1.1,1.2

SERVER → CLIENT:
CONNECTED
version:1.2
server:SkyClip-STOMP
heartbeat:25000,25000
```

**Message Send (Clipboard Sync):**
```
CLIENT → SERVER:
SEND
destination:/app/clipboard
content-length:150

{
  "payload": "SGVsbG8gV29ybGQh==",
  "type": "text"
}

SERVER → BROKER → ALL SUBSCRIBERS:
MESSAGE
subscription:0
message-id:ID:server:1
destination:/user/{username}/queue/clipboard

{
  "payload": "SGVsbG8gV29ybGQh==",
  "type": "text"
}
```

**Heartbeat:**
- Server sends PING every 25 seconds
- Client sends PONG
- Connection closes if heartbeat missed

---

### **Protocol 2: Raw WebSocket (P2P Mode - Signaling)**

**Endpoint:** `ws://server:8080/p2psignaling`

**Connection Established (Peer Assignment):**
```
SERVER → CLIENT:
{
  "type": "ASSIGNED_ID",
  "peerId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Peer List Broadcast:**
```
SERVER → CLIENT (All in user's room):
{
  "type": "PEER_LIST",
  "peers": [
    {
      "peerId": "550e8400-e29b-41d4-a716-446655440000",
      "username": "user1",
      "devices": ["windows", "android"]
    },
    ...
  ]
}
```

**P2P Signaling (SDP, ICE):**
```
CLIENT A → SERVER → CLIENT B:
{
  "type": "OFFER",
  "peerId": "550e8400-...",
  "data": {
    "sdp": "v=0\no=...",
    "ice_candidates": [...]
  }
}
```

---

## 6. Encryption Implementation (AES-256-GCM)

### **Key Derivation**
```python
def hash_password(password: str) -> bytes:
    return hashlib.pbkdf2_hmac(
        hash_name='sha256',
        password=password.encode(),
        salt=(username + password + custom_salt).encode(),
        iterations=664937,  # Configurable per device
        dklen=32  # 256 bits for AES-256
    )
```

### **Encryption**
```python
def encrypt(plaintext: str) -> dict:
    key = hashed_password  # 32 bytes
    cipher = AES.new(key, AES.MODE_GCM)
    ciphertext, tag = cipher.encrypt_and_digest(plaintext.encode())
    
    return {
        "nonce": cipher.nonce,          # 16 bytes
        "ciphertext": ciphertext,       # Variable
        "tag": tag                      # 16 bytes (auth)
    }
```

### **Decryption**
```python
def decrypt(nonce: bytes, ciphertext: bytes, tag: bytes) -> str:
    key = hashed_password  # Same derivation
    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    
    return cipher.decrypt_and_verify(ciphertext, tag).decode()
```

### **Message Format (JSON)**
```json
{
  "nonce": "base64-encoded-nonce",
  "ciphertext": "base64-encoded-ciphertext",
  "tag": "base64-encoded-authentication-tag"
}
```

**Security Notes:**
- ✅ Authenticated encryption (GCM tag prevents tampering)
- ✅ Random nonce per message (prevents repeats)
- ✅ No server-side keys (server never sees plaintext)
- ⚠️ Static key from password (no perfect forward secrecy)
- ⚠️ Same salt on all devices (must be coordinated)

---

## 7. Security Features

### **Authentication**
- **Method:** Username + Password (HTTP Basic or form)
- **Storage:** PBKDF2-SHA256 hashed (664,937 iterations)
- **Session:** Spring Security (cookies + JSESSIONID)
- **Optional:** 2FA via OTP

### **Brute Force Protection (BFA)**
```
MAX_UNIQUE_IP_ATTEMPTS: 15          # Unique IPs can try
MAX_ATTEMPTS_PER_IP: 30             # Total per IP
LOCK_TIMEOUT_SECONDS: 60            # Initial lockout
LOCK_TIMEOUT_SCALING_FACTOR: 2      # Exponential backoff (60, 120, 240...)
BFA_CACHE_ENABLED: false            # Optional in-memory tracking
```

### **Rate Limiting**
- Global WebSocket connections: Configurable
- Per-user connections: Configurable
- Message size: 1 MiB default (1 GiB in P2P mode)

### **CORS & Origins**
```
CC_ALLOWED_ORIGINS: *               # Or specific domain
```

### **Data Isolation**
- **Per-user message queues** (STOMP `/user/{username}/queue/...`)
- **No cross-user data leakage**
- **Account deletion/purging** (optional auto-cleanup)

---

## 8. Known Weaknesses & Proposed Solutions

| # | Weakness | Risk | Solution | Priority |
|---|----------|------|----------|----------|
| **1** | **Static encryption key from password** | No Perfect Forward Secrecy (PFS) | Add ephemeral key exchange (ECDH) or hybrid encryption | HIGH |
| **2** | **Password visible in config file (desktop)** | Local compromise = full access | Encrypt config on disk, use system keyring | HIGH |
| **3** | **No message logging/audit trail** | Can't detect clipboard theft | Add optional server-side encrypted audit log | MEDIUM |
| **4** | **H2 default DB lacks production hardening** | Single-file DB, embedded, not clustered | Mandate PostgreSQL for production, add replication | MEDIUM |
| **5** | **PBKDF2 iterations hardcoded (664,937)** | Becomes weak as CPUs improve | Use Argon2 instead, increase iterations yearly | MEDIUM |
| **6** | **No rate limiting on login endpoint** | Brute force (though BFA helps) | Add HTTP-level rate limiting (nginx, WAF) | MEDIUM |
| **7** | **Clipboard data transits in WebSocket (even encrypted)** | Network analysis can see packet patterns | Add random padding, dummy messages | LOW |
| **8** | **No file integrity verification** | Modified executables go undetected | Add code signing, SRI for JS assets | MEDIUM |
| **9** | **P2P STUN server is hardcoded** | Centralized third-party trust | Allow custom STUN/TURN server config | LOW |
| **10** | **No end-of-life (EOL) versions** | Old vulnerable versions stay usable | Enforce minimum version policy | HIGH |
| **11** | **Session tokens in cookies (HTTP only)** | XSS can still steal from localStorage | Ensure Secure + HttpOnly flags always set | MEDIUM |
| **12** | **No device fingerprinting** | Stolen session cookie works anywhere | Add optional device-based 2FA | LOW |

---

### **Detailed Solutions**

#### **Solution 1: Perfect Forward Secrecy (PFS)**
```
Add ECDH key exchange on login:
  1. Client generates ephemeral EC keypair
  2. Exchanges public key with server (over HTTPS)
  3. Server generates matching keypair
  4. Shared secret = ECDH(client_priv, server_pub)
  5. Session key = HKDF(shared_secret, session_id)
  6. Use session key instead of static password hash
  → If long-term password compromised, past sessions still safe
```

#### **Solution 2: Secure Config Storage**
```
Desktop Client (Python):
  • Store password in system keyring (keyring library)
  • Encrypt config file at rest (AES-256)
  • Derive keyring access from device fingerprint
  
Mobile (React Native):
  • Use react-native-secure-storage (encrypted)
  • Biometric unlock (Face ID / fingerprint)
```

#### **Solution 3: Audit Logging**
```
Server-side optional log:
  • Timestamp, username, device_id, ip_address
  • Message hash (not content - encryption key unknown)
  • Size, type (text/image/files)
  • Encrypted with master key (separate from user keys)
  • Retention policy: 90 days default
  • Searchable via admin dashboard
```

#### **Solution 4: Upgrade PBKDF2 → Argon2**
```
def hash_password_v2(password, username, salt):
    # Argon2id: resistant to both GPU and side-channel attacks
    import argon2
    
    full_salt = (username + salt).encode()
    hasher = argon2.PasswordHasher(
        time_cost=3,      # iterations
        memory_cost=65540, # ~64 MB
        parallelism=4
    )
    return hasher.hash(password + full_salt)
```

#### **Solution 5: Database Hardening (PostgreSQL)**
```yaml
# docker-compose.yml for production
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: clipcascade
      POSTGRES_PASSWORD: ${STRONG_PASSWORD}
    volumes:
      - ./pg_init.sql:/docker-entrypoint-initdb.d/
    restart: always
    healthcheck:
      test: ["CMD", "pg_isready"]
      interval: 10s

  pgbackups:
    image: pgbackups/pgbackups
    volumes:
      - ./backups:/backups
    environment:
      DATABASE_URL: postgresql://...
```

#### **Solution 6: HTTP Rate Limiting (Nginx)**
```nginx
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=signup:10m rate=2r/m;

location /login {
    limit_req zone=login burst=10 nodelay;
    proxy_pass http://localhost:8080;
}

location /signup {
    limit_req zone=signup burst=5 nodelay;
    proxy_pass http://localhost:8080;
}
```

#### **Solution 7: Device Fingerprinting (Optional)**
```python
def get_device_fingerprint():
    # Combine:
    # - User-Agent
    # - IP address
    # - Browser/OS version
    # - Timezone
    # - Screen resolution
    # - Language
    
    fingerprint = hashlib.sha256(
        (ua + ip + os_version + tz + resolution).encode()
    ).hexdigest()
    
    # Store on first login, require re-auth if changed
    return fingerprint
```

---

## 9. Deployment Modes

### **Mode 1: Server-Based (P2S - Peer-to-Server)**
```
CC_P2P_ENABLED=false (default)

Pros:
  + Simple architecture
  + All routing through server
  + Works with NAT/firewalls
  + Message size limited (1 MiB)

Cons:
  - Server bottleneck
  - Higher latency
  - Server sees message metadata
```

### **Mode 2: Peer-to-Peer (P2P - Direct)**
```
CC_P2P_ENABLED=true

Pros:
  + Lower latency
  + No server bandwidth (signaling only)
  + Unlimited message size
  + Better privacy

Cons:
  - Requires STUN/TURN server for NAT traversal
  - More client complexity (WebRTC)
  - Firewall issues possible
  - Still needs server for auth
```

---

## 10. Performance Characteristics

| Metric | Value | Notes |
|--------|-------|-------|
| **Message Latency (LAN)** | 100-500ms | STOMP overhead |
| **Message Latency (P2P)** | 20-100ms | Direct WebRTC |
| **Encryption Overhead** | ~5-10ms | AES-256-GCM |
| **Max Clipboard Size** | 1 MiB (P2S) / Unlimited (P2P) | Configurable |
| **Max Concurrent Users** | -1 (unlimited) | Per `CC_MAX_USER_ACCOUNTS` |
| **Max Connections/User** | -1 (unlimited) | Per `CC_MAX_WS_CONNECTIONS_PER_USER` |
| **Session Timeout** | 525960m (~1 year) | Configurable |
| **Heartbeat Interval** | 25s send / 25s receive | STOMP protocol |

---

## 11. File Sharing Implementation

### **Desktop to Desktop (Text/Images Work Directly)**
```
Text: Copy → Base64 → Encrypt → Send → Decrypt → Paste ✅
Image: Copy → Base64(PNG/BMP/TIFF) → Encrypt → Send → Decrypt → Display ✅
Files: Copy paths → JSON with file metadata → Base64 each → Send
  → Receive → Save to temp folder → Notification + Download button
```

### **Mobile Clipboard Limitations**
```
Android:
  • Text: ✅ Full support
  • Image: ✅ Full support (via share)
  • Files: ⚠️ Limited (can only share via intent, not clipboard)
  
iOS: TBD (not yet implemented)
```

---

## 12. Update & Versioning

- **Semantic Versioning (SemVer)**: X.Y.Z
- **Current Version**: Check `version.json`
- **Built-in Update Checker**: Auto-detects newer releases
- **Desktop Clients**: Manual updates (download from GitHub)
- **Server**: Docker image updates or JAR replacement
- **Mobile**: Play Store / App Store updates

---

## 13. Summary: Architecture Strengths & Roadmap

### **Strengths**
✅ End-to-end encryption with AES-256-GCM  
✅ Cross-platform (Windows, macOS, Linux, Android)  
✅ Flexible deployment (P2S or P2P mode)  
✅ Brute force protection  
✅ Self-hostable with Docker  
✅ Open source (GPL-3.0)  

### **Roadmap (TODOs)**
- [ ] iOS support (React Native)
- [ ] Clipboard history with encryption
- [ ] OAuth/OIDC authentication
- [ ] Advanced audit logging
- [ ] Device fingerprinting
- [ ] Argon2 password hashing
- [ ] Perfect forward secrecy (ECDH)

---

## 14. Contact & Support

- **GitHub**: https://github.com/C0R3DMP/SkyClip
- **Email**: https://github.com/C0R3DMP/SkyClip/issues
- **Issues**: GitHub Issues page
- **Discussions**: GitHub Discussions

---

**End of Architecture Documentation**

Generated: 2026-05-28  
Architecture Version: 1.0  
Analyzer: Claude Code / SkyForge PM
