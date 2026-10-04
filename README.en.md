# Monitor

A self-hosted companion for Codex and Claude Code across multiple computers. View tasks, final responses, attachments and available usage information from a native Android app, a SwiftUI macOS client or a browser.

[中文介绍](README.md) · [Android releases](https://github.com/Makabaka-zxh/agent-monitor/releases/latest)

## Highlights

- Multiple Windows / Mac collectors, task filtering, active and archived views.
- Explicit pairing and per-device result/file/reply permissions.
- Native Android UI, profile settings, usage charts and live-notification content previews.
- In-app GitHub release checks, bounded APK downloads, SHA-256 and signer validation, followed by the Android package installer.
- Python service with private SQLite state; optional Google OAuth using your own credentials.

## Run locally

Use Python 3.12 or newer. Create a virtual environment, install `requirements.txt`, then run `python -m agent_monitor`. Open `http://127.0.0.1:8766` on that computer to initialize the account. Configure a certificate-valid HTTPS endpoint before pairing the Android app or remote computers. See [deployment](docs/DEPLOYMENT.md), [collectors](docs/CONNECTORS.md) and [build instructions](docs/BUILDING.md).

## Status

1.0.0 is the first public release. Android requires API 26+; macOS source requires macOS 14+. No notarized Mac binary or native iPhone client is included. OEM live-notification support varies; official Xiaomi island/rear-screen authorization is still pending. The content preview is not an OEM rendering guarantee. Earlier network tests observed startup latency and intermittent TLS/timeouts whose root cause remains open. Usage is best-effort, not a billing record.

This repository contains no deployment account, server, private database or signing key. MIT applies to original project code; fonts and vendored dependencies retain their own terms. This application uses MiSans, provided by Xiaomi. Read [third-party notices](THIRD_PARTY_NOTICES.md) before redistribution. This is an independent community project, not an official product of the referenced tool or device vendors.
