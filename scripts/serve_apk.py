"""Serve only the built APK at /buds.apk, for local-device installation."""
import argparse
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil

DEFAULT_APK = Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/app-debug.apk"


class Handler(BaseHTTPRequestHandler):
    def __init__(self, *args, apk, **kwargs):
        self.apk = apk
        super().__init__(*args, **kwargs)

    def do_GET(self):
        self.respond(True)

    def do_HEAD(self):
        self.respond(False)

    def respond(self, body):
        if self.path != "/buds.apk":
            self.send_error(404)
            return
        try:
            source = self.apk.open("rb")
        except FileNotFoundError:
            self.send_error(503, "Build the APK first")
            return
        with source:
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="Buds-0.1.0-debug.apk"')
            self.send_header("Content-Length", str(self.apk.stat().st_size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if body:
                shutil.copyfileobj(source, self.wfile)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    args = parser.parse_args()
    apk = args.apk.resolve(strict=True)
    print(f"APK available at http://{args.bind}:{args.port}/buds.apk", flush=True)
    ThreadingHTTPServer((args.bind, args.port), partial(Handler, apk=apk)).serve_forever()
