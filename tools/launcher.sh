#!/bin/bash
# .app 的主启动器：启动本地服务，就绪后打开应用窗口
set -u

RES="$(cd "$(dirname "$0")/../Resources" && pwd)"
DATA="$HOME/Library/Application Support/本地同传"
mkdir -p "$DATA"

export LOCAL_INTERPRETER_DATA="$DATA"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
export PYTHONPATH="$RES/site-packages"
export PYTHONUNBUFFERED=1
export NO_BROWSER=1

PORT="${PORT:-8765}"
URL="http://127.0.0.1:$PORT"

open_ui() {
  for CHROME in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
                "$HOME/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; do
    if [ -x "$CHROME" ]; then
      nohup "$CHROME" --app="$URL" \
        --user-data-dir="$DATA/chrome-profile" \
        --no-first-run --no-default-browser-check \
        --window-size=1320,880 >/dev/null 2>&1 &
      return 0
    fi
  done
  open "$URL"
}

# 已经有实例在运行：直接打开窗口
if curl -s -m 1 "$URL/api/health" >/dev/null 2>&1; then
  open_ui
  exit 0
fi

cd "$RES/source" || exit 1

# 服务就绪后打开界面（NO_UI=1 时只启动服务）
if [ "${NO_UI:-0}" != "1" ]; then
  (
    for _ in $(seq 1 240); do
      if curl -s -m 1 "$URL/api/health" >/dev/null 2>&1; then
        open_ui
        exit 0
      fi
      sleep 0.5
    done
  ) >/dev/null 2>&1 &
fi

exec "$RES/python/bin/python3.12" -m app.server >>"$DATA/app.log" 2>&1
