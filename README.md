# UsageTracker

An Android app with a home-screen widget that shows how much of your **OpenAI (ChatGPT / Codex)** and **Claude (Pro / Max)** subscription usage is left, and when each limit resets.

## Features

- **Multiple accounts**: sign in to any number of OpenAI and Claude accounts. Everything stays on the phone, and sign-in tokens are encrypted with the Android Keystore.
- **Detects the limits each plan has**
  - OpenAI: 5-hour and weekly limits, plus extra per-model limits the service reports (e.g. GPT-5-Codex-Spark).
  - Claude: 5-hour and weekly limits, plus model-specific weekly limits (e.g. Fable).
- **Home-screen widget** that shows everything, one provider (accounts merged), or a single account, in two styles:
  - **Bars**: one row per limit with a progress bar, percentage and time until reset; rows grow to fill the widget.
  - **Rings**: the limits side by side as ring gauges, good for small (4×1, 4×2) widgets.
  - A refresh button that turns into a spinner until the update finishes.
- **Merge accounts**: several accounts of the same provider can be shown as one, using the average usage (one account used up and another unused shows 50% left) and the soonest reset. Each account can be left out of the merge.
- **Remaining or used**: show percentages as what is left or as what is used.
- **Alerts**: get notified when a limit (or any limit) of an account or a merged provider drops below a threshold. Each alert fires once and re-arms after the limit recovers, e.g. after a reset.
- **Background refresh** every 15 minutes to 2 hours (15 minutes is Android's minimum).
- **Languages**: English, Traditional Chinese, Simplified Chinese and Japanese. The app follows the system language by default and can be switched in Settings. On Android 13+ it is also available in the system's per-app language setting.

## Sign-in

| Provider | How |
| --- | --- |
| OpenAI | Browser sign-in with the same OAuth flow as Codex CLI (the account is added automatically), or device-code sign-in. |
| Claude | Browser sign-in with the same OAuth flow as Claude Code (the account is added automatically). If the browser does not return to the app, paste the address-bar URL or the code shown by Claude. |

Sign-in pages open in an incognito Custom Tab by default, so adding a second account doesn't reuse the browser's existing session.

## Privacy

- No server of its own and no analytics: the app only talks to OpenAI's and Anthropic's sign-in and usage endpoints.
- The account list, settings and latest usage are stored in the app's private storage; tokens are in a separate file encrypted with a Keystore key.
- Data is excluded from cloud backup and device transfer.

## Requirements

Android 8.0 (API 26) or later.

## Build

Requires JDK 17 and the Android SDK (platform 36, build-tools 36.0.0).

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # unit tests
```

The release build is signed with the local debug key so it can be sideloaded directly.

## Project structure

```
app/src/main/java/com/mamekyo/usagetracker/
├── data/     models and encrypted storage (Store, SecureBox)
├── net/      OpenAI and Claude OAuth and usage APIs
├── domain/   refresh flow, account merging, alerts, formatting
├── i18n/     app language selection and localized text
├── work/     WorkManager background refresh
├── widget/   Glance home-screen widget and its configuration screen
└── ui/       Jetpack Compose app screens
```

## Disclaimer

This is an unofficial project, not affiliated with or endorsed by OpenAI or Anthropic. Usage data comes from the undocumented endpoints used by Codex (`chatgpt.com/backend-api/wham/usage`) and Claude Code (`api.anthropic.com/api/oauth/usage`), so it may stop working temporarily when those services change.
