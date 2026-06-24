#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <app-version> <lock-file> <output-dir>" >&2
  exit 2
fi

app_version="$1"
lock_file="$2"
output_dir="$3"

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

for required_command in python3 curl zip sha256sum dirname basename mkdir rm; do
  require_command "$required_command"
done

if [[ ! -f "$lock_file" ]]; then
  echo "Plugin lock file not found: $lock_file" >&2
  exit 1
fi

lock_file="$(cd "$(dirname "$lock_file")" && pwd)/$(basename "$lock_file")"
output_dir="$(mkdir -p "$output_dir" && cd "$output_dir" && pwd)"
work_dir="${RUNNER_TEMP:-/tmp}/dynamic-bot-official-plugins-${app_version}"
package_dir="$work_dir/package"
download_list="$work_dir/downloads.tsv"
artifact_name="dynamic-bot-v${app_version}-official-plugins.zip"

rm -rf "$work_dir"
mkdir -p "$package_dir/plugins"

python3 - "$lock_file" "$download_list" "$package_dir/MANIFEST.json" "$app_version" <<'PY'
import json
import sys
from pathlib import Path

lock_path = Path(sys.argv[1])
download_list_path = Path(sys.argv[2])
manifest_path = Path(sys.argv[3])
app_version = sys.argv[4]

lock = json.loads(lock_path.read_text(encoding="utf-8"))
plugins = lock.get("plugins", [])
if not plugins:
    raise SystemExit("official plugin lock file has no plugins")

manifest = {
    "schemaVersion": 1,
    "appVersion": app_version,
    "description": "dynamic-bot 官方插件合集包，版本以此清单为准。",
    "plugins": [],
}

with download_list_path.open("w", encoding="utf-8", newline="\n") as downloads:
    for plugin in plugins:
        repo = plugin["repo"]
        tag = plugin["tag"]
        asset = plugin["asset"]
        download_url = f"https://github.com/{repo}/releases/download/{tag}/{asset}"
        downloads.write(f"{plugin['id']}\t{asset}\t{download_url}\n")
        manifest["plugins"].append({
            "id": plugin["id"],
            "name": plugin["name"],
            "repo": repo,
            "version": plugin["version"],
            "tag": tag,
            "asset": asset,
            "downloadUrl": download_url,
        })

manifest_path.write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

cat > "$package_dir/README.txt" <<EOF
dynamic-bot ${app_version} 官方插件合集包

安装方式：
1. 解压本文件。
2. 将 plugins/ 下的 JAR 复制到 dynamic-bot 运行目录的 plugins/ 下。
3. 重启 dynamic-bot，或在 Web 后台刷新插件。

说明：
- 本包只包含官方插件，不包含私有插件或实验插件。
- 插件版本以 MANIFEST.json 为准。
- 后续单独升级插件时，推荐在 Web 后台的插件页面更新。
EOF

while IFS=$'\t' read -r plugin_id asset download_url; do
  echo "Downloading official plugin: ${plugin_id} (${asset})"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
    -o "$package_dir/plugins/$asset" \
    "$download_url"
done < "$download_list"

(
  cd "$package_dir"
  sha256sum plugins/*.jar > SHA256SUMS.txt
  zip -qr "$output_dir/$artifact_name" plugins README.txt MANIFEST.json SHA256SUMS.txt
)

echo "Official plugin package written to $output_dir/$artifact_name"
