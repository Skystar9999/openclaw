# SMS Gateway API v2.0 - Documentație Completă

## 🚀 Overview

SMS Gateway complet integrat în OpenClaw Android cu suport pentru:
- ✅ Trimitere SMS via HTTP API
- ✅ Citire inbox SMS
- ✅ Notificări WebSocket în timp real
- ✅ Dashboard UI nativ în aplicație
- ✅ Management SMS (mark read, delete)

## 📡 Endpoints HTTP

### Base URL
```
http://<tablet-ip>:8888
```

### 1. Status Gateway
```http
GET /sms/status
```

**Response:**
```json
{
  "status": "running",
  "port": 8888,
  "webSocketPort": 8889,
  "smsEnabled": true,
  "hasPermission": true,
  "hasReadPermission": true,
  "timestamp": 1738525200000
}
```

### 2. Listă Inbox
```http
GET /sms/inbox?limit=20&unread=false&from=+4077
```

**Query Parameters:**
- `limit` (int, optional) - Număr maxim mesaje (default: 50)
- `unread` (bool, optional) - Doar necitite (default: false)
- `from` (string, optional) - Filtru după număr expeditor

**Response:**
```json
{
  "messages": [
    {
      "id": "123",
      "threadId": "456",
      "address": "+40773746621",
      "body": "Salut!",
      "date": 1738525200000,
      "dateFormatted": "2025-02-02 20:00:00",
      "read": false,
      "type": "inbox"
    }
  ],
  "totalCount": 150,
  "unreadCount": 3,
  "timestamp": 1738525200000
}
```

### 3. Citește SMS Specific
```http
GET /sms/inbox/{id}
```

**Response:**
```json
{
  "id": "123",
  "threadId": "456",
  "address": "+40773746621",
  "body": "Salut!",
  "date": 1738525200000,
  "dateFormatted": "2025-02-02 20:00:00",
  "read": false,
  "type": "inbox"
}
```

### 4. Trimite SMS
```http
POST /sms/send
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

### 5. Marchează ca Citit
```http
POST /sms/inbox/{id}/read
X-API-Key: your-api-key
```

**Response:**
```json
{
  "success": true,
  "id": "123"
}
```

### 6. Șterge SMS
```http
DELETE /sms/inbox/{id}
X-API-Key: your-api-key
```

**Response:**
```json
{
  "success": true,
  "id": "123",
  "deleted": true
}
```

## 🔌 WebSocket API

### Conectare
```javascript
const ws = new WebSocket('ws://<tablet-ip>:8889');

ws.onopen = () => {
  console.log('Conectat la SMS Gateway');
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Eveniment:', data);
};
```

### Evenimente

#### 1. SMS Primit
```json
{
  "type": "sms:received",
  "data": {
    "id": "123",
    "from": "+40773746621",
    "body": "Mesaj nou!",
    "timestamp": "1738525200000"
  },
  "timestamp": 1738525200000
}
```

#### 2. SMS Trimis
```json
{
  "type": "sms:sent",
  "data": {
    "to": "+40773746621",
    "body": "Mesaj trimis!",
    "success": "true",
    "timestamp": "1738525200000"
  },
  "timestamp": 1738525200000
}
```

#### 3. Status Gateway
```json
{
  "type": "sms:status",
  "data": {
    "connected": "true",
    "clients": "3"
  },
  "timestamp": 1738525200000
}
```

## 🎨 Dashboard UI

### Accesare
Dashboard-ul este disponibil în aplicația OpenClaw Android:
- Navigare în aplicație → SMS Dashboard
- Afișează inbox, status și butoane acțiuni

### Funcționalități
- 📥 Vizualizare mesaje primite (cu badge "Nou" pentru necitite)
- 📤 Trimitere SMS (dialog dedicat)
- ✓ Marcare mesaje ca citite
- 🗑️ Ștergere mesaje
- 🔄 Reîmprospătare inbox
- 📊 Status gateway în timp real

## 🔒 Securitate

### Autentificare
Toate endpoint-urile (except GET /sms/status) necesită header:
```
X-API-Key: your-api-key
```

### Configurare API Key
```bash
# La build
export SMS_GATEWAY_API_KEY="cheia-ta-secreta"
./gradlew :app:assembleDebug

# Sau în cod (development only)
val apiKey = "development-key-change-me"
```

### Rețea
- **HTTP**: Port 8888 (LAN only)
- **WebSocket**: Port 8889 (LAN only)
- **CORS**: Activat pentru acces web

## 📱 Permisiuni Android

Adăugate în `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

## 🧪 Exemple Utilizare

### Bash/cURL
```bash
# Status gateway
curl http://192.168.100.103:8888/sms/status

# Listă inbox (ultimele 10)
curl "http://192.168.100.103:8888/sms/inbox?limit=10" \
  -H "X-API-Key: dev-key-change-me"

# Doar necitite
curl "http://192.168.100.103:8888/sms/inbox?unread=true" \
  -H "X-API-Key: dev-key-change-me"

# Trimite SMS
curl -X POST http://192.168.100.103:8888/sms/send \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-me" \
  -d '{"to":"+40773746621","message":"Test 🦞"}'

# Marchează citit
curl -X POST http://192.168.100.103:8888/sms/inbox/123/read \
  -H "X-API-Key: dev-key-change-me"

# Șterge mesaj
curl -X DELETE http://192.168.100.103:8888/sms/inbox/123 \
  -H "X-API-Key: dev-key-change-me"
```

### Python
```python
import requests

BASE_URL = "http://192.168.100.103:8888"
API_KEY = "dev-key-change-me"
headers = {"X-API-Key": API_KEY}

# Trimite SMS
response = requests.post(
    f"{BASE_URL}/sms/send",
    headers={**headers, "Content-Type": "application/json"},
    json={"to": "+40773746621", "message": "Salut!"}
)
print(response.json())

# Citește inbox
inbox = requests.get(f"{BASE_URL}/sms/inbox", headers=headers).json()
for msg in inbox["messages"]:
    print(f"{msg['address']}: {msg['body']}")
```

### JavaScript WebSocket
```javascript
const ws = new WebSocket('ws://192.168.100.103:8889');

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  if (msg.type === 'sms:received') {
    console.log('SMS nou de la:', msg.data.from);
    console.log('Conținut:', msg.data.body);
    // Afișează notificare în UI
  }
};
```

## 📂 Structura Fișierelor

```
apps/android/app/src/main/java/ai/openclaw/android/
├── sms/
│   ├── SmsGatewayServer.kt      # HTTP API server
│   ├── SmsInboxReader.kt        # Inbox operations
│   └── SmsWebSocketServer.kt    # WebSocket notifications
├── ui/sms/
│   └── SmsDashboard.kt          # Jetpack Compose UI
└── NodeForegroundService.kt     # Integration
```

## 🔄 Sync cu Upstream

```bash
# Fetch upstream changes
git fetch upstream

# Rebase your changes
git rebase upstream/main

# Force push (if needed)
git push origin main --force-with-lease
```

## 📝 Changelog

### v2.0 (2026-02-02)
- ✨ SmsInboxReader - Citire inbox completă
- ✨ SmsWebSocketServer - Notificări real-time
- ✨ SmsDashboard UI - Interfață nativă
- ✨ Endpoint-uri management SMS
- ✨ Filtrare inbox (unread, from, limit)

### v1.0 (2026-02-02)
- ✨ SmsGatewayServer - Trimitere SMS via HTTP
- ✨ API Key authentication
- ✨ CORS enabled

---

*Dezvoltat pentru Adrian S. - Aghiuță Assistant 🦞*
