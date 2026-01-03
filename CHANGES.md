# Technical Implementation Notes

**Project**: VseMoiOnline (v2rayNG Fork)

This document contains detailed technical information for developers working on VseMoiOnline. For user-facing documentation, see README.md. For change history, see V2RAY_CHANGELOG.md.

---

## Android Client Implementation

### Modified Files
- `V2rayNG/app/build.gradle.kts`
- `V2rayNG/app/src/main/AndroidManifest.xml`
- `V2rayNG/app/src/main/res/values/strings.xml`
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

### Application Identity
**File**: `app/build.gradle.kts:8-12`

Changed application ID while preserving namespace:
```kotlin
android {
    namespace = "com.v2ray.ang"  // Unchanged for BuildConfig compatibility
    compileSdk = 36

    defaultConfig {
        applicationId = "online.vsemoi"  // New package ID
```

**Rationale**: Allows installation alongside original v2rayNG without code refactoring.

### Application Name
**File**: `app/src/main/res/values/strings.xml:3`

```xml
<string name="app_name" translatable="false">VseMoiOnline</string>
```

### URI Scheme Registration
**File**: `AndroidManifest.xml:70-76`

Registered `vsemoionline://` custom URI scheme with intent filter on MainActivity:
```xml
<!-- VseMoiOnline Magic Link Support -->
<intent-filter android:label="VseMoiOnline Configuration">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="vsemoionline" />
</intent-filter>
```

### Magic Link Handler
**File**: `MainActivity.kt:210-220`

The `handleMagicLink()` method:
1. Checks for `vsemoionline://` scheme in intent
2. Extracts `?url=` query parameter
3. Calls `fetchAndImportConfig()` with provision URL
4. Logs all operations to logcat with "VseMoiOnline:" prefix

**Lifecycle Integration**:
- `onCreate()` (line 197): Handles cold start clicks
- `onNewIntent()` (line 200-204): Handles clicks when app already running

### Device Identity System
**File**: `MainActivity.kt:226-239`

`getOrCreateDeviceId()` implementation:
- Uses SharedPreferences with key `"vsemoionline_prefs"`
- Generates UUID via `java.util.UUID.randomUUID()`
- Persists across app sessions
- Avoids hardware identifiers (privacy-compliant)
- Returns same UUID for all backend requests

### Configuration Provisioning
**File**: `MainActivity.kt:245-287`

`fetchAndImportConfig()` method:
1. Shows progress spinner (`pbWaiting`)
2. Appends `device_id=UUID` parameter to provision URL
3. Downloads VLESS URI via HTTP GET (10s timeout)
4. Validates HTTP 200 response
5. Passes URI to existing `importBatchConfig()` method
6. Displays success/error toast
7. Hides progress spinner

**Network Operations**:
- Uses `lifecycleScope.launch(Dispatchers.IO)` for async download
- `HttpURLConnection` with 10-second connect/read timeout
- `withContext(Dispatchers.Main)` for UI updates
- Exception handling with user-facing error messages

### Auto-Provisioning Until Success
**File**: `MainActivity.kt:372-383`

`checkAndAutoProvision()` implementation:
- Called from `onResume()` every time app resumes (line 365)
- Checks if server list is empty via `MmkvManager.decodeServerList()`
- If empty → triggers provisioning from hardcoded backend URL
- **Retries on every resume until successful**
- No persistent "attempted" flag - server list itself is the state tracker
- Stops retrying once any server is successfully added to list

**Hardcoded Backend URL** (line 380):
```kotlin
val defaultBackendUrl = "http://103.241.67.124:8888/provision"
```

**Retry Behavior:**
- ✅ Backend unavailable → Retries on next app resume
- ✅ Network error → Retries on next app resume
- ✅ Config import fails → Retries on next app resume
- ✅ Success → Stops trying (server list no longer empty)

---

## End-to-End Flow

### Magic Link Flow
1. User clicks `vsemoionline://import?url=http://server:8888/provision` link
2. Android intent system launches MainActivity
3. `handleMagicLink()` extracts URL parameter
4. Device UUID retrieved from SharedPreferences
5. HTTP GET: `http://server:8888/provision?device_id=<uuid>`
6. Server generates VLESS config and returns URI
7. Client downloads VLESS URI string
8. `importBatchConfig()` parses and imports config
9. User sees server in list, can connect immediately

### Auto-Provision Flow
1. User installs and opens app for first time
2. `onResume()` calls `checkAndAutoProvision()`
3. Checks if `serverList` is empty
4. If empty, triggers `fetchAndImportConfig()` automatically
5. Config imported silently in background
6. User sees server appear in list
7. On next resume, `serverList` is not empty → stops retrying

**If Backend Unavailable:**
1. User opens app, backend is down
2. Provisioning fails (network error)
3. User closes app, backend comes online
4. User reopens app → `onResume()` triggers retry
5. Provisioning succeeds → server added to list

---

## Integration with v2rayNG Architecture

### Config Import Integration
VseMoiOnline reuses v2rayNG's existing import infrastructure:
- `AngConfigManager.importBatchConfig()` - Handles VLESS URI parsing
- `MainViewModel.reloadServerList()` - Refreshes UI after import
- Existing QR code, clipboard, and manual import paths unchanged

### VLESS URI Format
Backend returns standard VLESS URI:
```
vless://UUID@SERVER:PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBLIC_KEY&sid=SHORT_ID&type=tcp#VseMoiOnline
```

Parsed by v2rayNG's native `AngConfigManager` without modification.

---

## Testing & Validation

### Verified Scenarios
✅ Cold start: App not running, click magic link → Config imports
✅ Hot resume: App already open, click magic link → Config imports
✅ Auto-provision: First launch with empty server list → Config imports
✅ Device persistence: Same UUID across app restarts
✅ Server recognition: Returning devices get existing config
✅ Build success: Compiles without errors (namespace preservation)

### Test Command (ADB)
```bash
# Trigger magic link
adb shell am start -a android.intent.action.VIEW \
  -d "vsemoionline://import?url=http://103.241.67.124:8888/provision"

# View logs
adb logcat | grep "VseMoiOnline:"
```

### Expected Log Output
```
VseMoiOnline: Magic link detected, provisioning from: http://...
VseMoiOnline: Generated new device UUID: <uuid>  (or "Using existing device UUID")
VseMoiOnline: Fetching config from: http://.../provision?device_id=<uuid>
VseMoiOnline: Received config, importing...
```

---

## Branding Notes

### Application Name
- **Display name**: "VseMoiOnline"
- **Package ID**: `online.vsemoi`
- **Namespace**: `com.v2ray.ang` (preserved for compatibility)

### Backend URL
Hardcoded in `MainActivity.kt:390`. To change:
1. Edit `defaultBackendUrl` variable
2. Rebuild APK
3. Future: Make configurable via Settings

### Logos & Icons
- Currently using original v2rayNG icons
- Future: Replace launcher icons in `app/src/main/res/mipmap-*/`

---

## Privacy & Security

### Device Identification
- Random UUID generated on first launch
- No hardware identifiers (IMEI, MAC address, etc.)
- UUID stored in app-private SharedPreferences
- Cannot be traced to physical device

### Network Traffic
- HTTP used for provisioning (config download)
- ⚠️ **Production Note**: VLESS URI transmitted in plaintext
- VLESS connection itself uses Reality protocol (encrypted)
- Auto-provisioning only on first launch (no background activity)

### Permissions
No additional permissions required beyond original v2rayNG:
- `INTERNET` - Already present
- `ACCESS_NETWORK_STATE` - Already present
- No camera, location, or sensitive permissions

---

## Future Enhancements

### Potential Improvements
1. **HTTPS Backend**: Encrypt provisioning traffic
2. **Configurable Backend URL**: Settings UI for custom backend
3. **Multi-Server Support**: Provision multiple configs at once
4. **Subscription Updates**: Periodic config refresh from backend
5. **Error Recovery**: Retry button in UI if auto-provision fails

### Migration Path
To update backend URL without rebuilding:
1. Implement Settings preference for backend URL
2. Store in MmkvManager (v2rayNG's config storage)
3. Use stored URL if present, fall back to hardcoded
4. Add UI in SettingsActivity for URL configuration

---
