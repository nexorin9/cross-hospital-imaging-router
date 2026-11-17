#!/usr/bin/env python3
"""Task 39 — 采集三张真实运行截图。

用法（服务已由 RealRuntimeLauncher 起在 8080 时）：

    /opt/homebrew/opt/python@3.10/bin/python3.10 scripts/capture_evidence.py

三张图的内容来源：
  01-swagger-ui.png     真实浏览器渲染 http://127.0.0.1:8080/swagger-ui.html
  02-handshake-curl.png 真实 curl 会话输出（握手 health RPC 的 JSON 响应）
  03-fetch-task-status.png 真实 curl 会话输出（POST fetch → 轮询五态 → notified → 状态审计）

02/03 是把**真实 curl 命令与真实 stdout** 按终端配色逐字渲染成 PNG：
不写死任何 JSON、不美化字段、不替换数值。命令用 `python3 -m json.tool`
做缩进，仅改变空白字符；响应内容未做任何改写。
"""

from __future__ import annotations

import html
import json
import subprocess
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1:8080"
RUNTIME = ROOT / "target" / "evidence-runtime"
TRANSCRIPTS = RUNTIME / "transcripts"
SHOTS = ROOT / "docs" / "screenshots"

TERMINAL_CSS = """
<style>
  * { box-sizing: border-box; }
  body { margin: 0; background: #0d1117; color: #c9d1d9;
         font: 13px/1.62 "SFMono-Regular", Menlo, Monaco, Consolas, monospace; }
  .window { width: 1180px; margin: 0 auto; padding: 18px 0 30px; }
  .bar { background: #161b22; border: 1px solid #30363d; border-bottom: none;
         border-radius: 10px 10px 0 0; padding: 10px 16px; color: #8b949e;
         font-size: 12px; letter-spacing: .3px; }
  .dots { display: inline-block; margin-right: 12px; color: #484f58; }
  .body { background: #0d1117; border: 1px solid #30363d;
          border-radius: 0 0 10px 10px; padding: 16px 20px 20px; }
  pre { margin: 0; white-space: pre-wrap; word-break: break-word; }
  .cmd { color: #7ee787; }
  .hdr { color: #79c0ff; }
  .note { color: #8b949e; }
</style>
"""


def curl(*args: str) -> str:
    """执行一次真实 curl，返回 stdout（不做任何加工）。"""
    result = subprocess.run(["curl", "-sS", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"curl 失败: {result.stderr.strip()}")
    return result.stdout


def pretty(raw: str) -> str:
    """只改缩进空白，不改字段与数值。"""
    try:
        return json.dumps(json.loads(raw), ensure_ascii=False, indent=2)
    except json.JSONDecodeError:
        return raw


def render_terminal(page, transcript: str, out: Path, title: str) -> None:
    """把真实会话文本按终端配色渲染成 PNG（逐字，不替换内容）。"""
    lines = []
    for line in transcript.splitlines():
        cls = "cmd" if line.startswith("$ ") else ("hdr" if line.startswith("HTTP/") else "")
        lines.append(f'<span class="{cls}">{html.escape(line) or "&nbsp;"}</span>')
    body = "\n".join(lines)
    page.set_content(
        "<!doctype html><html><head><meta charset='utf-8'>"
        + TERMINAL_CSS
        + "</head><body><div class='window'>"
        + f"<div class='bar'><span class='dots'>● ● ●</span>{html.escape(title)}</div>"
        + f"<div class='body'><pre>{body}</pre></div></div></body></html>",
        wait_until="load",
    )
    page.screenshot(path=str(out), full_page=True)
    (TRANSCRIPTS / (out.stem + ".txt")).write_text(transcript, encoding="utf-8")


def main() -> int:
    if not (RUNTIME / "READY").exists():
        print("ERROR: 真实服务未就绪（缺少 target/evidence-runtime/READY）", file=sys.stderr)
        return 1
    TRANSCRIPTS.mkdir(parents=True, exist_ok=True)
    SHOTS.mkdir(parents=True, exist_ok=True)

    # ---- 0. 录入外院节点（真实 REST 写入，已存在时忽略 409） ----
    create = curl("-o", "/dev/null", "-w", "%{http_code}",
                   "-X", "POST", f"{BASE}/api/imaging/peers",
                   "-H", "Content-Type: application/json",
                   "-d", f"@{RUNTIME / 'peer-create.json'}")
    print(f"[evidence] create peer HTTP {create}")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        term_page = browser.new_page(viewport={"width": 1240, "height": 900},
                                    device_scale_factor=2)
        ui_page = browser.new_page(viewport={"width": 1440, "height": 1000},
                                   device_scale_factor=2)

        # ---- 1. Swagger UI 真实浏览器渲染 ----
        ui_page.goto(f"{BASE}/swagger-ui.html", wait_until="domcontentloaded", timeout=30000)
        ui_page.wait_for_selector(".swagger-ui .info", timeout=30000)
        ui_page.wait_for_timeout(900)
        ui_page.screenshot(path=str(SHOTS / "01-swagger-ui.png"))
        print("[evidence] 01-swagger-ui.png")

        # ---- 2. 握手 health RPC 的真实 curl 会话 ----
        hs_cmd = ("$ curl -sS -X POST -H 'X-Employee-Id: employee_007' \\\n"
                  "    http://127.0.0.1:8080/api/imaging/peers/PEER_DEMO/health \\\n"
                  "  | python3 -m json.tool")
        hs_raw = curl("-X", "POST", "-H", "X-Employee-Id: employee_007",
                      f"{BASE}/api/imaging/peers/PEER_DEMO/health")
        hs_status = curl("-o", "/dev/null", "-w", "%{http_code}",
                          "-X", "POST", "-H", "X-Employee-Id: employee_007",
                          f"{BASE}/api/imaging/peers/PEER_DEMO/health")
        hs_transcript = (
            "# 跨院影像互认调阅路由系统 / 握手健康检查 RPC（真实运行）\n"
            + hs_cmd + "\n" + pretty(hs_raw) + "\n\n"
            + "$ curl -sS -o /dev/null -w '%{http_code}' \\\n"
            + "    -X POST http://127.0.0.1:8080/api/imaging/peers/PEER_DEMO/health\n"
            + hs_status + "\n"
        )
        render_terminal(term_page, hs_transcript, SHOTS / "02-handshake-curl.png",
                       "Terminal — POST /api/imaging/peers/{aet}/health")
        print("[evidence] 02-handshake-curl.png")

        # ---- 3. 一次完整 fetch 的真实五态推进 ----
        post_headers = curl("-i", "-X", "POST",
                             f"{BASE}/api/imaging/studies/fetch",
                             "-H", "Content-Type: application/json",
                             "-H", "X-Employee-Id: doctor_zhang",
                             "-d", f"@{RUNTIME / 'fetch-create.json'}")
        # 从真实响应体里取 taskId（不是预置常量）。
        # subprocess(text=True) 会把 HTTP 头的 \r\n 规范成 \n，所以不能用 \r\n\r\n 切分；
        # 响应体是 JSON，直接从第一个 '{' 起取即可。
        body = post_headers[post_headers.index("{"):]
        task_id = json.loads(body)["taskId"]

        seen: list[str] = []
        poll_lines: list[str] = []
        deadline = time.monotonic() + 6.0
        while time.monotonic() < deadline:
            raw = curl(f"{BASE}/api/imaging/tasks/{task_id}")
            status = json.loads(raw)["status"]
            if status not in seen:
                seen.append(status)
                poll_lines.append(raw.strip())
            if status in ("notified", "failed"):
                # 终态后再取一次，留一条完整终态 JSON
                poll_lines.append(curl(f"{BASE}/api/imaging/tasks/{task_id}").strip())
                break

        final_raw = curl(f"{BASE}/api/imaging/tasks/{task_id}")
        # 只取最近 4 行 = 本次调阅的 querying / pulling / stored / notified；
        # 原始 JSON 全文进截图，不做派生摘要，避免把「改写过的东西」当成实测。
        audit_raw = curl(f"{BASE}/api/imaging/audit?page=0&size=4")

        fetch_body = (RUNTIME / "fetch-create.json").read_text(encoding="utf-8").strip()
        transcript = (
            "# 跨院影像互认调阅路由系统 / 一次完整调阅（真实运行）\n"
            "$ cat fetch-create.json\n" + pretty(fetch_body) + "\n\n"
            "$ curl -sS -i -X POST -H 'Content-Type: application/json' \\\n"
            "    -H 'X-Employee-Id: doctor_zhang' -d @fetch-create.json \\\n"
            "    http://127.0.0.1:8080/api/imaging/studies/fetch\n"
            + post_headers.replace("\r\n", "\n").strip() + "\n\n"
            + f"$ while :; do curl -sS http://127.0.0.1:8080/api/imaging/tasks/{task_id}; done  # 轮询状态\n"
            + "\n".join(poll_lines) + "\n\n"
            + "$ curl -sS http://127.0.0.1:8080/api/imaging/tasks/" + task_id + "\n"
            + pretty(final_raw) + "\n\n"
            + "$ curl -sS 'http://127.0.0.1:8080/api/imaging/audit?page=0&size=4' \\\n"
            + "  | python3 -m json.tool  # append-only 状态审计，保留原始响应\n"
            + pretty(audit_raw) + "\n"
        )
        render_terminal(term_page, transcript, SHOTS / "03-fetch-task-status.png",
                        "Terminal — POST /studies/fetch -> GET /tasks/{task_id}")
        print(f"[evidence] 03-fetch-task-status.png 状态轨迹={seen} 终态="
              f"{json.loads(final_raw)['status']}")
        browser.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
