# SMS Gateway API - OpenClaw Android Fork

## 🚀 Overview

Această fork adaugă un **SMS Gateway HTTP server** în aplicația OpenClaw Android, permițând trimiterea de SMS-uri prin SIM-ul tabletei/SM-T295 via API HTTP.

## 📱 Endpoints

### 1. Status Gateway
```http
GET http://<tablet-ip>:8888/sms/status
```

**Response:**
```json
{
  "status": "running",
  "port": 8888,
  "smsEnabled": true,
  "hasPermission": true,
  "timestamp": 1738525200000
}
```

### 2. Trimite SMS
```http
POST http://<tablet-ip>:8888/sms/send
Content-Type: application/json
X-API-Key: your-api-key

{
  "to": "+40773746621",
  "message": "Salut de la Aghiuță! 🦞"
}
```

**Response:**
```json
{
  "success": true,
  "messageId": "sms_1738525200000_1234",
  "timestamp": 1738525200000
}
```

## 🔧 Setup

### 1. Build APK
```bash
cd apps/android
./gradlew :app:assembleDebug
```

### 2. Configurează API Key (Opțional)
```bash
export SMS_GATEWAY_API_KEY="cheia-ta-secreta"
./gradlew :app:assembleDebug
```

### 3. Instalează pe tabletă
```bash
adb install app/build/outputs/apk/debug/openclaw-2026.2.1-debug.apk
```

### 4. Pornește OpenClaw Node
- Deschide aplicația
- Conectează-te la Gateway (Mac Mini)
- SMS Gateway pornește automat pe port 8888

### 5. Verifică funcționarea
```bash
curl http://192.168.100.103:8888/sms/status
```

## 📋 Permisiuni necesare

Aplicația necesită:
- `SEND_SMS` - pentru trimitere SMS
- `READ_PHONE_STATE` - pentru verificare SIM
- `INTERNET` - pentru HTTP server (deja existent)

## 🔒 Securitate

- **API Key** în header `X-API-Key` pentru autentificare
- CORS activat pentru acces web
- Port 8888 deschis doar în LAN (nu expune la internet!)

## 🧪 Testare

```bash
# Test status
curl http://192.168.100.103:8888/sms/status

# Test trimite SMS (înlocuiește cu număr real)
curl -X POST http://192.168.100.103:8888/sms/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-me" \
  -d '{"to":"+40773746621","message":"Test SMS Gateway 🦞"}'
```

## 📂 Fișiere modificate/adăugate

```
apps/android/
├── app/src/main/java/ai/openclaw/android/sms/
│   └── SmsGatewayServer.kt          # NOU - HTTP server SMS
├── app/src/main/java/ai/openclaw/android/
│   └── NodeForegroundService.kt     # MODIFICAT - integrare SMS Gateway
└── app/build.gradle.kts             # MODIFICAT - API Key build config
```

## 🔄 Sync cu upstream

```bash
git fetch upstream
git rebase upstream/main
# Rezolvă conflicte dacă apar
git push origin main --force-with-lease
```

## 📝 TODO / Feature-uri viitoare

- [ ] Endpoint `/sms/inbox` - citire SMS primite
- [ ] WebSocket pentru notificări SMS în timp real
- [ ] ADB bridge nativ în app
- [ ] Dashboard UI pentru management SMS

---
*Fork creat pentru Adrian S. - Aghiuță Assistant 🦞*