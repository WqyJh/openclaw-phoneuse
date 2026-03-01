---
name: phoneuse
description: |
  Control an Android phone remotely via OpenClaw PhoneUse node. Use when:
  - User asks to interact with their phone (open app, tap, type, swipe, screenshot)
  - User wants to automate a phone task (order food, fill forms, send messages)
  - User asks to see what's on their phone screen
  - User needs files from their phone or wants to transfer files
  - User wants to unlock/lock their phone
  NOT for: tasks that don't involve an Android device, or when no PhoneUse node is connected.
---

# PhoneUse: Android Remote Control

Control a connected Android phone via the `nodes` tool with `action: invoke`.

## Context Cost Management

**Screenshots and recordings are expensive in context.** Follow these rules:

### Rule 1: Use a sub-agent for PhoneUse tasks

Spawn an isolated sub-agent with `sessions_spawn(mode: "run")` for phone operations. This keeps screenshots/UI trees out of the main conversation context.

```
Main agent receives: "帮我打开B站看看热门视频"
→ sessions_spawn({task: "Use phoneUse on node <nodeId> to open Bilibili and report the top 3 trending videos", mode: "run"})
→ Sub-agent executes all phoneUse commands internally (screenshots stay in sub-agent context)
→ Sub-agent returns text result: "已打开B站，热门Top3: 1. xxx 2. xxx 3. xxx"
→ Sub-agent exits immediately after completing the task
```

The sub-agent MUST exit after completing its task — do not keep it running.

### Rule 2: Minimize screenshots

- **Prefer `getUITree`** (~5KB text) over `screenshot` (~100KB image) when you need to find elements
- Only screenshot to **verify visual state** or when UI tree alone is insufficient
- Use lower quality when possible: `{maxWidth: 480, quality: 30}` → ~20KB
- **Never reference raw base64** in your responses — describe what you see in words

### Rule 3: Keep files on device, not in context

Screen recordings and large files should stay on the phone — don't load them into context. Use `file.read` only when you need to transfer to the server. The files remain on the device for later access.

## Before You Start

1. Check node is connected: `nodes describe` — look for a node with `phoneUse` in caps
2. Note the `nodeId` — you'll need it for every invoke call

## Core Workflow: See → Think → Act

Every phone interaction follows this loop:

```
1. Screenshot  →  see the current screen
2. Analyze     →  understand what's visible, find targets
3. Act         →  tap/swipe/type based on what you see
4. Verify      →  screenshot again to confirm the action worked
```

**Never act blindly.** Always screenshot first to see the current state.

## Invoking Commands

All commands use the `nodes` tool:

```json
{"action": "invoke", "node": "<nodeId>", "invokeCommand": "<command>", "invokeParamsJson": "<json>"}
```

## Essential Commands

### See the screen (preferred — file-based, no context bloat)

Use the `nodes` tool's built-in `camera_snap` action (NOT invoke):

```json
{"action": "camera_snap", "node": "<nodeId>"}
```

This saves the screenshot to a file on the server and returns a local path. The image does NOT stay in context as raw base64.

**Only use `phoneUse.screenshot` via invoke if you need custom parameters:**
```json
{"action": "invoke", "node": "<nodeId>", "invokeCommand": "phoneUse.screenshot", "invokeParamsJson": "{\"maxWidth\": 480, \"quality\": 30}"}
```

### Get interactive elements

```json
{"invokeCommand": "phoneUse.getUITree", "invokeParamsJson": "{\"interactiveOnly\": true}"}
```
Returns JSON with all clickable/editable elements, their text, bounds, and center coordinates. Use this to find tap targets precisely instead of guessing coordinates.

### Tap

```json
{"invokeCommand": "phoneUse.tap", "invokeParamsJson": "{\"x\": 540, \"y\": 1200}"}
```

### Type text

```json
{"invokeCommand": "phoneUse.setText", "invokeParamsJson": "{\"text\": \"Hello world\"}"}
```
Sets text in the currently focused input field. Tap the field first if needed.

### Find and click by text

```json
{"invokeCommand": "phoneUse.findAndClick", "invokeParamsJson": "{\"text\": \"Submit\"}"}
```
Finds a visible element containing the text and clicks it. Preferred over coordinate tapping when text is known.

### Swipe / scroll

```json
{"invokeCommand": "phoneUse.swipe", "invokeParamsJson": "{\"x1\":540,\"y1\":1500,\"x2\":540,\"y2\":500,\"duration\":300}"}
```
Or use shortcuts: `phoneUse.scrollDown`, `phoneUse.scrollUp`.

### Launch app

```json
{"invokeCommand": "phoneUse.launch", "invokeParamsJson": "{\"app\": \"Settings\"}"}
```
Launches by display name or package name.

### Navigate

- `phoneUse.back` — back button
- `phoneUse.home` — home button

## Screen Management

### If the screen is off

```json
{"invokeCommand": "phoneUse.wakeScreen", "invokeParamsJson": "{}"}
```

### If the phone is locked

```json
{"invokeCommand": "phoneUse.unlock", "invokeParamsJson": "{\"pin\": \"1234\"}"}
```
Omit `pin` for swipe/no-lock screens. The command wakes the screen, dismisses the lock, and enters the PIN automatically.

### Check screen state

```json
{"invokeCommand": "phoneUse.isScreenOn", "invokeParamsJson": "{}"}
```

## File Operations

Read, write, and browse files on the phone. Paths are typically under `/sdcard/`.

```json
{"invokeCommand": "file.list", "invokeParamsJson": "{\"path\": \"/sdcard/DCIM\"}"}
{"invokeCommand": "file.read", "invokeParamsJson": "{\"path\": \"/sdcard/DCIM/photo.jpg\", \"offset\": 0, \"size\": 2097152}"}
{"invokeCommand": "file.write", "invokeParamsJson": "{\"path\": \"/sdcard/Download/note.txt\", \"base64\": \"SGVsbG8=\"}"}
```

For large files, use chunked transfer. See [references/file-transfer.md](references/file-transfer.md).

## Common Patterns

### Pattern: Open app and navigate to a screen

```
1. phoneUse.launch {app: "WeChat"}
2. phoneUse.waitForElement {text: "Chats"}  — wait for app to load
3. phoneUse.screenshot {}  — see current state
4. phoneUse.findAndClick {text: "Contacts"}  — navigate
5. phoneUse.screenshot {}  — verify
```

### Pattern: Fill a form

```
1. phoneUse.screenshot {}  — see the form
2. phoneUse.getUITree {interactiveOnly: true}  — find input fields
3. phoneUse.tap {x, y}  — tap first field
4. phoneUse.setText {text: "value"}  — enter text
5. phoneUse.tap {x, y}  — tap next field
6. phoneUse.setText {text: "value"}
7. phoneUse.findAndClick {text: "Submit"}
8. phoneUse.screenshot {}  — verify submission
```

### Pattern: Scroll to find content

```
1. phoneUse.screenshot {}
2. If target not visible: phoneUse.scrollDown {}
3. phoneUse.screenshot {}  — check again
4. Repeat until found or bottom reached
```

## App & System Info

### List installed apps

```json
{"invokeCommand": "phoneUse.listApps", "invokeParamsJson": "{}"}
```
Returns app names and package names. Use package name with `phoneUse.launch`.

### Get current foreground app

```json
{"invokeCommand": "phoneUse.getForegroundApp", "invokeParamsJson": "{}"}
```

### Device status (battery, WiFi, storage)

```json
{"invokeCommand": "phoneUse.getDeviceStatus", "invokeParamsJson": "{}"}
```

### Clipboard

```json
{"invokeCommand": "phoneUse.clipboard", "invokeParamsJson": "{}"}
{"invokeCommand": "phoneUse.clipboard", "invokeParamsJson": "{\"set\": \"copied text\"}"}
```

### Open URL or deep link

```json
{"invokeCommand": "phoneUse.openUrl", "invokeParamsJson": "{\"url\": \"https://bilibili.com\"}"}
```

### Open system settings or send Intent

```json
{"invokeCommand": "phoneUse.startActivity", "invokeParamsJson": "{\"action\": \"android.settings.WIFI_SETTINGS\"}"}
```

For Intent patterns and deep links, see [references/intents.md](references/intents.md).

### Discover app capabilities

```json
{"invokeCommand": "phoneUse.queryIntents", "invokeParamsJson": "{\"package\": \"tv.danmaku.bili\"}"}
```

## Tips

- **Prefer `findAndClick` over coordinate tapping** when button text is known — it's more reliable across screen sizes.
- **Use `getUITree` with `interactiveOnly: true`** to discover exact element coordinates instead of guessing.
- **Always verify** actions with a screenshot after performing them.
- **Wait for loading**: Use `phoneUse.waitForElement` after launching apps or navigating.
- **Screen coordinates** are in pixels. Use `phoneUse.getScreenInfo` to get screen dimensions if needed.
- Commands that need the screen (tap, screenshot) **auto-wake** the display — no need to call `wakeScreen` first in most cases.
