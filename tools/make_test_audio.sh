#!/bin/bash
# 用系统 TTS 生成测试语音（中英各一段），供 tools/test_pipeline.py 使用
set -e
cd "$(dirname "$0")/.."
mkdir -p data/test

ZH_TEXT="大家好，欢迎参加今天的项目评审会。今天下午三点我们在大会议室讨论新版本的方案，请把上周的原型带过来。如果还有问题，我们明天上午再单独沟通。"
EN_TEXT="Good morning everyone. Today we will review the new design and discuss the timeline for the next release. Please make sure your feedback is submitted before Friday."

say -v Tingting -o data/test/zh.wav --file-format=WAVE --data-format=LEI16@16000 "$ZH_TEXT" 2>/dev/null \
  || say -v "Ting-Ting" -o data/test/zh.wav --file-format=WAVE --data-format=LEI16@16000 "$ZH_TEXT"
say -v Samantha -o data/test/en.wav --file-format=WAVE --data-format=LEI16@16000 "$EN_TEXT"

echo "已生成：data/test/zh.wav, data/test/en.wav"
ls -lh data/test
