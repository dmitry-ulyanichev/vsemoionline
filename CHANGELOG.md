# Changelog

All notable changes to VseMoiOnline will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.1.0] - 2026-01-04

### Added
- **Custom branding with launcher icons**
  - Custom app icon with foreground and background layers (`ic_launcher_foreground.png`, `ic_launcher_background.png`)
  - Icons deployed to all density folders (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
  - Adaptive icons properly configured for Android 8.0+

- **WireGuard-style inline VPN toggle**
  - Material Design toggle switch added directly in connection item layout
  - One-tap VPN control without floating action button
  - Switch state automatically syncs with VPN status
  - Fixed toggle state update issue after VPN permission grant

- **Localized connection display name**
  - English: "Turn VPN On/Off"
  - Russian: "Вкл./Выкл. VPN"
  - User-friendly instruction instead of technical name

- **Per-app proxy settings**
  - Restored to navigation drawer for banking app exclusions
  - Allows users to bypass VPN for specific apps (e.g., SberbankOnline)

- **Navigation drawer enhancements**
  - App version info displayed in header below app name
  - Direct access to Source code, Open source licenses, Telegram channel, Privacy policy
  - Removed redundant "About" screen - all items moved to main menu

### Changed
- **App name for F-Droid variant**
  - Changed from "v2rayNG (F-Droid)" to "Все Мои Онлайн"
  - Displays as "VseMoiOnline" on device home screen

- **Simplified connection list UI**
  - Removed technical details (IP address, port, protocol type)
  - Removed subscription indicator and statistics
  - Removed ping test results display
  - Removed action buttons (share, edit, delete, more options)
  - Clean single-line display with just connection name and toggle

- **Streamlined main toolbar**
  - Removed search/filter icon
  - Removed add config icon and submenu
  - Removed three-dot overflow menu
  - **Only** refresh icon (service restart) remains for troubleshooting

- **Minimized navigation drawer**
  - Removed: Subscription settings, Routing settings, User asset settings, General settings, Promotion, Logcat, Check for updates
  - Kept: Per-app proxy settings, Source code, Licenses, Telegram, Privacy policy

### Removed
- **From About screen**
  - Backup configuration feature
  - Share configuration feature
  - Restore configuration feature
  - Feedback feature
  - Entire About activity now bypassed - items moved to navigation drawer

- **From connection test results**
  - Country code display (security: prevents IP disclosure)
  - Server IP address display (security: prevents accidental sharing)
  - Only shows: "Success: Connection took Xms"

- **Floating action button (FAB)**
  - Hidden completely (`android:visibility="gone"`)
  - Replaced by inline toggle switches

### Fixed
- **Toggle switch state synchronization**
  - Switch now immediately updates to ON after granting VPN permission
  - Added `adapter.notifyDataSetChanged()` call in `isRunning` observer
  - Eliminates need to leave/return to app to see correct state

### Technical Details
- **Modified files**: 36 files changed
- **Code reduction**: 773 deletions, 162 additions (net -611 lines)
- **Layouts simplified**: `item_recycler_main.xml` reduced from 224 to 96 lines
- **Navigation cleaned**: `menu_drawer.xml` and `menu_main.xml` streamlined
- **Adapter enhanced**: WireGuard-style toggle logic in `MainRecyclerAdapter.kt`
- **String resources**: Localization support for English and Russian

### User Experience
- **Zero-configuration VPN** for non-technical users
- **One-tap connect/disconnect** with visual toggle
- **No confusing technical information** displayed
- **Banking app compatibility** via per-app proxy settings
- **Clean, minimal interface** focused on essential functions

## [1.0.0] - 2026-01-02

### Added
- **Magic link provisioning** via `vsemoionline://` URI scheme
  - Custom intent filter in AndroidManifest.xml to handle `vsemoionline://` links
  - `handleMagicLink()` method to process incoming magic links with `?url=` parameter
  - Automatic VLESS configuration download from backend server
  - Integration with v2rayNG's native `importBatchConfig()` for config parsing

- **Device identity management**
  - `getOrCreateDeviceId()` method generating persistent UUIDs
  - SharedPreferences-based storage for device IDs (`vsemoionline_prefs`)
  - Device ID appended to provision URLs for server-side device recognition
  - Privacy-friendly: no hardware identifiers used

- **Automatic provisioning with retry**
  - `checkAndAutoProvision()` triggered on every app resume
  - Checks for empty server list via `MmkvManager.decodeServerList()`
  - Retries on every resume until successful
  - No persistent "attempted" flag - resilient to network failures

- **Network capabilities**
  - Coroutine-based asynchronous HTTP downloads (`Dispatchers.IO`)
  - `HttpURLConnection` with 10-second connect/read timeouts
  - Progress spinner during config download
  - Error handling with user-facing toast messages

- **User experience improvements**
  - Zero-config VPN setup via single link click
  - Toast notifications for import success/failure
  - Integration with both cold start (`onCreate`) and hot resume (`onNewIntent`) flows
  - Seamless import using v2rayNG's existing UI

### Changed
- **Application identity**
  - Changed `applicationId` from `com.v2ray.ang` to `online.vsemoi`
  - Preserved `namespace` as `com.v2ray.ang` for BuildConfig compatibility
  - Updated app name from "v2rayNG" to "VseMoiOnline"

- **MainActivity lifecycle**
  - Modified `onCreate()` to call `handleMagicLink(intent)`
  - Modified `onResume()` to call `checkAndAutoProvision()`
  - Added `onNewIntent()` for handling magic links when app is running

### Technical Details
- **Backend integration**: FastAPI server with VLESS URI generation
- **Protocol**: VLESS + Reality (xtls-rprx-vision flow)
- **Config format**: Standard VLESS URI with Reality parameters
- **State persistence**: SharedPreferences for device UUID and provisioning flag
- **Logging**: All operations prefixed with "VseMoiOnline:" in logcat

### Build Information
- Based on v2rayNG 1.10.33 (version code 684)
- Target SDK: 36
- Min SDK: 24
- Kotlin JVM target: 17
- Build successful on 2026-01-02

## Upstream Base

Based on [v2rayNG](https://github.com/2dust/v2rayNG) version 1.10.33 (commit: master branch as of 2024-12-31).

### Preserved Upstream Features
- ✅ VLESS/VMess/Trojan protocol support
- ✅ Reality protocol support
- ✅ Manual configuration import (QR code, clipboard, file)
- ✅ Multiple server profiles
- ✅ Auto-reconnect
- ✅ Traffic statistics
- ✅ Routing rules
- ✅ Custom DNS
- ✅ All original v2rayNG functionality

### Compatibility
- Can be installed alongside original v2rayNG (different package ID)
- Fully compatible with v2rayNG server configurations
- No breaking changes to upstream features

---

## Future Roadmap

### Planned Features
- [ ] HTTPS backend support for secure provisioning
- [ ] Configurable backend URL in app settings
- [ ] Multi-server provisioning (receive multiple configs)
- [ ] Subscription update mechanism
- [ ] Custom launcher icons and branding
- [ ] In-app manual retry for failed provisioning

### Under Consideration
- [ ] QR code generation for invite links
- [ ] Backup/restore device UUID
- [ ] Server health monitoring
- [ ] Fallback server list
- [ ] Connection quality metrics

---
