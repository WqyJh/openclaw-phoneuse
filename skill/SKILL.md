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

### Rule 3: Use `nodes` tool actions for media (not invoke)

For screenshots and recordings, use `nodes` tool **actions** (camera_snap, screen_record) instead of invoke. The Gateway automatically saves media to server-side files, so only a file path enters your context — not the raw image/video data.

| Method | Context cost | Use when |
|--------|-------------|----------|
| `nodes camera_snap` (action) | ✅ File path only | Camera photo (front/back) |
| `nodes screen_record` (action) | ✅ File path only | Default for recordings |
| `nodes invoke phoneUse.screenshot` | ⚠️ base64 in payload | Custom params needed |

## Before You Start

1. Check node is connected: `nodes describe` — look for a node with `phoneUse` in caps
2. Note the `nodeId` — you'll need it for every invoke call
3. Ensure Gateway allows phoneUse commands. Run once:

```bash
openclaw config set gateway.nodes.allowCommands '["phoneUse.tap","phoneUse.doubleTap","phoneUse.longTap","phoneUse.swipe","phoneUse.pinch","phoneUse.setText","phoneUse.typeText","phoneUse.findAndClick","phoneUse.screenshot","phoneUse.getUITree","phoneUse.getScreenInfo","phoneUse.launch","phoneUse.back","phoneUse.home","phoneUse.recents","phoneUse.openNotifications","phoneUse.openQuickSettings","phoneUse.scrollDown","phoneUse.scrollUp","phoneUse.scrollLeft","phoneUse.scrollRight","phoneUse.waitForElement","phoneUse.inputKey","phoneUse.requestScreenCapture","phoneUse.unlock","phoneUse.lockScreen","phoneUse.wakeScreen","phoneUse.isScreenOn","phoneUse.listApps","phoneUse.getForegroundApp","phoneUse.openUrl","phoneUse.startActivity","phoneUse.clipboard","phoneUse.getDeviceStatus","phoneUse.openAllApps","phoneUse.queryIntents","camera.snap","camera.clip","screen.record","system.run","system.notify","file.read","file.write","file.info","file.list","file.delete"]'
```

This only needs to be done once per Gateway.

## Core Workflow: Sense → Think → Act → Verify

Every phone interaction follows this loop:

```
1. getUITree    →  understand current screen (package, elements, coordinates)
2. Think        →  decide action based on UI tree
3. Act          →  tap/swipe/type based on UI tree data
4. getUITree    →  verify the action worked (check package changed, element appeared)
```

**UI Tree first, always.** `getUITree` is cheap (~5KB text), tells you the current app (package), all elements, and their coordinates. Only use `screenshot` when you need to see visual content that isn't in the UI tree (images, videos, layout verification).

**Never screenshot as the first step.** Screenshot is expensive (~100KB), may be black on lock screen, and UI tree gives you more actionable data (exact coordinates, element IDs, text content).

## Invoking Commands

All commands use the `nodes` tool:

```json
{"action": "invoke", "node": "<nodeId>", "invokeCommand": "<command>", "invokeParamsJson": "<json>"}
```

## Essential Commands

### See the screen

Use the `nodes` tool's **`camera_snap` action** (not invoke):

```json
{"action": "camera_snap", "node": "<nodeId>"}
```

Gateway automatically saves the image to a **server-side file** and returns a local path. The raw image data does NOT enter your context — you get a `MEDIA:` path reference.

**For custom resolution/quality**, use invoke:
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

## Screen Management & Unlock

### Step-by-step unlock flow (follow this exactly):

```
1. getUITree {}
   → Check the "package" field in response:
     - "com.android.systemui" = lock screen
     - anything else = already unlocked, skip to your task

2. If locked and you have a PIN:
   phoneUse.unlock {pin: "1234"}
   → This command handles everything: wake screen, swipe up, enter PIN

3. Verify unlock:
   getUITree {}
   → package should NOT be "com.android.systemui"
   → If still locked, try unlock one more time

4. If unlock fails twice: report failure, do NOT keep retrying
```

**Important:**
- **Never screenshot on lock screen** — Android security blocks it, you'll get a black image
- **Use getUITree to check lock state** — look at `package` field
- `phoneUse.unlock` already calls `wakeScreen` internally — don't call it separately
- Omit `pin` for swipe/no-lock screens

## File Operations

Read, write, and browse files on the phone. Paths are typically under `/sdcard/`.

```json
{"invokeCommand": "file.list", "invokeParamsJson": "{\"path\": \"/sdcard/DCIM\"}"}
{"invokeCommand": "file.read", "invokeParamsJson": "{\"path\": \"/sdcard/DCIM/photo.jpg\", \"offset\": 0, \"size\": 2097152}"}
{"invokeCommand": "file.write", "invokeParamsJson": "{\"path\": \"/sdcard/Download/note.txt\", \"base64\": \"SGVsbG8=\"}"}
```

For large files, use chunked transfer. See [references/file-transfer.md](references/file-transfer.md).

## Navigation Rules (MUST follow)

1. **Always use package name for launch** — never display names like "哔哩哔哩"
   - ✅ `phoneUse.launch {package: "tv.danmaku.bili"}`
   - ❌ `phoneUse.launch {app: "哔哩哔哩"}`
   - If package unknown → `phoneUse.listApps {}` first

2. **Don't press Home as recovery** — use `back` to stay in context
   - Already in B站? Stay there, navigate from within
   - Pressing Home loses all app state

3. **Verify after every critical action** — `getUITree` to confirm
   - Check `package` field to confirm which app you're in
   - Don't assume an action worked

4. **Use deep links when available** — `openUrl("bilibili://search?keyword=xxx")` beats manual UI navigation

5. **Check context before acting** — `getUITree` first to see where you are
   - Text "搜索" exists on home screen AND in apps — wrong one = wrong app
   - Always confirm package before clicking

## Common Patterns

### Pattern: Open app and navigate

```
1. getUITree {}                                    — where am I?
2. phoneUse.launch {package: "tv.danmaku.bili"}    — always use package name
3. waitForElement {text: "首页", timeout: 5000}     — wait for app to load
4. getUITree {interactiveOnly: true}               — find navigation targets
5. findAndClick {text: "搜索"}                      — navigate
6. getUITree {}                                     — verify (check package!)
```

### Pattern: Fill a form

```
1. getUITree {interactiveOnly: true}   — find all input fields + coordinates
2. phoneUse.tap {x, y}                — tap first field
3. phoneUse.setText {text: "value"}    — enter text
4. phoneUse.tap {x, y}                — next field
5. phoneUse.setText {text: "value"}
6. findAndClick {text: "Submit"}
7. getUITree {}                         — verify submission result
```

### Pattern: Scroll to find content

```
1. getUITree {}                  — check visible elements
2. If target not found: scrollDown {}
3. getUITree {}                  — check again
4. Repeat until found or 5 scrolls max
```

### Pattern: Unlock → Open app → Do task

```
1. getUITree {}                        — check if locked (package = com.android.systemui?)
2. phoneUse.unlock {pin: "1234"}       — only if locked
3. getUITree {}                        — verify unlocked
4. phoneUse.launch {package: "..."}    — open target app
5. getUITree {}                        — verify app loaded
6. ... continue with task
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

- **getUITree is your eyes** — use it before and after every action. It's cheap (~5KB).
- **Screenshot only for visual content** — photos, videos, layouts. Never as the default "see" step.
- **Never screenshot on lock screen** — it returns black. Use getUITree to check lock state.
- **Prefer `findAndClick` over coordinate tapping** when button text is known.
- **Wait for loading**: Use `phoneUse.waitForElement` after launching apps.
- **`getForegroundApp`** is the fastest way to check which app is active (1 field vs full UI tree).
- **Don't retry endlessly** — if an action fails twice, report failure and ask the user.
- **`system.run` cannot launch Activities** — no shell permissions. Use `phoneUse.launch` or `phoneUse.startActivity`.
