# PostgreSQL Migration Guide

## Overview

This document explains how to migrate SkyClip from H2 (default) to PostgreSQL.

## Quick Start: Docker Compose with PostgreSQL

### 1. Set Required Environment Variables

Before starting, set the database credentials:

```bash
export CC_DB_USER=clipcascade
export CC_DB_PASSWORD=your_secure_password_here
```

**⚠️ IMPORTANT:** These variables are REQUIRED. Docker Compose will fail if not set.

### 2. Start the Stack

```bash
cd docker-compose
docker-compose up -d
```

This will:
- Create a PostgreSQL service with persistent data volume
- Start SkyClip configured to use PostgreSQL
- Automatically run all database migrations

### 3. Verify the Connection

```bash
docker logs clipcascade | grep "Flyway"
```

You should see Flyway migrations running successfully.

---

## Switching Between H2 and PostgreSQL

### Use PostgreSQL (Default in docker-compose.yml)

```bash
# Requires CC_DB_USER and CC_DB_PASSWORD to be set
docker-compose up
```

### Use H2 (Development Only)

```bash
# H2 file-based database, no external service needed
docker-compose -f docker-compose-h2.yml up
```

---

## Migration from H2 to PostgreSQL

### Scenario 1: Fresh Installation
- No H2 data to migrate
- Start with PostgreSQL directly
- Flyway initializes schema automatically

### Scenario 2: Existing H2 Database

**Option A: Start Fresh (Recommended for Testing)**
1. Delete old H2 database files: `rm -rf ./database/`
2. Start fresh with PostgreSQL
3. Re-create user accounts

**Option B: Export → Import (For Data Preservation)**

1. Export data from H2:
   ```bash
   # Start H2 database
   docker-compose -f docker-compose-h2.yml up -d
   
   # Wait for startup, then export
   docker exec clipcascade-h2 java -cp /app.jar org.h2.tools.Shell \
     -url "jdbc:h2:file:./database/clipcascade;CIPHER=AES" \
     -user clipcascade \
     -password "$CC_SERVER_DB_PASSWORD" \
     -sql "SCRIPT TO 'export.sql';" > h2_export.sql
   ```

2. Import to PostgreSQL:
   ```bash
   docker exec clipcascade-postgres psql -U clipcascade -d clipcascade \
     -f h2_export.sql
   ```

---

## Schema Compatibility

### Task 4 (API Rate Limiting) Schema

The `login_attempts` table created in Task 4 is **fully compatible** with both H2 and PostgreSQL:

**H2:** `BIGINT AUTO_INCREMENT` → PostgreSQL: `BIGINT` with `SERIAL` equivalent ✅
**Both:** `VARCHAR`, `DATETIME/TIMESTAMP`, `FOREIGN KEY` fully compatible ✅

No schema changes needed. Flyway handles the migration transparently.

---

## Configuration Files

### Docker Compose (PostgreSQL)
**File:** `docker-compose/docker-compose.yml`

Includes:
- PostgreSQL service with health checks
- SkyClip service with PostgreSQL configuration
- Persistent data volume (`postgres-data`)
- Secure password handling (required env vars)

### Docker Compose (H2)
**File:** `docker-compose/docker-compose-h2.yml`

For development only. No external dependencies.

### Application Configuration

**Base Configuration:** `application.properties`
- Spring profile selection
- Common settings (logging, timeouts, etc.)

**PostgreSQL Profile:** `application-postgres.properties`
- Database URL, driver, dialect
- HikariCP connection pool settings (10 max connections)

**H2 Profile:** `application-h2.properties`
- H2 file-based database settings
- Smaller connection pool (5 max connections)

---

## Environment Variables

### Required for PostgreSQL

- `CC_DB_USER` — PostgreSQL username (e.g., `clipcascade`)
- `CC_DB_PASSWORD` — PostgreSQL password (must be secure, no defaults)
- `CC_SERVER_DB_URL` — JDBC URL (e.g., `jdbc:postgresql://postgres:5432/clipcascade`)
- `CC_SERVER_DB_DRIVER` — Driver class (e.g., `org.postgresql.Driver`)
- `CC_SERVER_DB_HIBERNATE_DIALECT` — Dialect (e.g., `org.hibernate.dialect.PostgreSQLDialect`)

### Optional

- `SPRING_PROFILES_ACTIVE` — Profile selection (`postgres` or `h2`, default: `postgres`)
- Other SkyClip settings (see main docker-compose.yml)

---

## Troubleshooting

### SkyClip fails to start: "CC_DB_PASSWORD must be set"

**Cause:** Environment variable not set

**Solution:**
```bash
export CC_DB_USER=clipcascade
export CC_DB_PASSWORD=your_password
docker-compose up
```

### PostgreSQL not ready when SkyClip starts

**Cause:** Health check timing issue

**Solution:** Wait for PostgreSQL to be ready:
```bash
docker-compose up postgres -d
sleep 10
docker-compose up
```

### Flyway migration fails

**Cause:** Schema version mismatch or corrupted migration history

**Solution:**
1. Check Flyway table: `SELECT * FROM flyway_schema_history;`
2. If corrupted, clean and restart: `docker-compose down && docker volume rm postgres-data && docker-compose up`

---

## Backup and Restore

### Backup PostgreSQL Data

```bash
docker exec clipcascade-postgres pg_dump -U clipcascade clipcascade > backup.sql
```

### Restore PostgreSQL Data

```bash
docker exec -i clipcascade-postgres psql -U clipcascade clipcascade < backup.sql
```

---

## Performance Tuning

### HikariCP Pool Settings (application-postgres.properties)

- `maximum-pool-size=10` — Max concurrent database connections
- `minimum-idle=2` — Min idle connections to keep open
- `connection-timeout=30000` — 30-second connection timeout
- `idle-timeout=600000` — 10-minute idle connection timeout
- `max-lifetime=1800000` — 30-minute max connection lifetime

Adjust based on your workload.

---

## Security Notes

1. **Passwords are REQUIRED** — No defaults. Docker Compose fails if not set.
2. **Use strong passwords** — For production, use a password manager or secrets management system.
3. **Do not hardcode credentials** — Use environment variables or Docker Compose secrets.
4. **Encrypt data in transit** — Consider using SSL/TLS for PostgreSQL connections (not covered here).

---

## Rollback to H2

If you need to switch back to H2:

```bash
docker-compose -f docker-compose-h2.yml up
```

This uses the same H2 database files from before (if they still exist).

---

## Next Steps

- Monitor logs: `docker logs -f clipcascade`
- Access the app: `http://localhost:8080/login`
- Test Task 4 (Rate Limiting) on PostgreSQL
- Verify backward compatibility with H2

---

For questions or issues, refer to the SkyClip GitHub repository.
