# KoPilot Deployment & Release Guide

## 1. Backend Deployment (Docker Compose + Nginx + TLS)

Deploy KoPilot in an isolated directory (e.g. `/opt/kopilot`) with its own Docker network (`kopilot_internal`), database volume (`kopilot_pgdata`), and domain (`kp.kkdes.co.ke`) so it does not interfere with other applications on the host.

### Automated Apache + Certbot + Docker Deployment (`kp.kkdes.co.ke`)

When deploying on a VPS that already runs Apache on ports 80/443, use the automated script [deploy_kp_kkdes.sh](file:///C:/Users/Metto/Desktop/Codes/KoPilot/deploy/scripts/deploy_kp_kkdes.sh) and Apache vhost [kp.kkdes.co.ke.conf](file:///C:/Users/Metto/Desktop/Codes/KoPilot/deploy/apache/kp.kkdes.co.ke.conf). It binds the FastAPI container to `127.0.0.1:8440`, pulls from Git, configures Apache + Certbot SSL, and restarts all services without touching other hosted sites:

```bash
export KOPILOT_GIT_REPO="https://github.com/Alee24/koPilot.git" && \
export CERTBOT_EMAIL="admin@kkdes.co.ke" && \
sudo -E bash /opt/kopilot/deploy/scripts/deploy_kp_kkdes.sh
```

### Initial Server Setup (Standalone Docker + Nginx)

```bash
cd /opt/kopilot/deploy && \
cp .env.example .env && \
# Edit .env with real POSTGRES_PASSWORD, JWT_SECRET_KEY, OPENAI_API_KEY, GOOGLE_MAPS_API_KEY
docker compose up -d postgres redis api && \
docker compose up -d nginx
```

### Standard Update Sequence

```bash
git pull origin main && \
cd deploy && \
docker compose build --no-cache api && \
docker compose up -d && \
docker image prune -f
```

### Verification & Health Checks

```bash
docker compose ps && \
curl -fsS https://kp.kkdes.co.ke/health
```

### Database Backup & Rollback

```bash
./scripts/backup_postgres.sh /var/backups/kopilot
```

## 2. Android Build & Release Signing

1. **Debug APK:**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```
   Output: `android/app/build/outputs/apk/debug/app-debug.apk`

2. **Release App Bundle (.aab):**
   Configure `keystore.properties` outside git (never commit `.jks` or passwords) and run:
   ```bash
   cd android && ./gradlew :app:bundleRelease
   ```
