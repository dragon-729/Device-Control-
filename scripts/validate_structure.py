#!/usr/bin/env python3
"""Fails when a required project file is missing (used by CI)."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQUIRED = """
README.md LICENSE CONTRIBUTING.md CODE_OF_CONDUCT.md SECURITY.md CHANGELOG.md .gitignore settings.gradle.kts build.gradle.kts
gradle.properties gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties
app/build.gradle.kts app/proguard-rules.pro app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml app/src/main/res/drawable-nodpi/droidlink_logo.png
app/src/main/java/com/droidlink/MainActivity.kt app/src/main/java/com/droidlink/DroidLinkApp.kt
app/src/main/java/com/droidlink/data app/src/main/java/com/droidlink/domain app/src/main/java/com/droidlink/presentation
app/src/main/java/com/droidlink/navigation app/src/main/java/com/droidlink/services app/src/main/java/com/droidlink/security
app/src/main/java/com/droidlink/network app/src/main/java/com/droidlink/utils
backend/requirements.txt backend/README.md backend/app/main.py backend/app/api backend/app/core backend/app/models backend/app/services
docs/INSTALLATION.md docs/ARCHITECTURE.md docs/API.md docs/SECURITY.md docs/CONTRIBUTING.md docs/assets/logo.png
.github/workflows/android-build.yml .github/workflows/backend-tests.yml .github/workflows/release.yml
.github/ISSUE_TEMPLATE .github/PULL_REQUEST_TEMPLATE.md
""".split()

missing = [p for p in REQUIRED if not (ROOT / p).exists()]
for p in missing:
    print(f"MISSING: {p}")
print("structure OK" if not missing else f"{len(missing)} required path(s) missing")
sys.exit(1 if missing else 0)
