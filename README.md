# OpenClaw PhoneUse Agent

<p align="center">
  <b>Let AI control your Android phone via OpenClaw Gateway</b>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#commands">Commands</a> •
  <a href="#gateway-setup">Gateway Setup</a> •
  <a href="#building">Building</a>
</p>

---

An Android app that connects to an [OpenClaw](https://github.com/openclaw/openclaw) Gateway as a **node**, enabling AI agents to remotely control your phone — tap, swipe, type, take screenshots, launch apps, and more.

## Features

- 📱 **Full UI Automation** — Tap, swipe, scroll, type text, find & click elements by text
- 📸 **Screenshot Capture** — Compressed JPEG via Accessibility API (720p, ~100KB)
- 🌐 **Tailscale Support** — Connect securely over Tailscale Serve (WSS/HTTPS)
- 🔐 **Ed25519 Auth** — Cryptographic device identity with Gateway challenge-response
- 🔄 **Auto Reconnect** — Exponential backoff (2s → 60s max), reset on manual connect
- 📍 **Device Sensors** — GPS location, notification listing
- 🔔 **Push Notifications** — Remote notification delivery to device
- 🐚 **Shell Execution** — Run commands on the device
- 📋 **UI Tree Inspection** — Get accessibility tree as JSON for element discovery
- 📤 **Log Export** — Full debug log export (UI log + logcat + device info)

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

**Protocol Flow:**
1. Gateway sends `event: node.invoke.request` with `{id, command, paramsJSON}`
2. App executes the command via Accessibility Service
3. App sends `req: node.invoke.result` with `{id, nodeId, ok, payloadJSON}`

## Quick Start

### Prerequisites

- Android 8.0+ (API 26+)
- An [OpenClaw](https://github.com/openclaw/openclaw) Gateway running on your network
- Network connectivity between phone and Gateway (LAN, Tailscale, etc.)

### Install

Download the latest APK from [Releases](https://github.com/WqyJh/openclaw-phoneuse/releases), or [build from source](#building).

### Setup

1. **Install & open** the app
2. **Enable Accessibility Service**: Tap the button → enable "OpenClaw PhoneUse Agent" in system settings
3. **Allow Notification Permission**: Automatically prompted on first launch
4. **Enter Gateway URL**: 
   - LAN: `192.168.1.100:18789`
   - Tailscale Serve: `https://mydevice.ts.net`
5. **Enter Gateway Token** (if configured)
6. **Tap Connect**
7. **Approve device** on Gateway (Dashboard → Nodes, or `openclaw device approve`)

## Commands

### Standard OpenClaw Node Commands

These are recognized by the Gateway's built-in `nodes` tool:

| Command | Parameters | Min API | Description |
|---------|-----------|---------|-------------|
| `camera.snap` | `{maxWidth?, quality?}` | **30 (11)** | Screenshot (JPEG, compressed) |
| `camera.list` | `{}` | 26 (8.0) | List available capture sources |
| `camera.clip` | `{durationMs?, fps?, maxWidth?}` | **30 (11)** | Short video clip (MJPEG frames) |
| `screen.record` | `{durationMs?, fps?, maxWidth?}` | **30 (11)** | Screen recording (MJPEG frames) |
| `location.get` | `{}` | 26 (8.0) | GPS/network location |
| `notifications.list` | `{}` | 26 (8.0) | List active notifications |
| `system.run` | `{command}` | 26 (8.0) | Execute shell command |
| `system.notify` | `{title, body}` | 26 (8.0) | Show Android notification |

### PhoneUse Commands

These provide full UI automation via Accessibility Service:

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

## Gateway Setup

### 1. Allow PhoneUse Commands

Add to your `~/.openclaw/openclaw.json`:

```json5
{
  gateway: {
    nodes: {
      allowCommands: [
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
        "camera.snap", "camera.clip", "screen.record",
        "system.run", "system.notify"
      ]
    }
  }
}
```

### 2. Tailscale Serve (Recommended for Remote Access)

```json5
{
  gateway: {
    bind: "loopback",
    tailscale: { mode: "serve" }
  }
}
```

This keeps the Gateway on `127.0.0.1` while Tailscale provides HTTPS access at `https://<device>.ts.net`.

### 3. Device Pairing

On first connect, the device needs approval:

```bash
# Via CLI
openclaw device approve

# Or via Gateway Dashboard → Nodes page
```

## Building

### Prerequisites

- JDK 17+
- Android SDK (API 34)
- Gradle 8.7+

### Build Debug APK

```bash
# Clone
git clone https://github.com/WqyJh/openclaw-phoneuse.git
cd openclaw-phoneuse

# Build
./gradlew assembleDebug

# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Build Release APK

```bash
./gradlew assembleRelease
```

> Note: Release builds require a signing keystore. See [Android signing docs](https://developer.android.com/studio/publish/app-signing).

## Automation Example

Using the OpenClaw `nodes` tool from an AI agent:

```javascript
// Take a screenshot
nodes invoke --node <deviceId> --command phoneUse.screenshot

// Tap a button
nodes invoke --node <deviceId> --command phoneUse.tap --params '{"x": 540, "y": 1200}'

// Type text
nodes invoke --node <deviceId> --command phoneUse.setText --params '{"text": "Hello world"}'

// Get UI tree for element discovery
nodes invoke --node <deviceId> --command phoneUse.getUITree --params '{"interactiveOnly": true}'

// Launch an app
nodes invoke --node <deviceId> --command phoneUse.launch --params '{"app": "Settings"}'
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `device identity mismatch` | Wrong key algorithm | Use V18+ (Ed25519) |
| `device signature invalid` | Token/payload mismatch | Reset Pairing in app |
| `command not allowlisted` | Gateway blocks command | Add to `gateway.nodes.allowCommands` |
| `command not declared by node` | Stale node data | Disconnect & reconnect |
| `handshake timeout` | TLS/network issue | Check Tailscale Serve status |
| Screenshot returns error | Accessibility not enabled | Enable in system settings |
| Frequent disconnects | Tailscale Serve instability | Try `bind: "tailnet"` mode |

## Security

- **Ed25519 device identity**: Each device generates a unique keypair. Device ID = SHA-256 of public key.
- **Challenge-response auth**: Gateway sends nonce → device signs with Ed25519 → Gateway verifies.
- **Scoped tokens**: Gateway issues device-scoped tokens after pairing approval.
- **Command allowlist**: Gateway enforces per-platform command allowlists.
- **Tailscale encryption**: All traffic encrypted via WireGuard when using Tailscale.

## License

[MIT](LICENSE)

## Credits

Built for [OpenClaw](https://github.com/openclaw/openclaw) — the open-source AI assistant platform.
