# Compliance & Attribution

## Fork Information

**Project Name**: VseMoiOnline
**Upstream Project**: [v2rayNG](https://github.com/2dust/v2rayNG)
**License**: GPL-3.0

## License

This project maintains the same license as the upstream v2rayNG project.

The original v2rayNG codebase is:
- Copyright © 2024 v2rayNG Contributors
- Licensed under GNU General Public License v3.0
- SPDX-License-Identifier: GPL-3.0-only

VseMoiOnline modifications are also released under GPL-3.0.

## Modified Files

The following files have been modified from the upstream v2rayNG project:

### 1. `V2rayNG/app/build.gradle.kts`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/build.gradle.kts
- **Changes**:
  - Changed `applicationId` from `"com.v2ray.ang"` to `"online.vsemoi"` (line 12)
  - Preserved `namespace` as `"com.v2ray.ang"` for BuildConfig compatibility (line 8)

### 2. `V2rayNG/app/src/main/res/values/strings.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values/strings.xml
- **Changes**:
  - Changed `app_name` from `"v2rayNG"` to `"VseMoiOnline"` (line 3)

### 3. `V2rayNG/app/src/main/AndroidManifest.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/AndroidManifest.xml
- **Changes**:
  - Added `<intent-filter>` for `vsemoionline://` URI scheme in MainActivity (lines 70-76)

### 4. `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt
- **Changes**:
  - Added `onNewIntent()` method
  - Added `handleMagicLink()` method — dispatches `vsemoionline://import` and `vsemoionline://activate` deep links
  - Added `handleActivateDeepLink()` method — POSTs activation token, re-provisions on success, restarts VPN tunnel if running
  - Added `getOrCreateDeviceId()` method
  - Added `fetchAndImportConfig()` method
  - Added `checkAndAutoProvision()` method — cleans up stale configs if more than one server found in MMKV
  - Added `tryProvisioningWithFallback()` method — two-cycle provisioning with multi-tier URL fallback
  - Added `fetchProvisioningEndpoint()` method
  - Added `saveLastWorkingProvisionUrl()` / `getLastWorkingProvisionUrl()` methods
  - Added `saveProvisionData()` method
  - Added `startStatusPolling()` / `stopStatusPolling()` / `pollStatus()` methods
  - Modified `onCreate()` to call `handleMagicLink(intent)`
  - Modified `onResume()` to call `checkAndAutoProvision()`
  - Added subscription UI methods: `setupVsmUi`, `setupCodeFields`, `handleCodePaste`, `checkSubmitEnabled`, `submitActivationCode`, `toggleSubBlock`, `openCabinetUrl`, `onServerRowTapped`, `updateSubscriptionHeader`, `startBlinking`, `updateServerRow`, `updateSubBlock`, `trafficRingColor`, `formatGb`
  - Extended `pollStatus()` to parse and persist days_remaining, traffic_remaining, throttle_mbps

### 5. `V2rayNG/app/src/main/res/values/strings.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values/strings.xml
- **Changes**:
  - Added all VseMoiOnline Russian UI strings (vsm_ prefix); upstream strings untouched

### 6. `V2rayNG/app/src/main/res/values/colors.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values/colors.xml
- **Changes**:
  - Added 20 vsm_ brand colours; upstream colour entries untouched

### 7. `V2rayNG/app/src/main/res/values-night/colors.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values-night/colors.xml
- **Changes**:
  - Added 10 vsm_ dark-mode colour overrides; upstream entries untouched

### 8. `V2rayNG/app/src/main/res/values/themes.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values/themes.xml
- **Changes**:
  - Changed `AppThemeDayNight` parent from `Theme.AppCompat.DayNight` to `Theme.MaterialComponents.DayNight`
  - Changed `colorPrimary` to `vsm_link` (#1565C0); `colorPrimaryDark` and `statusBarColor` to `vsm_toolbar`

### 9. `V2rayNG/app/src/main/res/values-night/themes.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values-night/themes.xml
- **Changes**:
  - Same theme parent and colour changes as `values/themes.xml`

### 10. `V2rayNG/app/src/main/res/menu/menu_main.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/menu/menu_main.xml
- **Changes**:
  - Changed `service_restart` item `showAsAction` from `always` to `never` (moves reload icon to overflow menu)

### 11. `V2rayNG/app/src/main/res/layout/activity_main.xml`
- **Upstream**: https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/layout/activity_main.xml
- **Changes**:
  - Full restructure for VseMoiOnline subscription UI; original view IDs preserved for binding compatibility

### 12. `V2rayNG/app/src/main/java/com/v2ray/ang/ui/DonutChartView.kt` *(new file)*
- No upstream equivalent
- Custom canvas-drawn donut ring for traffic and speed visualisation

### 13. `V2rayNG/app/src/main/res/drawable/code_field_bg.xml` *(new file)*
- No upstream equivalent
- Selector drawable for 8-field activation code input

## Attribution Requirements

When distributing this software:
1. Maintain original copyright notices from v2rayNG contributors
2. Include link to upstream v2rayNG repository
3. Clearly identify modifications made in the VseMoiOnline fork
4. Include copy of GPL-3.0 license
5. Provide access to modified source code (GPL requirement)

## Upstream Compatibility

VseMoiOnline is designed to remain compatible with standard VLESS configurations and v2rayNG server profiles. The magic link feature is additive and does not modify:
- Core Xray-core integration
- VLESS/VMess/Trojan protocol implementations
- Reality protocol support
- VPN service architecture
- Config parsing logic

### Differences from Upstream
- Custom `applicationId` allows side-by-side installation with v2rayNG
- Auto-provisioning feature with retry-until-success behavior
- Magic link import via `vsemoionline://` scheme
- Device UUID generation for backend recognition

All v2rayNG features remain functional and unchanged.

## Automatic Provisioning

The application performs automatic configuration provisioning whenever:
1. App resumes (comes to foreground)
2. No servers are configured in app

The provisioning retries on every app resume until successful.

### Privacy Considerations
- A random UUID is generated and stored locally
- No hardware identifiers (IMEI, Android ID, MAC address) are used
- No personal data is collected or transmitted
- Device UUID is only sent to configured backend server
- User can clear app data to reset UUID

### Network Behavior
- Provisioning occurs only when app resumes to foreground
- No background or silent network activity
- HTTP request to hardcoded backend URL (line 380 in MainActivity.kt)
- Retries on every app resume until server list is not empty
- VPN connection never established automatically without user action
- Resilient to temporary network failures and backend downtime

## Source Code Availability

As required by GPL-3.0, the complete source code for VseMoiOnline is available at:
- **Repository**: (To be published)
- **Modified files**: See list above
- **Build instructions**: Use standard Android Studio build process

## Third-Party Dependencies

VseMoiOnline inherits all upstream v2rayNG dependencies, including:
- **Xray-core**: VLESS protocol implementation
- **AndroidLibXrayLite**: Native Xray library (.aar)
- Various Android/Kotlin libraries (see `build.gradle.kts`)

All dependency licenses are preserved and distributed with the app.

## Commercial Use

Per GPL-3.0 license:
- ✅ Commercial use permitted
- ✅ Distribution permitted
- ✅ Modification permitted
- ⚠️ Must disclose source code
- ⚠️ Must preserve GPL-3.0 license
- ⚠️ Modified versions must also be GPL-3.0

## Trademark Notice

- **v2rayNG** is a trademark of its respective owners
- **VseMoiOnline** is the name of this fork
- No trademark infringement intended
- This is an independent fork, not affiliated with v2rayNG project

---
