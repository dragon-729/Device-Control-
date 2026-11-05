# Contributing to DroidLink

Thanks for helping! Please read the [Code of Conduct](CODE_OF_CONDUCT.md) first.

## Ground rules (non-negotiable)
DroidLink is a **consent-based** tool. Pull requests that add stealth behaviour, hidden persistence, silent permission
grants, keylogging, credential capture, ways around Android's consent dialogs or around `FLAG_SECURE`, or any
remote access path that skips the owner's approval will be closed.

## Getting set up
```bash
git clone https://github.com/hgcheat094-max/DroidLink.git
# Android: open in Android Studio (JDK 17, SDK 35)
gradle :app:testDebugUnitTest :app:lintDebug
# Backend
cd backend && python -m venv .venv && . .venv/bin/activate && pip install -r requirements-dev.txt && pytest
```

## Workflow
1. Open an issue for larger changes first.
2. Branch from `develop`: `feature/<topic>` or `fix/<topic>`.
3. Keep PRs focused; add or update tests (unit tests for logic, backend tests for API rules).
4. Run `ktlint -F "app/src/**/*.kt"` and the test suites before pushing.
5. Fill in the PR template (including the security checklist).

## Code style
* Kotlin official style, 4 spaces, `.editorconfig` is the source of truth.
* Business rules go in `domain` / pure helper objects, not in composables; Android classes stay at the edges
  (`services/`, `data/`), so the logic remains unit-testable.
* Never log or persist secrets, tokens, pairing codes of others, typed text or screen content.
* New remote action? It must pass `SessionGuard`, be listed in `relay_policy.py`, and have a test.
* Backend: services stay framework-independent; validate every input with Pydantic.

## Commit messages
Conventional style is appreciated: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`.

## Releases (maintainers)
1. Update `versionName`/`versionCode` in `app/build.gradle.kts` and add a section to `CHANGELOG.md`.
2. Merge to `main`, tag `vX.Y.Z`, push the tag. `release.yml` builds, tests and publishes - and refuses to publish if tests fail.
