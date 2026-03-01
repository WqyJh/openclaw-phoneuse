# Android Intents & Deep Links Reference

## Discovering Intents

Don't guess — query the device:

```json
// List all exported activities for an app
{"invokeCommand": "phoneUse.queryIntents", "invokeParamsJson": "{\"package\": \"tv.danmaku.bili\"}"}

// Find apps that handle a specific action
{"invokeCommand": "phoneUse.queryIntents", "invokeParamsJson": "{\"action\": \"android.intent.action.VIEW\", \"category\": \"android.intent.category.BROWSABLE\"}"}
```

## Starting Activities

```json
// Open a specific settings page
{"invokeCommand": "phoneUse.startActivity", "invokeParamsJson": "{\"action\": \"android.settings.WIFI_SETTINGS\"}"}

// Open a URL in the browser
{"invokeCommand": "phoneUse.openUrl", "invokeParamsJson": "{\"url\": \"https://example.com\"}"}

// Open a deep link
{"invokeCommand": "phoneUse.startActivity", "invokeParamsJson": "{\"action\": \"android.intent.action.VIEW\", \"uri\": \"bilibili://video/BV1xx411c7mD\"}"}

// Launch a specific app activity
{"invokeCommand": "phoneUse.startActivity", "invokeParamsJson": "{\"package\": \"com.tencent.mm\", \"class\": \"com.tencent.mm.ui.LauncherUI\"}"}
```

## Common Settings Actions

These work on all Android devices:

| Action | Opens |
|--------|-------|
| `android.settings.SETTINGS` | Main settings |
| `android.settings.WIFI_SETTINGS` | Wi-Fi |
| `android.settings.BLUETOOTH_SETTINGS` | Bluetooth |
| `android.settings.DISPLAY_SETTINGS` | Display |
| `android.settings.SOUND_SETTINGS` | Sound |
| `android.settings.BATTERY_SAVER_SETTINGS` | Battery |
| `android.settings.APPLICATION_SETTINGS` | Apps list |
| `android.settings.LOCATION_SOURCE_SETTINGS` | Location |
| `android.settings.SECURITY_SETTINGS` | Security |
| `android.settings.ACCESSIBILITY_SETTINGS` | Accessibility |
| `android.settings.DEVICE_INFO_SETTINGS` | About phone |
| `android.settings.DATE_SETTINGS` | Date & time |
| `android.settings.LOCALE_SETTINGS` | Language |
| `android.settings.INPUT_METHOD_SETTINGS` | Keyboard |
| `android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION` | File access |

## Common Deep Link Patterns

Use `phoneUse.openUrl` for these:

| App | URL Pattern |
|-----|-------------|
| Browser | `https://example.com` |
| Maps | `geo:37.7749,-122.4194` |
| Dialer | `tel:+1234567890` |
| SMS | `sms:+1234567890?body=Hello` |
| Email | `mailto:user@example.com` |
| WeChat | `weixin://` |
| Alipay | `alipays://platformapi/startapp?appId=...` |
| Bilibili | `bilibili://video/BV...` |
