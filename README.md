# OpenClaw PhoneUse Agent

<p align="center">
  <b>Let AI control your Android phone via OpenClaw Gateway</b>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#commands">Commands</a> •
  <a href="#connection-methods">Connection</a> •
  <a href="#gateway-setup">Gateway Setup</a> •
  <a href="#building">Building</a>
</p>

---

An Android app that connects to an [OpenClaw](https://github.com/openclaw/openclaw) Gateway as a **node**, enabling AI agents to remotely control your phone — tap, swipe, type, take screenshots, record screen, read/write files, and more.

## Features

- 📱 **Full UI Automation** — Tap, swipe, scroll, type text, find & click elements
- 📸 **Screenshot** — Compressed JPEG via Accessibility API (720p, ~100KB)
- 🎬 **Screen Recording** — Real H.264 MP4 via MediaProjection + MediaRecorder
- 📁 **File Operations** — Read/write/list any file on the device (`/sdcard/` etc.)
- 🔓 **Screen Unlock** — Dismiss lock screen, auto-enter PIN
- 🌐 **Tailscale / Caddy / LAN** — Multiple secure connection methods
- 🔐 **Ed25519 Auth** — Cryptographic device identity with challenge-response
- 🔄 **Auto Reconnect** — Exponential backoff, auto-connect on launch
- 💤 **Background Keep-Alive** — Works with screen off, wakes on command
- 📍 **Device Sensors** — GPS location, notification listing
- 🔔 **Push Notifications** — Remote notification delivery
- 🐚 **Shell Execution** — Run commands on the device
- 📋 **UI Tree Inspection** — Accessibility tree as JSON for element discovery
- 📤 **Log Export** — Business log (metrics) + Debug log (logcat)

## Architecture

```
┌─────────────┐     WebSocket (WSS)     ┌───────────────┐
│   Android    │◄───────────────────────►│   OpenClaw    │
│   PhoneUse   │  Ed25519 signed auth    │   Gateway     │
│   Agent      │  node.invoke.request →  │               │
│              │  ← node.invoke.result   │               │
└──────┬───────┘                         └───────┬───────┘
       │                                         │
       │ Accessibility                           │ AI Agent
       │ Service                                 │ (Claude, GPT, etc.)
       ▼                                         ▼
┌──────────────┐                         ┌───────────────┐
│ Android UI   │                         │  "tap (500,   │
│ (any app)    │                         │   1000)"      │
└──────────────┘                         └───────────────┘
```

## Quick Start

### Prerequisites

- Android 8.0+ (API 26+)
- An [OpenClaw](https://github.com/openclaw/openclaw) Gateway running
- Network connectivity between phone and Gateway

### Install

Download the latest APK from [Releases](https://github.com/WqyJh/openclaw-phoneuse/releases), or [build from source](#building).

### Setup

1. **Install & open** the app
2. **Grant permissions** (prompted automatically):
   - Accessibility Service → enable "OpenClaw PhoneUse Agent"
   - Notification permission
   - Battery optimization exemption
   - All files access (for file operations)
   - Screen Capture (optional, for video recording)
3. **Enter Gateway URL** (see [Connection Methods](#connection-methods))
4. **Enter Gateway Token** (if configured)
5. **Tap Connect**
6. **Approve device** on Gateway (Dashboard → Nodes, or `openclaw device approve`)

After first setup, the app auto-connects on launch.

## Commands

### Standard OpenClaw Node Commands

| Command | Parameters | Min API | Description |
|---------|-----------|---------|-------------|
| `camera.snap` | `{maxWidth?, quality?}` | **30 (11)** | Screenshot (JPEG, compressed) |
| `camera.list` | `{}` | 26 (8.0) | List available capture sources |
| `camera.clip` | `{durationMs?, fps?, maxWidth?}` | **30 (11)** | Short video clip (MP4, fallback to screenshot) |
| `screen.record` | `{durationMs?, fps?, bitrate?, maxWidth?}` | **30 (11)** | H.264 MP4 screen recording |
| `location.get` | `{}` | 26 (8.0) | GPS/network location |
| `notifications.list` | `{}` | 26 (8.0) | List active notifications |
| `system.run` | `{command}` | 26 (8.0) | Execute shell command |
| `system.notify` | `{title, body}` | 26 (8.0) | Show Android notification |

### PhoneUse Commands

| Command | Parameters | Min API | Description |
|---------|-----------|---------|-------------|
| `phoneUse.tap` | `{x, y}` | 26 (8.0) | Tap at coordinates |
| `phoneUse.doubleTap` | `{x, y}` | 26 (8.0) | Double tap |
| `phoneUse.longTap` | `{x, y, duration?}` | 26 (8.0) | Long press (default 1000ms) |
| `phoneUse.swipe` | `{x1, y1, x2, y2, duration?}` | 26 (8.0) | Swipe gesture |
| `phoneUse.pinch` | `{x, y, zoomIn, span?, duration?}` | 26 (8.0) | Pinch zoom |
| `phoneUse.setText` | `{text, fieldIndex?}` | 26 (8.0) | Set text in input field |
| `phoneUse.typeText` | `{text}` | 26 (8.0) | Paste text via clipboard |
| `phoneUse.findAndClick` | `{text?, resourceId?, timeout?}` | 26 (8.0) | Find element and click |
| `phoneUse.screenshot` | `{maxWidth?, quality?}` | **30 (11)** | Compressed screenshot |
| `phoneUse.getUITree` | `{interactiveOnly?}` | 26 (8.0) | Accessibility tree as JSON |
| `phoneUse.getScreenInfo` | `{}` | 26 (8.0) | Screen dimensions & device info |
| `phoneUse.launch` | `{app?, package?}` | 26 (8.0) | Launch app by name or package |
| `phoneUse.back` | `{}` | 26 (8.0) | Back button |
| `phoneUse.home` | `{}` | 26 (8.0) | Home button |
| `phoneUse.recents` | `{}` | 26 (8.0) | Recent apps |
| `phoneUse.scrollDown/Up` | `{}` | 26 (8.0) | Scroll vertically |
| `phoneUse.scrollLeft/Right` | `{}` | 26 (8.0) | Scroll horizontally |
| `phoneUse.openNotifications` | `{}` | 26 (8.0) | Pull down notification shade |
| `phoneUse.openQuickSettings` | `{}` | 26 (8.0) | Pull down quick settings |
| `phoneUse.waitForElement` | `{text?, resourceId?, timeout?}` | 26 (8.0) | Wait for element to appear |
| `phoneUse.inputKey` | `{keyCode}` | 26 (8.0) | Send key event (66=Enter, 67=Del) |
| `phoneUse.unlock` | `{pin?}` | **28 (9.0)** | Unlock screen (optional PIN) |
| `phoneUse.lockScreen` | `{}` | **28 (9.0)** | Lock the screen |
| `phoneUse.wakeScreen` | `{}` | 26 (8.0) | Wake screen temporarily (10s) |
| `phoneUse.isScreenOn` | `{}` | 26 (8.0) | Check if screen is on |
| `phoneUse.listApps` | `{includeSystem?}` | 26 (8.0) | List installed apps with package names |
| `phoneUse.getForegroundApp` | `{}` | 26 (8.0) | Get current foreground package |
| `phoneUse.openUrl` | `{url}` | 26 (8.0) | Open URL in browser or app |
| `phoneUse.startActivity` | `{action?, uri?, package?, class?}` | 26 (8.0) | Launch any Android Intent |
| `phoneUse.clipboard` | `{set?}` | 26 (8.0) | Read or write clipboard |
| `phoneUse.getDeviceStatus` | `{}` | 26 (8.0) | Battery, WiFi, storage, screen state |
| `phoneUse.openAllApps` | `{}` | **31 (12)** | Open app drawer |
| `phoneUse.queryIntents` | `{action?, package?}` | 26 (8.0) | Discover app activities & intent handlers |

### File Operations

General-purpose file access. Supports any path accessible to the app (with `MANAGE_EXTERNAL_STORAGE` permission, this includes all of `/sdcard/`).

| Command | Parameters | Description |
|---------|-----------|-------------|
| `file.read` | `{path, offset?, size?}` | Read file in chunks (default 2MB) |
| `file.write` | `{path, base64, append?}` | Write or append to file |
| `file.info` | `{path}` | File metadata (size, permissions, modified) |
| `file.list` | `{path?}` | Directory listing (default `/sdcard`) |
| `file.delete` | `{path}` | Delete file |

**Chunked transfer example** (for large files):
```
1. file.info {path: "/sdcard/DCIM/video.mp4"}
   → {size: 50000000, ...}

2. file.read {path: "/sdcard/DCIM/video.mp4", offset: 0, size: 2097152}
   → {base64: "...", offset: 0, size: 2097152, total: 50000000, done: false}

3. file.read {path: "...", offset: 2097152, size: 2097152}
   → {base64: "...", done: false}

   ... repeat until done: true
```

### Screen Recording

Real H.264 MP4 recording via MediaProjection + MediaRecorder.

**Requires:** Screen Capture permission (tap "Enable Screen Capture" in app).

| Parameter | Default | Description |
|-----------|---------|-------------|
| `durationMs` | 5000 | Recording duration in ms |
| `fps` | 15 | Frames per second |
| `bitrate` | 2000000 | H.264 bitrate (bps) |
| `maxWidth` | 720 | Max width (height scales) |
| `inlineThreshold` | 10485760 | Max bytes for inline base64 (10MB) |

**Small recordings** (≤10MB) are returned inline as base64.
**Large recordings** return a `recordingId` — use `file.read` to download in chunks.

```
screen.record {durationMs: 10000, fps: 15, bitrate: 2000000}
→ {format: "mp4", base64: "...", durationMs: 10000, sizeBytes: 2500000}
```

## Connection Methods

### 1. LAN Direct Connect

Phone and Gateway on the same local network.

```
Phone → ws://192.168.1.100:18789 → Gateway
```

**Gateway config:**
```json5
{ gateway: { bind: "lan" } }
```

**URL:** `192.168.1.100:18789`

**Pros:** Lowest latency, no extra setup
**Cons:** Same network only, no encryption

### 2. Tailscale (Recommended for Remote Access)

#### 2a. Tailscale Serve (HTTPS reverse proxy)

```
Phone → wss://mydevice.ts.net → Tailscale Serve → 127.0.0.1:18789
```

**Gateway config:**
```json5
{
  gateway: {
    bind: "loopback",
    tailscale: { mode: "serve" }
  }
}
```

**URL:** `https://mydevice.ts.net`

**Pros:** HTTPS encryption, Tailscale identity auth, cross-network
**Cons:** Occasional TLS instability

#### 2b. Tailscale Direct Bind

```
Phone → ws://100.x.x.x:18789 → Gateway (Tailscale IP)
```

**Gateway config:**
```json5
{
  gateway: {
    bind: "tailnet",
    auth: { mode: "token" }
  }
}
```

**URL:** `100.x.x.x:18789`

**Pros:** More stable than Serve, WireGuard encrypted, cross-network
**Cons:** localhost unavailable on Gateway host

### 3. Caddy Reverse Proxy (Public Internet)

Expose **only the WebSocket endpoint**, hiding Control UI and HTTP APIs.

```
Phone → wss://example.com → Caddy → 127.0.0.1:18789
```

**Gateway config:**
```json5
{
  gateway: {
    bind: "loopback",
    auth: { mode: "token" }
  }
}
```

**Caddyfile:**
```
example.com {
    # Only allow WebSocket upgrade traffic through.
    @ws {
        header Connection *Upgrade*
        header Upgrade websocket
    }

    handle @ws {
        reverse_proxy 127.0.0.1:18789 {
            header_up Host {host}
            header_up X-Forwarded-Proto {scheme}
            header_up X-Forwarded-For {remote_host}
        }
    }

    # Hide everything else (Control UI, HTTP APIs, etc.)
    handle {
        respond 404
    }
}
```

**URL:** `https://example.com`

**Pros:** Works from anywhere, HTTPS, only WebSocket exposed
**Cons:** Requires domain + Caddy setup

> ⚠️ **Security:** Always use a strong Gateway token when exposing over public internet.

### Comparison

| Method | Encryption | Cross-Network | Latency | Setup |
|--------|-----------|---------------|---------|-------|
| LAN Direct | ❌ | ❌ | Lowest | Easy |
| Tailscale Serve | ✅ HTTPS | ✅ | Low | Medium |
| Tailscale Direct | ✅ WireGuard | ✅ | Lowest | Medium |
| Caddy Proxy | ✅ HTTPS | ✅ | Medium | Complex |

## Gateway Setup

### 1. Allow PhoneUse Commands

Add to `~/.openclaw/openclaw.json`:

```json5
{
  gateway: {
    nodes: {
      allowCommands: [
        // UI automation
        "phoneUse.tap", "phoneUse.doubleTap", "phoneUse.longTap",
        "phoneUse.swipe", "phoneUse.pinch",
        "phoneUse.setText", "phoneUse.typeText", "phoneUse.findAndClick",
        "phoneUse.screenshot", "phoneUse.getUITree", "phoneUse.getScreenInfo",
        "phoneUse.launch", "phoneUse.back", "phoneUse.home", "phoneUse.recents",
        "phoneUse.openNotifications", "phoneUse.openQuickSettings",
        "phoneUse.scrollDown", "phoneUse.scrollUp",
        "phoneUse.scrollLeft", "phoneUse.scrollRight",
        "phoneUse.waitForElement", "phoneUse.inputKey",
        "phoneUse.requestScreenCapture",
        // Screen lock
        "phoneUse.unlock", "phoneUse.lockScreen",
        "phoneUse.wakeScreen", "phoneUse.isScreenOn",
        // Apps & system info
        "phoneUse.listApps", "phoneUse.getForegroundApp",
        "phoneUse.openUrl", "phoneUse.startActivity",
        "phoneUse.clipboard", "phoneUse.getDeviceStatus",
        "phoneUse.openAllApps", "phoneUse.queryIntents",
        // Media capture
        "camera.snap", "camera.clip", "screen.record",
        // System
        "system.run", "system.notify",
        // File operations
        "file.read", "file.write", "file.info", "file.list", "file.delete"
      ]
    }
  }
}
```

### 2. Device Pairing

```bash
# Via CLI
openclaw device approve

# Or via Gateway Dashboard → Nodes page
```

## Background & Keep-Alive

The app is designed to work with the screen off:

| Component | Screen Off | Locked |
|-----------|-----------|--------|
| WebSocket | ✅ CPU + WiFi wake locks | ✅ |
| Accessibility | ✅ System service | ✅ |
| Gestures (tap/swipe) | ✅ but blind | ✅ auto-wakes screen |
| Screenshots | ✅ auto-wakes screen | ✅ |
| File operations | ✅ | ✅ |

**How it works:**
- Phone sleeps normally (no screen burn-in)
- CPU + WiFi wake locks keep connection alive
- When a command arrives → screen wakes for 10s → execute → sleep
- Battery optimization exemption prevents Doze from killing the service

**MIUI/HyperOS users:** Also enable "Autostart" and "No restrictions" in battery settings.

## Building

### Prerequisites

- JDK 17+
- Android SDK (API 34)
- Gradle 8.7+

### Build

```bash
git clone https://github.com/WqyJh/openclaw-phoneuse.git
cd openclaw-phoneuse
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `device identity mismatch` | Reset Pairing in app |
| `device signature invalid` | Reset Pairing, check clock sync |
| `command not allowlisted` | Add to `gateway.nodes.allowCommands` |
| `command not declared by node` | Disconnect & reconnect |
| Screenshot returns error | Enable Accessibility Service |
| Frequent disconnects | Try Tailscale Direct Bind, check battery optimization |
| `MediaProjection` error | Tap "Enable Screen Capture" in app (for recording only) |
| Storage permission denied | Grant "All files access" in system settings |
| Unlock fails | Use swipe lock or provide correct PIN |

## Security

- **Ed25519 device identity** — unique keypair per device, SHA-256 fingerprint as ID
- **Challenge-response auth** — Gateway nonce signed with Ed25519
- **Command allowlist** — Gateway enforces per-platform allowlists
- **Scoped device tokens** — issued after pairing approval
- **Tailscale / Caddy encryption** — WireGuard or HTTPS for transport

## License

[MIT](LICENSE)

## Credits

Built for [OpenClaw](https://github.com/openclaw/openclaw) — the open-source AI assistant platform.
