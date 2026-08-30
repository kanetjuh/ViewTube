#!/usr/bin/env python3

from pathlib import Path
import argparse
import json
import re

REPO = "kanetjuh/ViewTube"
APK_NAME = "ViewTube"

GRADLE = Path("smarttubetv/build.gradle")
MANIFEST = Path("update/viewtube_stable.json")


def read_gradle():
    return GRADLE.read_text(encoding="utf-8")


def get_version_code(text):
    match = re.search(
        r"versionCode\\s+(\\d+)",
        text
    )

    if not match:
        raise RuntimeError("versionCode niet gevonden")

    return int(match.group(1))


def get_version_name(text):
    match = re.search(
        r'versionName\\s+"([^"]+)"',
        text
    )

    if not match:
        raise RuntimeError("versionName niet gevonden")

    return match.group(1)


def prepare(version):
    text = read_gradle()

    old_code = get_version_code(text)
    old_version = get_version_name(text)

    new_code = old_code + 1

    text = re.sub(
        r"versionCode\\s+\\d+",
        f"versionCode {new_code}",
        text,
        count=1
    )

    text = re.sub(
        r'versionName\\s+"[^"]+"',
        f'versionName "{version}"',
        text,
        count=1
    )

    GRADLE.write_text(
        text,
        encoding="utf-8"
    )

    print()
    print("Nieuwe ViewTube versie voorbereid")
    print("-------------------------------")
    print(f"Vorige versie: {old_version}")
    print(f"Nieuwe versie: {version}")
    print(f"Vorige code:   {old_code}")
    print(f"Nieuwe code:   {new_code}")
    print()
    print("Bouw nu met:")
    print("./gradlew assembleStstableRelease")


def publish(version, changes):
    text = read_gradle()

    current_version = get_version_name(text)
    current_code = get_version_code(text)

    if current_version != version:
        raise RuntimeError(
            f"build.gradle staat op {current_version}, "
            f"niet op {version}"
        )

    if MANIFEST.exists():
        data = json.loads(
            MANIFEST.read_text(encoding="utf-8")
        )
    else:
        data = {}

    apk_name = (
        f"{APK_NAME}_stable_"
        f"{version}_universal.apk"
    )

    data["package"] = {
        "downloadUrl": (
            f"https://github.com/{REPO}/"
            f"releases/download/v{version}/"
            f"{apk_name}"
        )
    }

    data[version] = {
        "versionCode": current_code,
        "changelog": changes
    }

    MANIFEST.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    MANIFEST.write_text(
        json.dumps(
            data,
            indent=2,
            ensure_ascii=False
        ) + "\n",
        encoding="utf-8"
    )

    print()
    print("Update manifest bijgewerkt")
    print("--------------------------")
    print(f"Versie:       {version}")
    print(f"VersionCode:  {current_code}")
    print(f"APK:          {apk_name}")
    print()
    print("Na git push kunnen ViewTube apps deze versie zien.")


parser = argparse.ArgumentParser()

sub = parser.add_subparsers(
    dest="command",
    required=True
)

prep = sub.add_parser("prepare")
prep.add_argument("version")

pub = sub.add_parser("publish")
pub.add_argument("version")
pub.add_argument(
    "changes",
    nargs="+"
)

args = parser.parse_args()

if args.command == "prepare":
    prepare(args.version)

elif args.command == "publish":
    publish(
        args.version,
        args.changes
    )
