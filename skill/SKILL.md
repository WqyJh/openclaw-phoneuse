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

### See the screen

```json
{"invokeCommand": "phoneUse.screenshot", "invokeParamsJson": "{}"}
```
Returns compressed JPEG base64. Default 720p quality 60.

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

## Tips

- **Prefer `findAndClick` over coordinate tapping** when button text is known — it's more reliable across screen sizes.
- **Use `getUITree` with `interactiveOnly: true`** to discover exact element coordinates instead of guessing.
- **Always verify** actions with a screenshot after performing them.
- **Wait for loading**: Use `phoneUse.waitForElement` after launching apps or navigating.
- **Screen coordinates** are in pixels. Use `phoneUse.getScreenInfo` to get screen dimensions if needed.
- Commands that need the screen (tap, screenshot) **auto-wake** the display — no need to call `wakeScreen` first in most cases.
