#!/usr/bin/env python3
"""Serve the local fixture and record demo HTTP tracking; no upstream requests."""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Lock
from urllib.parse import parse_qs, urlencode, urlsplit

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--port", type=int, default=18888)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.parent.mkdir(parents=True, exist_ok=True)
fixture = Path(__file__).resolve().parents[1] / "src/main/assets/tcl-hk-pod.xml"
lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        try:
            request = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            self.send_error(400)
            return
        record = {"time": datetime.now(timezone.utc).isoformat(), "method": "POST",
                  "path": self.path, "body": request, "user_agent": self.headers.get("User-Agent")}
        with lock, args.output.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        if urlsplit(self.path).path != "/api/v2/ad/google-gam/resolve":
            self.send_error(404)
            return
        query = urlencode({"iu": "/fixture/video", "sz": "640x360", "output": "vast", "correlator": "",
                           "cust_params": "fixture=three-ad-pod"})
        response = json.dumps({"code": 100000, "data": {
            "ad_tag_url": f"http://127.0.0.1:{args.port}/tag?{query}",
            "ad_load_timeout_ms": 20000, "ad_startup_timeout_ms": 35000}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def do_GET(self):
        record = {"time": datetime.now(timezone.utc).isoformat(), "method": "GET",
                  "path": self.path, "query": parse_qs(urlsplit(self.path).query, keep_blank_values=True),
                  "user_agent": self.headers.get("User-Agent")}
        with lock, args.output.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
        body = fixture.read_bytes() if urlsplit(self.path).path == "/tag" else b""
        self.send_response(200)
        self.send_header("Content-Type", "application/xml" if body else "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


print(f"Local demo HTTP server: http://127.0.0.1:{args.port}", flush=True)
ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
