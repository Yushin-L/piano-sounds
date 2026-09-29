#!/usr/bin/env python3
"""Serve only the built APK and a small installation page on a local network."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
from urllib.parse import urlsplit

APK = Path(__file__).resolve().parents[1] / "dist/PianoSounds-1.1.0-debug.apk"
PAGE = """<!doctype html>
<html lang="ko"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Piano Sounds 설치</title>
<style>
body {font: 18px/1.7 system-ui; margin: 48px auto; padding: 0 24px;
max-width: 520px; background: #f7f3e9; color: #203b32}
a {display: block; background: #203b32; color: white; padding: 16px;
border-radius: 12px; text-align: center; text-decoration: none}
li {margin: 12px 0}
</style>
<h1>Piano Sounds</h1><p>KeyLab과 함께 연주하는 오프라인 피아노</p>
<a href="/PianoSounds-1.1.0-debug.apk" download>Android 앱 다운로드 · 약 85MB</a>
<p>버전 1.1.0 · 메트로놈 추가 · 개발용 서명 APK</p>
<ol><li>다운로드한 APK를 열어 설치하세요. 설치 출처 허용을 요청하면
현재 사용 중인 브라우저 또는 내 파일에 허용해 주세요.</li>
<li>앱을 열고 <strong>소리 미리 듣기</strong>로 소리를 확인하세요.</li>
<li>KeyLab을 USB-C 어댑터로 연결하고 연주하세요.</li></ol>
<p>소리가 작으면 휴대폰 미디어 볼륨과 앱 볼륨을 확인하세요.</p>
</html>""".encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.respond(head=True)

    def do_GET(self):
        self.respond(head=False)

    def respond(self, head):
        path = urlsplit(self.path).path
        if path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            if not head:
                self.wfile.write(PAGE)
        elif path == "/" + APK.name:
            with APK.open("rb") as source:
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", f'attachment; filename="{APK.name}"')
                self.send_header("Content-Length", str(APK.stat().st_size))
                self.end_headers()
                if not head:
                    shutil.copyfileobj(source, self.wfile)
        else:
            self.send_error(404)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not APK.is_file():
        parser.error(f"APK not found: {APK}")
    with ThreadingHTTPServer((args.bind, args.port), Handler) as server:
        print(f"Download page: http://{args.bind}:{args.port}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
