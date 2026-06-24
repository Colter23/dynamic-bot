#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <version> <jar-file> <output-dir>" >&2
  exit 2
fi

version="$1"
jar_file="$2"
output_dir="$3"
jre_major="${JRE_MAJOR:-21}"
include_jre_packages="${INCLUDE_JRE_PACKAGES:-true}"

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

required_commands=(dirname basename mkdir rm cp printf chmod zip tar)
if [[ "$include_jre_packages" == "true" ]]; then
  required_commands+=(curl unzip find head)
fi
for required_command in "${required_commands[@]}"; do
  require_command "$required_command"
done

if [[ ! -f "$jar_file" ]]; then
  echo "Jar file not found: $jar_file" >&2
  exit 1
fi

jar_file="$(cd "$(dirname "$jar_file")" && pwd)/$(basename "$jar_file")"
output_dir="$(mkdir -p "$output_dir" && cd "$output_dir" && pwd)"
work_dir="${RUNNER_TEMP:-/tmp}/dynamic-bot-package-${version}"
package_root="dynamic-bot"
artifact_prefix="dynamic-bot-v${version}"

rm -rf "$work_dir"
mkdir -p "$work_dir"

write_readme() {
  local root="$1"
  cat > "$root/README.txt" <<EOF
dynamic-bot ${version}

启动方式：
- Windows：双击 start.bat。
- Linux：执行 ./start.sh。

目录说明：
- config/：主程序和插件配置。首次启动会自动生成 config/main.yml。
- plugins/：插件目录，也可以在 Web 后台安装插件。
- data/：数据库、插件数据、图片缓存和字体目录。
- data/fonts/：自定义绘图字体。
- logs/：本地日志。

Java 说明：
- 不带 JRE 的包需要本机已安装 Java 17 或更高版本。
- 带 JRE 的包内置 Java ${jre_major}，无需额外安装 Java。

升级说明：
- 通常只需要替换根目录下的 dynamic-bot.jar。
- 请保留 config/、data/、plugins/ 和 logs/，不要用新包覆盖这些目录里的已有内容。
EOF
}

write_windows_launcher() {
  local root="$1"
  cat > "$root/start.bat" <<'EOF'
@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "JAVA_EXE=java"
if exist "%~dp0runtime\jre\bin\java.exe" (
  set "JAVA_EXE=%~dp0runtime\jre\bin\java.exe"
)

"%JAVA_EXE%" %JAVA_OPTS% -jar "%~dp0dynamic-bot.jar" %*
if errorlevel 1 (
  echo.
  echo dynamic-bot 已退出，退出码：%errorlevel%
  pause
)
EOF
}

write_linux_launcher() {
  local root="$1"
  cat > "$root/start.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

java_bin="java"
if [[ -x "$PWD/runtime/jre/bin/java" ]]; then
  java_bin="$PWD/runtime/jre/bin/java"
fi

exec "$java_bin" ${JAVA_OPTS:-} -jar "$PWD/dynamic-bot.jar" "$@"
EOF
  chmod +x "$root/start.sh"
}

prepare_common_root() {
  local root="$1"
  mkdir -p "$root/config" "$root/plugins" "$root/data/fonts" "$root/logs"
  cp "$jar_file" "$root/dynamic-bot.jar"
  printf '%s\n' "$version" > "$root/VERSION"
  write_readme "$root"
}

download_windows_jre() {
  local dest="$1"
  local archive="$work_dir/temurin-${jre_major}-windows-x64-jre.zip"
  local extract_dir="$work_dir/temurin-${jre_major}-windows-x64-jre"

  if [[ -d "$dest" ]]; then
    return
  fi

  echo "Downloading Windows x64 JRE ${jre_major}..."
  curl -fsSL \
    "https://api.adoptium.net/v3/binary/latest/${jre_major}/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk" \
    -o "$archive"
  mkdir -p "$extract_dir"
  unzip -q "$archive" -d "$extract_dir"
  local extracted
  extracted="$(find "$extract_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  if [[ -z "$extracted" || ! -d "$extracted" ]]; then
    echo "Cannot find extracted Windows JRE directory in $extract_dir" >&2
    exit 1
  fi
  mkdir -p "$dest"
  cp -a "$extracted"/. "$dest"/
}

download_linux_jre() {
  local dest="$1"
  local archive="$work_dir/temurin-${jre_major}-linux-x64-jre.tar.gz"
  local extract_dir="$work_dir/temurin-${jre_major}-linux-x64-jre"

  if [[ -d "$dest" ]]; then
    return
  fi

  echo "Downloading Linux x64 JRE ${jre_major}..."
  curl -fsSL \
    "https://api.adoptium.net/v3/binary/latest/${jre_major}/ga/linux/x64/jre/hotspot/normal/eclipse?project=jdk" \
    -o "$archive"
  mkdir -p "$extract_dir"
  tar -xzf "$archive" -C "$extract_dir" --strip-components=1
  mkdir -p "$dest"
  cp -a "$extract_dir"/. "$dest"/
}

package_windows() {
  local suffix="$1"
  local with_jre="$2"
  local root="$work_dir/${suffix}/${package_root}"
  prepare_common_root "$root"
  write_windows_launcher "$root"
  if [[ "$with_jre" == "true" ]]; then
    download_windows_jre "$root/runtime/jre"
  fi
  (cd "$work_dir/${suffix}" && zip -qr "$output_dir/${artifact_prefix}-${suffix}.zip" "$package_root")
}

package_linux() {
  local suffix="$1"
  local with_jre="$2"
  local root="$work_dir/${suffix}/${package_root}"
  prepare_common_root "$root"
  write_linux_launcher "$root"
  if [[ "$with_jre" == "true" ]]; then
    download_linux_jre "$root/runtime/jre"
  fi
  tar -C "$work_dir/${suffix}" -czf "$output_dir/${artifact_prefix}-${suffix}.tar.gz" "$package_root"
}

package_windows "windows-x64" "false"
package_linux "linux-x64" "false"

if [[ "$include_jre_packages" == "true" ]]; then
  package_windows "windows-x64-jre" "true"
  package_linux "linux-x64-jre" "true"
fi

echo "Release packages written to $output_dir"
