#!/bin/bash
# 命令行启动：./run.sh
set -e
cd "$(dirname "$0")"
if [ ! -x ".venv/bin/python" ]; then
  uv venv --python 3.12 .venv
  uv pip install --python .venv/bin/python -r requirements.txt
fi
exec .venv/bin/python -m app.server
