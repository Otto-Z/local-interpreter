#!/bin/bash
# 双击即可启动本地同传（首次运行会自动安装依赖）
set -e
cd "$(dirname "$0")"

if [ ! -x ".venv/bin/python" ]; then
  echo "首次启动：正在创建 Python 环境并安装依赖…"
  if ! command -v uv >/dev/null 2>&1; then
    echo
    echo "缺少 uv。请先在终端运行： brew install uv"
    echo
    read -n 1 -s -r -p "按任意键退出"
    exit 1
  fi
  uv venv --python 3.12 .venv
  uv pip install --python .venv/bin/python -r requirements.txt
fi

if ! command -v ollama >/dev/null 2>&1; then
  echo "提示：未安装 Ollama，翻译功能不可用。可运行 brew install ollama"
fi

exec .venv/bin/python -m app.server
