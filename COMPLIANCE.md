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
  - Added `onNewIntent()` method (lines 200-204)
  - Added `handleMagicLink()` method (lines 210-220)
  - Added `getOrCreateDeviceId()` method (lines 226-239)
  - Added `fetchAndImportConfig()` method (lines 245-287)
  - Added `checkAndAutoProvision()` method (lines 372-393)
  - Modified `onCreate()` to call `handleMagicLink(intent)` (line 197)
  - Modified `onResume()` to call `checkAndAutoProvision()` (line 365)

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
