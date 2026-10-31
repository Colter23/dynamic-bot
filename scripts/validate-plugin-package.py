"""Validate locked plugin identities and API compatibility before packaging."""

import argparse
import json
from pathlib import Path
import re
import zipfile

import yaml


def validate_package(lock_path, plugin_dir):
    lock = json.loads(Path(lock_path).read_text(encoding="utf-8"))
    api_version = lock.get("apiVersion", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+", api_version):
        raise ValueError("Lock file must declare apiVersion")
    plugins = lock.get("plugins", [])
    if not plugins:
        raise ValueError("Plugin lock file has no plugins")
    ids = set()
    assets = set()
    for plugin in plugins:
        plugin_id, version, asset = (plugin[key] for key in ("id", "version", "asset"))
        if plugin_id in ids or asset in assets:
            raise ValueError(f"Duplicate plugin id or asset: {plugin_id}")
        if Path(asset).name != asset or "/" in asset or "\\" in asset or not asset.endswith(".jar"):
            raise ValueError(f"Invalid plugin asset: {asset}")
        if plugin["tag"] != f"v{version}":
            raise ValueError(f"Plugin tag/version mismatch: {plugin_id}")
        ids.add(plugin_id)
        assets.add(asset)
        with zipfile.ZipFile(Path(plugin_dir) / asset) as jar:
            entries = [entry for entry in jar.infolist() if entry.filename == "plugin.yml"]
            if len(entries) != 1 or entries[0].file_size > 65536:
                raise ValueError(f"Missing, duplicate or oversized plugin.yml: {asset}")
            descriptor = yaml.safe_load(jar.read(entries[0]).decode("utf-8"))
        if not isinstance(descriptor, dict):
            raise ValueError(f"Invalid plugin.yml: {asset}")
        for key, expected in (("id", plugin_id), ("version", version), ("apiVersion", api_version)):
            if descriptor.get(key) != expected:
                raise ValueError(f"{asset}: {key} expected {expected}, got {descriptor.get(key)}")
    actual_assets = {file.name for file in Path(plugin_dir).glob("*.jar")}
    if actual_assets != assets:
        raise ValueError(f"Unexpected plugin files: {sorted(actual_assets - assets)}")
    return len(plugins)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lock_file", type=Path)
    parser.add_argument("plugin_dir", type=Path)
    args = parser.parse_args()
    try:
        count = validate_package(args.lock_file, args.plugin_dir)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, yaml.YAMLError) as error:
        parser.exit(1, f"Plugin package validation failed: {error}\n")
    print(f"Validated {count} official plugins")
