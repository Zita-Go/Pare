#!/usr/bin/env python3
"""Loopback-only deterministic Pare demo service. Never calls a model.

python3 Scripts/intelligence-mock-server.py --port 8766
Pare --demo --ai-endpoint http://127.0.0.1:8766/v1
GET /stats returns POST count; opening an assistant should leave it at zero.
"""
import argparse
import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=8766)
parser.add_argument("--mode", choices=["success", "malformed", "truncated", "error", "redirect", "oversized", "slow"], default="success")
args = parser.parse_args()


def action(kind, source, **fields):
    value = dict(actionID=str(uuid.uuid4()), kind=kind, sourceID=source["id"], evidenceIDs=[source["id"]],
                 title="", text="", nextAction="", definitionOfDone="", plannedMinutes=None,
                 allocationKind="personal", selected=True, reason="先用一个小动作获取实际信息。")
    value.update(fields)
    return value


def proposal(request):
    records = request["evidence"]
    source = next((item for item in records if item["id"] == request.get("subjectID")), records[0])
    actions = []
    if request["intent"] == "idea":
        actions = [action("createLinkedTask", source, title="收集一个真实案例", nextAction="围绕当前想法，写下一个具体使用场景和仍不确定的地方。", definitionOfDone="写完一个场景就可以停。", plannedMinutes=5),
                   action("updateIdeaNextQuestion", source, text="这个场景中，最需要验证的一个困难是什么？"),
                   action("appendAIExplorationRecord", source, text="AI 提议：先从一个具体场景验证需求；这尚未被实际观察证实。")]
    elif request["intent"] == "unblock":
        actions = [action("updateTaskNextStep", source, nextAction="针对“" + source["title"][:60] + "”，列出一个已知条件和一个需要澄清的问题。", definitionOfDone="写下两句话后可以停，不要求马上完成任务。", plannedMinutes=3)]
    else:
        source = next((item for item in records if item["kind"] == "task"), None)
        if source:
            actions = [action("setTodaySelection", source, title="今天先推进这一件", plannedMinutes=min(10, request["availableMinutes"])),
                       action("updateTaskNextStep", source, nextAction="围绕“" + source["title"][:60] + "”，写出一个最小可交付结果。", definitionOfDone="写好一行目标即可停。", plannedMinutes=min(10, request["availableMinutes"])),
                       action("setDailyWin", source)]
    return dict(version=1, requestID=request["id"], summary="这是本地固定样例：已有记录保留，尚未发生的验证仍是未知。",
                openQuestion="什么证据能说明这个起点有用？", clarification="" if actions else "这次最希望推进哪个任务？",
                actions=actions, evidenceIDs=[source["id"]] if source else [], memoryCandidate="以后优先给我十分钟内可以停下的起点。")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *unused):
        pass

    def send(self, status, payload, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        parsed = urlparse(self.path)
        mode = parse_qs(parsed.query).get("mode", [None])[0]
        if parsed.path == "/control" and mode in ["success", "malformed", "truncated", "error", "redirect", "oversized", "slow"]:
            self.server.mode = mode
        payload = {"requests": self.server.request_count}
        if parsed.path != "/stats":
            payload.update(mode=self.server.mode, lastInstruction=self.server.last_instruction)
        self.send(200, json.dumps(payload).encode())

    def do_POST(self):
        self.server.request_count += 1
        mode = self.server.mode
        length = int(self.headers.get("Content-Length", "0"))
        if length > 64000:
            self.send(413, b"{}"); return
        body = self.rfile.read(length)
        if mode == "error":
            self.send(503, b"{}"); return
        if mode == "redirect":
            self.send(307, b"{}", {"Location": f"http://127.0.0.1:{self.server.server_port}/redirect-target"}); return
        if mode == "oversized":
            self.send(200, b"x" * 128001); return
        if mode == "slow":
            time.sleep(4)
        try:
            envelope = json.loads(body)
            request = json.loads(envelope["messages"][-1]["content"])
            self.server.last_instruction = request.get("instruction", "")
            result = proposal(request)
            content = "not JSON" if mode == "malformed" else json.dumps(result, ensure_ascii=False)
            response = dict(choices=[dict(finish_reason="length" if mode == "truncated" else "stop", message=dict(content=content))])
            self.send(200, json.dumps(response, ensure_ascii=False).encode())
        except (KeyError, ValueError, TypeError) as error:
            self.send(400, json.dumps({"error": type(error).__name__}).encode())


server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
server.request_count = 0
server.mode = args.mode
server.last_instruction = ""
print(server.server_port, flush=True)
server.serve_forever()
