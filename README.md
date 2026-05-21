# HBX Short — Android App

Flutter Android wrapper for [HBX Short](https://xenox-short-production.up.railway.app)

## Features
- Full WebView of the web app
- System overlay floating bubble (works outside the app)
- JavaScript Bridge (`window.XenoxAndroid`)
- Firebase FCM push notifications (heads-up style)
- Splash screen + hardware acceleration
- Google Sheet & Local Sheet support from bubble

## Package
`com.hbx.shortapp`

## Build

### Prerequisites
- Flutter 3.24+
- Android Studio / SDK
- Java 17

### Steps
```bash
flutter pub get
flutter build apk --debug
```

APK location: `build/app/outputs/flutter-apk/app-debug.apk`

## GitHub Actions
Push to `main` branch → APK auto-builds → available in Actions → Artifacts

## Bubble Usage
1. Open app → Float Sheet Setup → Configure columns
2. Tap **Start Bubble** → grant overlay permission → bubble appears
3. **Tap bubble** → expands with column buttons + 2FA
4. **Tap column** → clipboard text saved to sheet
5. **Hold column** → deletes last entry
6. **Tap ☯** → generates TOTP from clipboard secret

## Permissions
- `SYSTEM_ALERT_WINDOW` — overlay bubble
- `FOREGROUND_SERVICE` — keep bubble running
- `POST_NOTIFICATIONS` — FCM notifications
- `INTERNET` — web app + Google Sheet API
