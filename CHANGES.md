# Technical Implementation Notes

**Project**: VseMoiOnline (v2rayNG Fork)

This document contains detailed technical information for developers working on VseMoiOnline. For user-facing documentation, see README.md. For change history, see CHANGELOG.md.

---

## Recent Updates (2026-01-05)

### Censorship-Resistant Provisioning System

**Objective**: Enable clients to automatically provision VPN configs even when primary provisioning domain is blocked by censors. Implement multi-tier fallback mechanism with distributed platform-hosted endpoints.

**Threat Model**:
- DNS/IP blocking of provisioning domain
- DNS/IP blocking of VPN server
- Both provisioning and VPN server blocked simultaneously
- Port scanning and infrastructure discovery by censors
- Traffic analysis and information leakage

**Key Changes**:

#### 1. Multi-Tier Fallback System
- **Last working URL** (Priority 1): Client tries previously successful URL first
- **Primary URL** (Priority 2): Hardcoded provisioning endpoint
- **Platform URLs** (Priority 3): GitHub Gists, Cloudflare Workers, Vercel, etc.
- Each attempt has 8-second timeout for fast failover
- Sequential retry stops at first success

#### 2. Duplicate Prevention
- Added `isProvisioningInProgress` flag
- Prevents race conditions when `onResume()` called multiple times
- Ensures only one provisioning attempt runs at a time

#### 3. Security Architecture Decision
**VPN-based update push mechanism was initially implemented but removed for security reasons:**
- Exposing additional ports on VPN server creates discoverable infrastructure
- Status API enables information leakage to censors monitoring responses
- Creates DDoS attack surface and traffic analysis vectors
- Risk of preemptive blocking when censors learn new provisioning URLs

**Final architecture relies solely on:**
- Distributed platform fallback URLs (harder to block all simultaneously)
- Local persistence of last working URL
- Magic links for emergency distribution (`vsemoionline://import?url=...`)

### Modified Files (Censorship Resistance - 2026-01-05)
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` - Multi-tier fallback logic
- `.gitignore` - Exclude abandoned provisioning-update-backend/

### Implementation Details

#### Client-Side Fallback System
**File**: `MainActivity.kt:56-75`

Added companion object constants for fallback configuration:
```kotlin
companion object {
    private const val PREFS_NAME = "vsemoionline_prefs"
    private const val PREF_LAST_WORKING_URL = "last_working_provisioning_url"
    private const val PROVISION_TIMEOUT_MS = 8000 // 8 seconds per attempt

    private const val PRIMARY_PROVISION_URL = "http://103.241.67.124:8888/provision"

    private val FALLBACK_PLATFORM_URLS = listOf(
        "https://gist.githubusercontent.com/.../endpoint.txt"
        // GitHub, Cloudflare Workers, Vercel, etc.
    )
}
```

#### Fallback Logic Implementation
**File**: `MainActivity.kt:307-377`

The `tryProvisioningWithFallback()` method:
1. Checks `isProvisioningInProgress` flag to prevent duplicates
2. Builds URL list in priority order (last working → primary → platforms)
3. Iterates through URLs sequentially with 8-second timeout each
4. For platform URLs, calls `fetchProvisioningEndpoint()` to get actual URL
5. Calls `fetchAndImportConfig()` for each URL until success
6. Saves successful URL using `saveLastWorkingProvisionUrl()`
7. Clears flag in `finally` block to ensure cleanup

**Platform URL Resolution**:
Platform URLs return plain text like:
- `103.241.67.124:8888` (IP:port format)
- `provision.example.com` (domain format)
- `https://provision.example.com/provision` (full URL)

The client constructs the full provisioning URL from this response.

#### Duplicate Prevention
**File**: `MainActivity.kt:103`

Added instance variable:
```kotlin
private var isProvisioningInProgress = false
```

Used in `tryProvisioningWithFallback()`:
```kotlin
if (isProvisioningInProgress) {
    Log.i(AppConfig.TAG, "Provisioning already in progress, skipping")
    return
}
isProvisioningInProgress = true
try {
    // ... provisioning logic ...
} finally {
    isProvisioningInProgress = false
}
```

### Censorship Resistance Coverage

| Scenario | Primary | Last URL | Platforms | Magic Link |
|----------|---------|----------|-----------|------------|
| Normal ops | ✅ | ✅ | ➖ | ➖ |
| Primary blocked | ❌ | ✅ | ✅ | ✅ |
| Provision blocked, VPN works | ❌ | ❌ | ✅ | ✅ |
| Both blocked | ❌ | ❌ | ✅ | ✅ |
| All infrastructure blocked | ❌ | ❌ | ❌ | ✅ |

**Magic Link** (`vsemoionline://import?url=...`) remains the ultimate fallback, distributable via Telegram, email, or messaging apps.

**Note**: VPN-based update push was considered but rejected to avoid exposing additional ports and creating information leakage vectors.

---

## Recent Updates (2026-01-04)

### UI/UX Redesign for Non-Technical Users

**Objective**: Transform v2rayNG into a simple, one-tap VPN solution suitable for family members with no technical knowledge.

**Key Changes**:
1. Custom branding with app-specific icons
2. WireGuard-style inline toggle switches
3. Removed all technical information from UI
4. Streamlined navigation to essential features only
5. Localized connection names for better UX

### Modified Files (UI Redesign)
- `V2rayNG/app/src/fdroid/res/values/strings.xml` - App name
- `V2rayNG/app/src/main/res/values/strings.xml` - Connection name strings
- `V2rayNG/app/src/main/res/values-ru/strings.xml` - Russian translations
- `V2rayNG/app/src/main/res/layout/item_recycler_main.xml` - Connection list item
- `V2rayNG/app/src/main/res/layout/nav_header.xml` - Navigation header
- `V2rayNG/app/src/main/res/layout/activity_main.xml` - Main activity (FAB hidden)
- `V2rayNG/app/src/main/res/layout/activity_about.xml` - About screen (gutted)
- `V2rayNG/app/src/main/res/menu/menu_drawer.xml` - Navigation menu
- `V2rayNG/app/src/main/res/menu/menu_main.xml` - Toolbar menu
- `V2rayNG/app/src/main/res/mipmap-*/ic_launcher*.png` - Custom icons (all densities)
- `V2rayNG/app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` - Adaptive icons
- `V2rayNG/app/src/main/res/drawable/ic_refresh_24dp.xml` - Refresh icon (new)
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` - Navigation handlers
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainRecyclerAdapter.kt` - Toggle logic
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/AboutActivity.kt` - Simplified
- `V2rayNG/app/src/main/java/com/v2ray/ang/handler/V2RayServiceManager.kt` - Test result

### Modified Files (Auto-Provisioning - 2026-01-02)
- `V2rayNG/app/build.gradle.kts`
- `V2rayNG/app/src/main/AndroidManifest.xml`
- `V2rayNG/app/src/main/res/values/strings.xml`
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

---

## Android Client Implementation

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

## UI/UX Redesign Implementation Details (2026-01-04)

### Custom Launcher Icons

**Files Modified**: All `mipmap-*/ic_launcher*.png` files

Replaced v2rayNG icons with custom VseMoiOnline branding:
- **Foreground**: `/icons/ic_launcher_foreground.png` (192x192px)
- **Background**: `/icons/ic_launcher_background.png` (192x192px)
- Deployed to all density folders: mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi

**Adaptive Icon Configuration**:
```xml
<!-- ic_launcher.xml -->
<adaptive-icon>
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

Changed from color-based background to image-based for proper layering.

### WireGuard-Style Toggle Switch

**File**: `MainRecyclerAdapter.kt:128-159`

Added inline toggle functionality:
```kotlin
// Setup switch for VPN connection
val isSelected = guid == MmkvManager.getSelectServer()
holder.itemMainBinding.switchConnection.isChecked = isRunning && isSelected
holder.itemMainBinding.switchConnection.isEnabled = isSelected

holder.itemMainBinding.switchConnection.setOnCheckedChangeListener { _, isChecked ->
    if (isChecked) {
        // Start VPN with permission check
        if (VpnService.prepare() == null) {
            mActivity.startV2Ray()
        } else {
            // Request permission, revert switch
            holder.itemMainBinding.switchConnection.isChecked = false
            mActivity.requestVpnPermission.launch(intent)
        }
    } else {
        // Stop VPN
        V2RayServiceManager.stopVService(mActivity)
    }
}
```

**Layout**: `item_recycler_main.xml:38-42`
```xml
<com.google.android.material.switchmaterial.SwitchMaterial
    android:id="@+id/switch_connection"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginEnd="@dimen/padding_spacing_dp8" />
```

**Bug Fix**: Toggle state synchronization
```kotlin
// MainActivity.kt:305
mainViewModel.isRunning.observe(this) { isRunning ->
    adapter.isRunning = isRunning
    adapter.notifyDataSetChanged()  // Critical: refresh switch states
    // ... rest of FAB logic
}
```

Without `notifyDataSetChanged()`, switch stays OFF after VPN permission grant until app resume.

### Localized Connection Names

**Implementation**: Replace profile.remarks with localized string

**Adapter**: `MainRecyclerAdapter.kt:65`
```kotlin
holder.itemMainBinding.tvName.text = mActivity.getString(R.string.vpn_connection_name)
```

**String Resources**:
```xml
<!-- values/strings.xml -->
<string name="vpn_connection_name">Turn VPN On/Off</string>

<!-- values-ru/strings.xml -->
<string name="vpn_connection_name">Вкл./Выкл. VPN</string>
```

Automatically uses device language. Actual connection name ("VseMoiOnline") remains in database for backend compatibility.

### Simplified Connection List UI

**File**: `item_recycler_main.xml`

Reduced from 224 lines to 96 lines by:
1. Removing nested layouts for statistics, type, test results
2. Hiding action buttons (share, edit, delete, more) with `visibility="gone"`
3. Keeping IDs to prevent adapter crashes but setting dimensions to 0dp
4. Clean single-line layout: `[indicator] [name] [toggle]`

**Result**: Clean, minimal display suitable for non-technical users.

### Navigation Drawer Cleanup

**Menu**: `menu_drawer.xml`

Removed 8 items, kept 5:
```xml
<group android:id="@+id/group_main">
    <item android:id="@+id/per_app_proxy_settings" />  <!-- Essential for banking apps -->
    <item android:id="@+id/source_code" />
    <item android:id="@+id/oss_licenses" />
    <item android:id="@+id/tg_channel" />
    <item android:id="@+id/privacy_policy" />
    <item android:id="@+id/placeholder" />  <!-- Version display -->
</group>
```

**Version Display**: `nav_header.xml:20-27`
```xml
<TextView
    android:id="@+id/tv_version_header"
    android:textAppearance="@style/TextAppearance.AppCompat.Small"
    android:textColor="?android:attr/textColorSecondary" />
```

**Population**: `MainActivity.kt:163-165`
```kotlin
binding.navView.getHeaderView(0)?.findViewById<TextView>(R.id.tv_version_header)?.text =
    "v${BuildConfig.VERSION_NAME} (${SpeedtestManager.getLibVersion()})"
```

### Toolbar Simplification

**Menu**: `menu_main.xml`

Reduced from 100+ lines to 9 lines:
```xml
<menu>
    <item
        android:id="@+id/service_restart"
        android:icon="@drawable/ic_refresh_24dp"
        android:title="@string/title_service_restart"
        app:showAsAction="always" />
</menu>
```

Only service restart button remains for troubleshooting stuck connections.

### About Screen Gutted

**Strategy**: Move all useful items to navigation drawer, bypass About activity

**AboutActivity.kt**: Removed 179 lines
- Deleted backup/restore/share functionality
- Deleted permission launchers
- Deleted file chooser logic
- Kept only version display (now redundant)

**Navigation Handler**: `MainActivity.kt:670-682`
```kotlin
when (item.itemId) {
    R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
    R.id.source_code -> Utils.openUri(this, AppConfig.APP_URL)
    R.id.oss_licenses -> {
        val webView = WebView(this)
        webView.loadUrl("file:///android_asset/open_source_licenses.html")
        AlertDialog.Builder(this).setView(webView).show()
    }
    R.id.tg_channel -> Utils.openUri(this, AppConfig.TG_CHANNEL_URL)
    R.id.privacy_policy -> Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY)
}
```

### Security Enhancement: Connection Test Results

**File**: `V2RayServiceManager.kt:255-260`

**Removed**:
```kotlin
// OLD: Exposed server IP and country
SpeedtestManager.getRemoteIPInfo()?.let { ip ->
    MessageUtil.sendMsg2UI(service, MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
}
```

**Result**: Only shows `"Success: Connection took 12ms"` without IP/country.

**Rationale**: Prevents non-technical users from accidentally sharing server IP in screenshots, reducing risk of censorship.

### Floating Action Button (FAB)

**File**: `activity_main.xml:110`

```xml
<FloatingActionButton
    android:id="@+id/fab"
    android:visibility="gone"  <!-- Hidden -->
    ... />
```

Replaced by inline toggle switches. FAB update logic remains for future compatibility but button is invisible.

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
