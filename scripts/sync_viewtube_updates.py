#!/usr/bin/env python3

import json
import re
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

OWNER = "kanetjuh"
REPO = "ViewTube"

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "smarttubetv" / "build.gradle"
UPDATE_DIR = ROOT / "update"

UPDATE_DIR.mkdir(parents=True, exist_ok=True)


def http_json(url):
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "ViewTube-Updater-Sync",
            "Accept": "application/vnd.github+json"
        }
    )

    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def http_text(url):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "ViewTube-Updater-Sync"}
    )

    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8")


def parse_gradle(text):
    version_name = re.search(
        r'versionName\s+"([^"]+)"',
        text
    )

    version_code = re.search(
        r'versionCode\s+(\d+)',
        text
    )

    if not version_name or not version_code:
        raise RuntimeError(
            "versionName/versionCode konden niet worden gelezen"
        )

    return version_name.group(1), int(version_code.group(1))


local_version_name, local_version_code = parse_gradle(
    GRADLE.read_text()
)

print(
    f"Lokale app: {local_version_name} "
    f"(versionCode {local_version_code})"
)


# ------------------------------------------------------------
# GitHub releases ophalen
# ------------------------------------------------------------

api_url = (
    f"https://api.github.com/repos/"
    f"{OWNER}/{REPO}/releases?per_page=50"
)

releases = http_json(api_url)

if not isinstance(releases, list):
    releases = []


def assets_for(release):
    result = {}

    for asset in release.get("assets", []):
        name = asset.get("name", "")
        url = asset.get("browser_download_url")

        if name and url:
            result[name] = url

    return result


def select_release(kind):
    """
    stable:
      - geen draft
      - geen prerelease
      - moet ViewTube_stable APK bevatten

    beta:
      - geen draft
      - prerelease mag
      - moet ViewTube_beta APK bevatten
    """

    prefix = f"ViewTube_{kind}_"

    for release in releases:
        if release.get("draft"):
            continue

        if kind == "stable" and release.get("prerelease"):
            continue

        assets = assets_for(release)

        found = any(
            name.startswith(prefix) and name.endswith(".apk")
            for name in assets
        )

        if found:
            return release

    return None


def version_from_release(release):
    tag = release.get("tag_name")

    if not tag:
        raise RuntimeError("Release heeft geen tag_name")

    encoded_tag = urllib.parse.quote(tag, safe="")

    gradle_url = (
        f"https://raw.githubusercontent.com/"
        f"{OWNER}/{REPO}/{encoded_tag}/"
        f"smarttubetv/build.gradle"
    )

    try:
        text = http_text(gradle_url)
        return parse_gradle(text)
    except Exception as e:
        print(
            f"WAARSCHUWING: versie bij tag {tag} "
            f"niet uit build.gradle kunnen lezen: {e}"
        )

        guessed = tag

        if guessed.startswith("v"):
            guessed = guessed[1:]

        guessed = guessed.replace("-stable", "")
        guessed = guessed.replace("-beta", "")

        return guessed, local_version_code


def release_changelog(release, version_name):
    body = release.get("body") or ""

    result = []

    for line in body.splitlines():
        line = line.strip()

        if not line:
            continue

        if line.startswith("##"):
            continue

        if line.startswith("|"):
            continue

        if line.startswith("- "):
            line = line[2:].strip()
        elif line.startswith("* "):
            line = line[2:].strip()
        else:
            continue

        if line and len(line) <= 300:
            result.append(line)

        if len(result) >= 30:
            break

    if not result:
        result = [f"ViewTube {version_name}"]

    return result


def make_package_from_assets(kind, version_name, tag, assets):
    prefix = f"ViewTube_{kind}_{version_name}_"

    universal = None
    arm64 = None
    armv7 = None
    x86 = None

    for name, url in assets.items():
        lower = name.lower()

        if not name.startswith(f"ViewTube_{kind}_"):
            continue

        if not lower.endswith(".apk"):
            continue

        if "_universal.apk" in lower:
            universal = url
        elif "_arm64-v8a.apk" in lower:
            arm64 = url
        elif "_armeabi-v7a.apk" in lower:
            armv7 = url
        elif "_x86.apk" in lower:
            x86 = url

    # Als er nog geen daadwerkelijke release bestaat:
    # gebruik de verwachte releasepaden.
    base = (
        f"https://github.com/{OWNER}/{REPO}/"
        f"releases/download/{tag}"
    )

    if universal is None:
        universal = (
            f"{base}/"
            f"ViewTube_{kind}_{version_name}_universal.apk"
        )

    if arm64 is None:
        arm64 = (
            f"{base}/"
            f"ViewTube_{kind}_{version_name}_arm64-v8a.apk"
        )

    if armv7 is None:
        armv7 = (
            f"{base}/"
            f"ViewTube_{kind}_{version_name}_armeabi-v7a.apk"
        )

    if x86 is None:
        x86 = (
            f"{base}/"
            f"ViewTube_{kind}_{version_name}_x86.apk"
        )

    return {
        "downloadUrl": universal,

        "downloadUrlList": [
            universal
        ],

        "downloadUrlList_arm64-v8a": [
            arm64,
            universal
        ],

        "downloadUrlList_armeabi-v7a": [
            armv7,
            universal
        ],

        "downloadUrlList_x86": [
            x86,
            universal
        ]
    }


def build_manifest(kind):
    release = select_release(kind)

    if release is None:
        print(
            f"Geen {kind} GitHub Release gevonden. "
            f"Manifest gebruikt huidige lokale versie."
        )

        version_name = local_version_name
        version_code = local_version_code

        if kind == "stable":
            tag = f"v{version_name}"
        else:
            tag = f"v{version_name}-beta"

        assets = {}

        changelog = [
            f"ViewTube {version_name}"
        ]

    else:
        tag = release["tag_name"]

        version_name, version_code = version_from_release(
            release
        )

        assets = assets_for(release)

        changelog = release_changelog(
            release,
            version_name
        )

        print(
            f"Laatste {kind} release: "
            f"{tag} -> {version_name} "
            f"(versionCode {version_code})"
        )

    package = make_package_from_assets(
        kind,
        version_name,
        tag,
        assets
    )

    # LET OP:
    # SmartTube AppVersionChecker verwacht alleen:
    #   "package"
    #   versienamen
    #
    # Daarom geen metadata keys toevoegen.
    manifest = {
        "package": package,

        version_name: {
            "versionCode": version_code,
            "changelog": changelog,
            "changelog_nl": changelog
        }
    }

    return manifest


stable_manifest = build_manifest("stable")
beta_manifest = build_manifest("beta")


def write_manifest(path, data):
    path.write_text(
        json.dumps(
            data,
            indent=2,
            ensure_ascii=False
        ) + "\n"
    )

    print(f"Geschreven: {path.relative_to(ROOT)}")


write_manifest(
    UPDATE_DIR / "viewtube_stable.json",
    stable_manifest
)

write_manifest(
    UPDATE_DIR / "viewtube_beta.json",
    beta_manifest
)


# ------------------------------------------------------------
# Uitleg huidige vs remote versie
# ------------------------------------------------------------

stable_versions = [
    (key, value["versionCode"])
    for key, value in stable_manifest.items()
    if key != "package"
]

if stable_versions:
    remote_name, remote_code = max(
        stable_versions,
        key=lambda x: x[1]
    )

    print()
    print(
        f"Geïnstalleerde/build versie: "
        f"{local_version_name} ({local_version_code})"
    )

    print(
        f"Update-manifest versie: "
        f"{remote_name} ({remote_code})"
    )

    if remote_code > local_version_code:
        print("RESULTAAT: update beschikbaar")
    elif remote_code == local_version_code:
        print("RESULTAAT: huidige versie is latest")
    else:
        print("RESULTAAT: lokale build is nieuwer dan GitHub")
