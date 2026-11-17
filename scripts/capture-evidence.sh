#!/usr/bin/env bash
# Task 39 — 真实运行截图采集入口（可复现）。
#
# 前置：先用真实服务启动器把应用起在 8080（真 PostgreSQL + 真 dcm4che SCP）：
#   mvn -o -q test-compile
#   mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/runtime-cp.txt -Dmdep.includeScope=test
#   java -cp "target/classes:target/test-classes:$(cat target/runtime-cp.txt)" \
#        com.crosshospital.imaging.tools.RealRuntimeLauncher &
#   等待 target/evidence-runtime/READY 出现
# 然后运行本脚本；采集完成后记得停掉后台服务。
#
# 产物：docs/screenshots/01-swagger-ui.png（真实浏览器渲染）
#       docs/screenshots/02-handshake-curl.png（真实 curl 会话）
#       docs/screenshots/03-fetch-task-status.png（真实五态推进 curl 会话）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ ! -f target/evidence-runtime/READY ]; then
  echo "[evidence] ERROR: 服务未就绪，缺少 target/evidence-runtime/READY" >&2
  exit 1
fi

PY=""
for candidate in \
  /opt/homebrew/opt/python@3.10/bin/python3.10 \
  "$(command -v python3 || true)"; do
  if [ -n "$candidate" ] && "$candidate" -c 'import playwright' >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
if [ -z "$PY" ]; then
  echo "[evidence] ERROR: 找不到带 Playwright 的 Python 解释器" >&2
  exit 1
fi

echo "[evidence] using $PY"
"$PY" scripts/capture_evidence.py
echo "[evidence] 三张截图已写入 docs/screenshots/"
ls -l docs/screenshots/
