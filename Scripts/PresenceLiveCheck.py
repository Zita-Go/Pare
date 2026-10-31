#!/usr/bin/env python3
"""Explicit, disposable presence HTTP acceptance. Never reads app stores or credentials.

Invoked by PresenceLiveCheck.swift with mode-0600 fake-account and bridge files.
Only uses a public project key and ordinary password/refresh/logout endpoints.
"""
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import uuid
from urllib.parse import urlparse

class CheckFailed(Exception):
    pass


def check(condition):
    if not condition:
        raise CheckFailed()


def private_json(path):
    info = os.stat(path, follow_symlinks=False)
    check(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_uid == os.getuid())
    return json.loads(Path(path).read_text())


class WireResponse:
    def __init__(self, status, body):
        self.status_code = status
        self.body = body

    def json(self):
        return json.loads(self.body)


class WireClient:
    """System TLS transport; no curlrc/netrc, redirects, shell interpolation, or secret argv."""
    def post(self, url, json, headers, timeout, allow_redirects):
        check(not allow_redirects)
        quote = __import__("json").dumps
        config = ["silent", "show-error", "request = \"POST\"", "proto = \"=https\"",
                  "max-time = " + str(timeout), "connect-timeout = 10", "url = " + quote(url),
                  "write-out = \"\\n%{http_code}\"", "data-binary = " + quote(quote(json))]
        config.extend("header = " + quote(name + ": " + value) for name, value in headers.items())
        # -q must be first: ignore personal curl configuration; all sensitive data stays on stdin.
        response = subprocess.run(["/usr/bin/curl", "-q", "--config", "-"], input="\n".join(config) + "\n",
                                  text=True, capture_output=True, timeout=timeout + 5)
        check(response.returncode == 0)
        body, status = response.stdout.rsplit("\n", 1)
        return WireResponse(int(status), body)

    def close(self):
        pass


def main():
    sessions = []
    http = WireClient()
    base = key = None
    successful = False
    cleanup_ok = True
    checks = 0
    stage = "inputs"
    last_status = 0
    failure_kind = "none"
    result = None
    try:
        check(len(sys.argv) == 6)
        config = json.loads(Path(sys.argv[1]).read_text())
        users = private_json(sys.argv[2])["users"]
        bridge = private_json(sys.argv[3])
        check(len(users) == 2)
        for user in users:
            check(user["email"] == "presence-qa-" + str(uuid.UUID(user["id"])) + "@presence.pare.invalid")
            check(len(user["password"]) >= 24)
        base = config["api_url"].rstrip("/")
        key = config["publishable_key"]
        url = urlparse(base)
        check(url.scheme == "https" and url.hostname == sys.argv[5] + ".supabase.co")
        check(not url.username and not url.password and not url.query and not url.fragment and url.path in ("", "/"))
        check(key.startswith("sb_publishable_") or key.count(".") == 2)

        def post(path, body, token=None):
            nonlocal last_status
            headers = {"apikey": key, "Content-Type": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            response = http.post(base + path, json=body, headers=headers, timeout=30, allow_redirects=False)
            last_status = response.status_code
            return response

        def sign_in(user):
            response = post("/auth/v1/token?grant_type=password", {"email": user["email"], "password": user["password"]})
            check(response.status_code == 200)
            session = response.json()
            check(session["user"]["id"] == user["id"])
            sessions.append(session)
            return session

        stage = "password-login"
        same = sign_in(users[0])
        other = sign_in(users[1])
        checks += 2
        stage = "presence-wire"
        device = str(uuid.uuid4())
        run = {"run_id": str(uuid.uuid4()), "task_id": str(uuid.uuid4()), "kind": "waiting", "state": "running",
               "started_at": bridge["started_at"], "elapsed_seconds": 45, "target_seconds": None}

        def request(sequence, runs, chosen=device):
            return {"p_device_id": chosen, "p_sequence": sequence, "p_label": "Android", "p_runs": runs}

        def exchange(body, session=same):
            response = post("/rest/v1/rpc/pare_presence_exchange", body, session["access_token"])
            check(response.status_code == 200)
            value = response.json()
            check(value["version"] == 1 and 1 <= len(value["devices"]) <= 32)
            own = next((d for d in value["devices"] if d["device_id"] == body["p_device_id"]), None)
            check(own is not None and own["sequence"] == value["accepted_sequence"] >= body["p_sequence"])
            return value, own

        first, own = exchange(request(1, [run]))
        mac = next((d for d in first["devices"] if d["device_id"] == bridge["mac_device"]), None)
        check(mac is not None and mac["runs"][0]["run_id"] == bridge["mac_run"])
        checks += 1
        run["state"] = "paused"
        run["elapsed_seconds"] = 78
        paused, paused_own = exchange(request(2, [run]))
        retried, retried_own = exchange(request(2, [run]))
        check(paused_own == retried_own)
        stale, stale_own = exchange(request(1, []))
        check(stale["accepted_sequence"] == 2 and stale_own == paused_own)
        checks += 2
        changed = dict(run, elapsed_seconds=79)
        check(post("/rest/v1/rpc/pare_presence_exchange", request(2, [changed]), same["access_token"]).status_code == 400)
        check(post("/rest/v1/rpc/pare_presence_exchange", request(3, [dict(run, elapsed_seconds=-1)]), same["access_token"]).status_code == 400)
        check(post("/rest/v1/rpc/pare_presence_exchange", request(3, [dict(run, title="fictional-rejected-field")]), same["access_token"]).status_code == 400)
        check(post("/rest/v1/rpc/pare_presence_exchange", request(3, []), None).status_code in (401, 403))
        checks += 4
        isolated, isolated_own = exchange(request(1, [], chosen=bridge["mac_device"]), other)
        check(len(isolated["devices"]) == 1 and isolated_own["runs"] == [])
        checks += 1
        renewed = post("/auth/v1/token?grant_type=refresh_token", {"refresh_token": same["refresh_token"]})
        check(renewed.status_code == 200)
        renewed_session = renewed.json()
        check(renewed_session["user"]["id"] == users[0]["id"])
        same.update(renewed_session)
        run["elapsed_seconds"] = 180
        final, own = exchange(request(3, [run]))
        check(own["runs"][0]["state"] == "paused" and own["runs"][0]["elapsed_seconds"] == 180)
        checks += 2
        result = {"android_device": device, "android_run": run["run_id"], "reply": final}
        successful = True
    except Exception as failure:
        # Never print exception messages, HTTP responses, request headers or transport context.
        successful = False
        failure_kind = type(failure).__name__
    finally:
        for session in sessions:
            try:
                response = http.post(base + "/auth/v1/logout?scope=local", json={},
                    headers={"apikey": key, "Authorization": "Bearer " + session["access_token"]}, timeout=30, allow_redirects=False)
                cleanup_ok = cleanup_ok and response.status_code in (200, 204)
            except Exception:
                cleanup_ok = False
        if successful and sessions:
            try:
                response = http.post(base + "/rest/v1/rpc/pare_presence_exchange", json=request(4, []),
                    headers={"apikey": key, "Authorization": "Bearer " + sessions[0]["access_token"]}, timeout=30, allow_redirects=False)
                check(response.status_code in (401, 403))
                checks += 1
            except Exception:
                successful = False
        http.close()
    if successful and cleanup_ok:
        fd = os.open(sys.argv[4], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as output:
            json.dump(result, output)
        print("PASS: Python authenticated wire checks=" + str(checks) + "; temporary sessions logged out=" + str(len(sessions)))
        return 0
    print("FAIL: Python presence acceptance; completed checks=" + str(checks) + "; stage=" + stage +
          "; category=" + failure_kind + "; HTTP=" + str(last_status) + "; cleanup=" + ("PASS" if cleanup_ok else "REQUIRED"))
    return 1


if __name__ == "__main__":
    sys.exit(main())
