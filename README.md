# Narusya Phone Bridge

A persistent Android app that bridges your phone to [Hermes Agent](https://github.com/NousResearch/hermes-agent) (Narusya). Gives the agent full device access via HTTP API — SMS, camera, location, files, apps, notifications, and more.

Includes an animated desk pet (Cissia from Zenless Zone Zero, serpent form) that reacts to events.

## Features

- **13 HTTP endpoints** — device info, battery, SMS (read/send), files, apps, notifications, open apps, clipboard, vibrate, toast, location, camera
- **Animated desk pet** — Cissia (serpent form) with 4 states: idle, thinking, excited, sleeping
- **Pet settings** — toggle pet on/off, react to events on/off, reset state
- **Auto-start on boot** — BootReceiver launches the service automatically
- **Tailscale support** — works from anywhere via VPN
- **Runtime permissions** — requests all permissions on first launch
- **Dark theme GUI** — status dashboard with battery, last message, quick actions

## Architecture

```
┌─────────────────┐     HTTP/JSON      ┌──────────────────┐
│  Hermes Agent   │◄──────────────────►│  Android App     │
│  (VM/Server)    │   X-Auth-Token     │  (Phone)         │
└─────────────────┘                    └──────────────────┘
        │                                      │
        │ Tailscale VPN                        │ Tailscale VPN
        └──────────────────────────────────────┘
```

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/device` | GET | Device info (model, Android version, etc.) |
| `/battery` | GET | Battery level and charging status |
| `/texts` | GET | Read SMS messages |
| `/files` | GET | Browse/read files |
| `/apps` | GET | List installed apps |
| `/notify` | POST | Push notification to phone |
| `/open` | POST | Launch an app by package name |
| `/sms` | POST | Send an SMS |
| `/clipboard` | GET/POST | Read/write clipboard |
| `/vibrate` | POST | Vibrate the phone |
| `/toast` | POST | Show a toast message |
| `/location` | GET | Get current GPS location |
| `/call` | POST | Initiate a phone call |
| `/speak` | POST | Speak text via TTS (works during calls) |
| `/call_status` | GET | Check if in a call |
| `/play_audio` | POST | Play base64-encoded audio (WAV/MP3) during calls |

## Authentication

All requests require the `X-Auth-Token` header with the value `narusya-bridge-2026`.

## Build

The app is built via GitHub Actions (no local Android SDK needed).

1. Push to `main` branch
2. GitHub Actions builds the APK automatically
3. Download the APK from the Actions artifacts
4. Install on phone via ADB or sideload

## Install

```bash
# Via ADB (wireless debugging)
adb connect <phone-ip>:<port>
adb install app-debug.apk
```

## Pet States

| State | Animation | Trigger |
|-------|-----------|---------|
| Idle | 8-frame loop | Service running, no active task |
| Thinking | 8-frame loop | Button pressed, waiting for response |
| Excited | 8-frame loop | Notification sent successfully |
| Sleeping | Static darkened frame | Service stopped |

## Pet Settings

Tap the **Settings** button to:
- Toggle pet on/off
- Toggle react-to-events on/off
- Reset pet state

Settings persist across app restarts via SharedPreferences.

## Tech Stack

- **Language:** Kotlin
- **HTTP Server:** NanoHTTPD
- **Build:** Gradle (GitHub Actions)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)

## License

MIT

---

Built with 🐍 by Narusya and Adora.