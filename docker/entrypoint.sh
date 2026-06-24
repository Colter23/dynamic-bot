#!/bin/sh
set -eu

APP_USER="${APP_USER:-dynamicbot}"
APP_UID="${APP_UID:-1000}"
APP_GID="${APP_GID:-1000}"
APP_UMASK="${APP_UMASK:-0022}"
FIX_VOLUME_PERMISSIONS="${FIX_VOLUME_PERMISSIONS:-auto}"
APP_RUNTIME_DIR="${APP_RUNTIME_DIR:-/app/.runtime}"
LOG_DIR="${LOG_DIR:-/app/logs}"
HOME="$APP_RUNTIME_DIR/home"
XDG_CACHE_HOME="$APP_RUNTIME_DIR/cache"
TMPDIR="$APP_RUNTIME_DIR/tmp"
JAVA_RUNTIME_OPTS="-Duser.home=$HOME -Djava.io.tmpdir=$TMPDIR"
JAVA_OPTS="${JAVA_OPTS:-} $JAVA_RUNTIME_OPTS"
export HOME XDG_CACHE_HOME TMPDIR JAVA_OPTS LOG_DIR

case "$APP_UID" in
  ''|*[!0-9]*)
    echo "错误: APP_UID 必须是数字，当前值：$APP_UID" >&2
    exit 1
    ;;
esac

case "$APP_GID" in
  ''|*[!0-9]*)
    echo "错误: APP_GID 必须是数字，当前值：$APP_GID" >&2
    exit 1
    ;;
esac

case "$FIX_VOLUME_PERMISSIONS" in
  auto|always|false) ;;
  *)
    echo "错误: FIX_VOLUME_PERMISSIONS 只能是 auto、always 或 false，当前值：$FIX_VOLUME_PERMISSIONS" >&2
    exit 1
    ;;
esac

if [ "$(id -u)" != "0" ]; then
  echo "提示: 容器不是以 root 启动，跳过挂载目录权限初始化。"
  umask "$APP_UMASK"
  exec "$@"
fi

if ! id "$APP_USER" >/dev/null 2>&1; then
  echo "错误: 容器用户不存在：$APP_USER" >&2
  exit 1
fi

current_gid="$(id -g "$APP_USER")"
if [ "$current_gid" != "$APP_GID" ]; then
  if getent group "$APP_GID" >/dev/null 2>&1; then
    usermod -g "$APP_GID" "$APP_USER"
  else
    groupmod -o -g "$APP_GID" "$APP_USER"
    usermod -g "$APP_GID" "$APP_USER"
  fi
fi

current_uid="$(id -u "$APP_USER")"
if [ "$current_uid" != "$APP_UID" ]; then
  usermod -o -u "$APP_UID" "$APP_USER"
fi

ensure_writable_dir() {
  dir="$1"
  mkdir -p "$dir"

  if [ "$FIX_VOLUME_PERMISSIONS" = "always" ]; then
    chown -R "$APP_UID:$APP_GID" "$dir"
  elif [ "$FIX_VOLUME_PERMISSIONS" = "auto" ] && ! gosu "$APP_USER" test -w "$dir"; then
    echo "提示: $dir 对 $APP_UID:$APP_GID 不可写，正在修复权限..."
    chown -R "$APP_UID:$APP_GID" "$dir"
  fi

  if ! gosu "$APP_USER" test -w "$dir"; then
    echo "错误: $dir 不可写。请检查宿主机目录权限，或设置 APP_UID/APP_GID 与宿主机目录所有者一致。" >&2
    exit 1
  fi
}

ensure_runtime_dir() {
  dir="$1"
  mkdir -p "$dir"
  chown -R "$APP_UID:$APP_GID" "$dir"

  if ! gosu "$APP_USER" test -w "$dir"; then
    echo "错误: $dir 不可写。请检查容器运行时目录权限。" >&2
    exit 1
  fi
}

for dir in \
  "$APP_RUNTIME_DIR" \
  "$HOME" \
  "$XDG_CACHE_HOME" \
  "$TMPDIR"
do
  ensure_runtime_dir "$dir"
done

for dir in \
  /app/data \
  /app/config \
  /app/plugins \
  "$LOG_DIR" \
  /app/data/images/source \
  /app/data/images/draw \
  /app/data/plugins \
  /app/data/videos \
  /app/data/login-qr \
  /app/plugins/.downloads
do
  ensure_writable_dir "$dir"
done

if [ ! -f /app/plugins/catalog.json ] && [ -f /app/defaults/plugins/catalog.json ]; then
  cp /app/defaults/plugins/catalog.json /app/plugins/catalog.json
  chown "$APP_UID:$APP_GID" /app/plugins/catalog.json
fi

umask "$APP_UMASK"
exec gosu "$APP_USER" "$@"
