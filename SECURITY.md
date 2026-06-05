# Security Hardening Documentation

## Phase 1: Password Hashing Upgrade (PBKDF2 → Argon2id)

**Status:** ✅ Implemented  
**Date:** 2026-05-28  
**Impact:** Security improvement to desktop encryption key derivation

---

## Overview

SkyClip has upgraded from **PBKDF2-HMAC-SHA256** to **Argon2id** for password-based key derivation on desktop clients. This provides:

- **GPU resistance**: Argon2id is memory-hard and resistant to GPU attacks
- **Side-channel resistance**: Protection against timing and power-analysis attacks
- **Future-proof**: Adjustable cost parameters without breaking backward compatibility

---

## What Changed

### Desktop Client (Python)

**Before:**
- Algorithm: PBKDF2-HMAC-SHA256
- Iterations: 664,937 (configurable, static)
- Purpose: Derive 256-bit AES encryption key for clipboard data

**After:**
- Algorithm: Argon2id
- Parameters:
  - `time_cost`: 3 iterations
  - `memory_cost`: 65,540 KB (64 MB)
  - `parallelism`: 4 threads
  - `hash_length`: 32 bytes (256 bits)
- Purpose: Same (AES-256 key derivation)

### Server (Java)

**No changes** - Server authentication still uses:
- Layer 1: SHA3-512 digest
- Layer 2: BCrypt (2^12 rounds, version 2b)

Server authentication is independent of client encryption key derivation.

---

## Backward Compatibility

**Old PBKDF2 hashes are automatically detected and upgraded:**

### Migration Flow

```
User logs in with old config (algorithm: "pbkdf2" or missing)
    ↓
CipherManager.hash_password() detects old hash via needs_rehash()
    ↓
Falls back to legacy PBKDF2 for decryption compatibility
    ↓
After successful authentication:
  - Re-derives key using Argon2id
  - Updates config: algorithm = "argon2id"
  - Saves config file
  - Shows user notification
    ↓
Next login uses Argon2id directly
```

### Zero Data Loss

- **Existing encrypted clipboard data remains encrypted with old key**
- Re-derivation uses exact original salt formula: `username + password + salt`
- Cross-device syncing continues to work (same salt + password = same key)
- No server-side changes required (desktop stores hash locally)

---

## Dependencies

**New requirement:**
```
argon2-cffi==23.1.0
```

Added to:
- `requirements_linux.txt`
- `requirements_mac.txt`
- `requirements_win.txt`

**Installation:**
```bash
pip install argon2-cffi==23.1.0
```

---

## Security Warnings

### If `argon2-cffi` is not installed:

1. **Warning Log:** `SECURITY WARNING: argon2-cffi library not installed. Falling back to weaker PBKDF2 hashing.`
2. **User Notification:** System tray popup alerting user to install the library
3. **Graceful Fallback:** Uses PBKDF2 to prevent crashes, but warns about weak hashing

**Action Required:** Install `argon2-cffi` immediately if this warning appears.

---

## Configuration

### New Config Field

**File:** `desktop/src/core/config.py`

```json
{
  "algorithm": "argon2id",  // New field (default for new installations)
  "hash_rounds": 664937,    // Ignored if algorithm is "argon2id"
  "salt": "...",            // Still used for salt component
  "hashed_password": "..."  // Now stores Argon2id-derived key
}
```

### Upgrading Old Configs

Old configs with:
```json
{
  "algorithm": "pbkdf2"  // or missing entirely
}
```

Will automatically be upgraded to:
```json
{
  "algorithm": "argon2id"
}
```

On next successful login.

---

## Testing

### Unit Tests

Location: `desktop/tests/test_cipher_manager.py`

**Test Coverage:**
1. Argon2id key derivation (deterministic)
2. PBKDF2 detection (old config)
3. Automatic upgrade on login
4. AES encryption key size (32 bytes)
5. Cross-device sync (same salt → same key)
6. No encryption regression

### Integration Tests

1. **Test 1: Fresh Installation**
   - Install new version
   - Login → algorithm="argon2id" created

2. **Test 2: Upgrade from Old Version**
   - Copy old config (algorithm="pbkdf2")
   - Login → hash detected
   - Config upgraded to "argon2id"
   - Verify file updated

3. **Test 3: Cross-Device Sync**
   - Device A: New Argon2id
   - Device B: New Argon2id
   - Same username + salt → same key → clipboard syncs

4. **Test 4: No Regression**
   - Encryption/decryption works
   - Clipboard items sync correctly
   - No crashes or data loss

---

## Performance Impact

| Operation | Time | Impact |
|-----------|------|--------|
| **Key Derivation (Argon2id)** | ~100-200ms | Once per login, minimal user impact |
| **AES-256-GCM Encryption** | <1ms per message | Unchanged |
| **AES-256-GCM Decryption** | <1ms per message | Unchanged |
| **Config Save** | <10ms | Negligible |

---

## Rollback Plan

If critical issues found:

1. **Revert to PBKDF2:**
   - Downgrade to previous version
   - Existing Argon2id configs → detected as unknown algorithm → fallback to PBKDF2
   - Users can manually delete DATA file and re-login if needed

2. **Data Safety:**
   - No data loss (config file preserved)
   - Old clipboard data still encrypted with original key
   - No server-side changes to rollback

---

## Future Improvements

### Task 2: Perfect Forward Secrecy (PFS)
- Add ECDH (X25519) ephemeral key exchange per session
- Session key = ECDH(shared_secret) + Argon2id(password)
- If long-term password compromised, past sessions remain secure

### Task 3: Secure Config Storage
- Move plaintext password out of config file
- Use system keyring:
  - Linux: `libsecret`
  - Windows: Windows Credential Manager
  - macOS: Keychain

### Task 4: Server-Side Upgrade (Optional)
- Upgrade server authentication from BCrypt to Argon2
- Separate from desktop encryption (independent concern)
- Maintains backward compatibility with old hashes

---

## References

- **Argon2id Specification**: https://github.com/P-H-C/phc-winner-argon2
- **argon2-cffi Documentation**: https://argon2-cffi.readthedocs.io/
- **AES-256-GCM**: NIST SP 800-38D

---

## Deployment & Encryption Hardening Notes

Operational recommendations for self-hosters. These do **not** change default
behavior; they help you harden a deployment.

### Encryption key derivation — all devices in a sync group must match

Each device derives the end-to-end encryption key locally from
`username + password + custom_salt` via a key-derivation function (KDF). For
clipboard sync to work, **every device in a sync group must use the same KDF
and the same salt**:

- **Default KDF: PBKDF2-HMAC-SHA256** (664,937 iterations). This is compatible
  across the desktop and mobile clients out of the box.
- The desktop also supports **Argon2id** (stronger, memory-hard) as an explicit
  opt-in via the `algorithm` config field. **Argon2id keys are not compatible
  with PBKDF2 keys** — only enable it if *every* device in the group is a
  desktop set to Argon2id. (The mobile client is PBKDF2-only.)
- The custom **salt** is empty by default. The effective KDF salt always
  includes your username and password, but if you sync sensitive data across
  **multiple servers**, set a shared non-empty custom salt on all devices to
  avoid cross-server key correlation (same username + password would otherwise
  derive the same key on different servers).

### Restrict allowed origins (`CC_ALLOWED_ORIGINS`)

Defaults to `*` (any origin). Native desktop/mobile clients send no `Origin`
header and are unaffected, but if the server is reachable from browsers, set
`CC_ALLOWED_ORIGINS` to your exact origin (e.g. `https://clip.example.com`) so
arbitrary websites cannot open WebSocket connections.

### Session timeout (`CC_SESSION_TIMEOUT`)

Defaults to a very long value (~1 year) so sync clients stay logged in. If a
stolen session cookie is a concern for your threat model, lower it
(e.g. `CC_SESSION_TIMEOUT=480m` for 8 hours).

### HTTPS and HSTS

Always run SkyClip behind TLS. The server sends a `Strict-Transport-Security`
header, but Spring only emits it on requests it sees as **secure**. If you
terminate TLS at a reverse proxy, the app receives plain HTTP and will not emit
HSTS unless it is made aware the request is secure (terminate TLS at the app, or
configure the app/proxy so the request is treated as secure).
`Referrer-Policy: same-origin`, `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, and a Content-Security-Policy are always sent.

---

## Questions & Support

- **Security concern?** Report to: [security contact]
- **Installation issue?** Check requirements file and run: `pip install argon2-cffi==23.1.0`
- **Data loss?** All old encryption keys preserved; fallback to PBKDF2 if needed

