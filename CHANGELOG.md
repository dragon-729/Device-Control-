# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-11-05

### Added
- Android app (Kotlin, Compose, Material 3, MVVM + Clean Architecture): splash, onboarding, device registration,
  dashboard, pairing (code / QR / manual), connected devices, remote screen viewer, file transfer, device information,
  connection history, security settings, application settings, about.
- MediaProjection screen streaming with adaptive quality and rotation support.
- AccessibilityService remote input: tap, double-tap, long-press, swipe, scroll, global navigation, text and key input, app launch.
- SAF-based bidirectional file transfer with SHA-256 verification, progress and cancellation.
- Security: Keystore-encrypted credentials, short-lived tokens, per-session approval, biometric re-authentication,
  `SessionGuard`, emergency disconnect, persistent notification and banner.
- FastAPI relay: registration, token auth, pairing, sessions, WebSocket relay with scope policy, rate limiting, audit log, OpenAPI docs.
- GitHub Actions: Android build/test/lint, backend tests, release workflow.
- Documentation and unit tests (Android + backend).

### Known limitations
- Android sources were not compiled in the generation environment; first CI run may require small fixes.
- No end-to-end encryption between peers yet (TLS to the relay only).
