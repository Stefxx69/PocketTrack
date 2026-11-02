# SPDX-License-Identifier: GPL-3.0-or-later
"""Wi-Fi receiver: a tiny HTTP server the phone app uploads recordings to.

Runs in a background thread and never touches bpy. Finished recordings are put on a
queue that a bpy timer (see ui.py) drains on Blender's main thread.

Protocol (PIN in the X-PocketTrack-Pin header):
    GET  /ping                        -> 200 when the PIN matches
    POST /upload?rec=NAME&file=FILE   -> raw body saved as <save_dir>/NAME/FILE
    POST /done?rec=NAME               -> NAME is queued for import
"""

import json
import os
import queue
import re
import secrets
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ALLOWED_FILES = {"track.json", "meta.json", "video.mp4", "depth.bin"}
_NAME_RE = re.compile(r"^[A-Za-z0-9_\-]{1,64}$")
MAX_UPLOAD = 8 * 1024 ** 3  # 8 GB sanity limit


class Receiver:
    def __init__(self, save_dir, port=8765):
        self.save_dir = save_dir
        self.pin = "%06d" % secrets.randbelow(1_000_000)
        self.completed = queue.Queue()
        self.status = "Waiting for phone…"
        self.hosts = local_ipv4_addresses()
        self._server = None
        self._thread = None
        self.port = None
        self._start(port)

    def _start(self, first_port):
        last_error = None
        for port in range(first_port, first_port + 10):
            try:
                server = ThreadingHTTPServer(("0.0.0.0", port), _Handler)
            except OSError as e:
                last_error = e
                continue
            server.daemon_threads = True
            server.receiver = self
            self._server = server
            self.port = port
            self._thread = threading.Thread(target=server.serve_forever, name="PocketTrackReceiver", daemon=True)
            self._thread.start()
            return
        raise OSError("No free port from %d: %s" % (first_port, last_error))

    def stop(self):
        if self._server:
            self._server.shutdown()
            self._server.server_close()
            self._server = None

    @property
    def running(self):
        return self._server is not None

    def qr_payload(self):
        return json.dumps({"pt": 1, "h": self.hosts, "p": self.port, "k": self.pin},
                          separators=(",", ":"))


class _Handler(BaseHTTPRequestHandler):
    server_version = "PocketTrack/1"

    def log_message(self, fmt, *args):  # keep Blender's console quiet
        pass

    @property
    def receiver(self):
        return self.server.receiver

    def _reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self):
        pin = self.headers.get("X-PocketTrack-Pin", "")
        return secrets.compare_digest(pin.encode(), self.receiver.pin.encode())

    def _rec_dir(self, qs):
        name = qs.get("rec", [""])[0]
        if not _NAME_RE.match(name):
            return None, name
        return os.path.join(self.receiver.save_dir, name), name

    def do_GET(self):
        url = urlparse(self.path)
        if url.path != "/ping":
            return self._reply(404, {"ok": False})
        if not self._authorised():
            return self._reply(403, {"ok": False, "error": "wrong PIN"})
        self.receiver.status = "Phone connected (%s)" % self.client_address[0]
        self._reply(200, {"ok": True, "app": "pockettrack"})

    def do_POST(self):
        url = urlparse(self.path)
        qs = parse_qs(url.query)
        if not self._authorised():
            return self._reply(403, {"ok": False, "error": "wrong PIN"})
        rec_dir, name = self._rec_dir(qs)
        if rec_dir is None:
            return self._reply(400, {"ok": False, "error": "bad recording name"})

        if url.path == "/upload":
            file_name = qs.get("file", [""])[0]
            if file_name not in ALLOWED_FILES:
                return self._reply(400, {"ok": False, "error": "unexpected file"})
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_UPLOAD:
                return self._reply(400, {"ok": False, "error": "bad length"})
            os.makedirs(rec_dir, exist_ok=True)
            final = os.path.join(rec_dir, file_name)
            tmp = final + ".part"
            remaining = length
            with open(tmp, "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
                    done = length - remaining
                    self.receiver.status = "Receiving %s  %d%%  (%.1f MB)" % (
                        file_name, done * 100 // length, done / 1e6)
            if remaining:
                os.remove(tmp)
                return self._reply(400, {"ok": False, "error": "upload interrupted"})
            os.replace(tmp, final)
            return self._reply(200, {"ok": True})

        if url.path == "/done":
            if not os.path.isfile(os.path.join(rec_dir, "track.json")):
                return self._reply(400, {"ok": False, "error": "track.json missing"})
            final_dir = name_folder_after_scan(rec_dir)
            self.receiver.status = "Received %s – importing…" % os.path.basename(final_dir)
            self.receiver.completed.put(final_dir)
            return self._reply(200, {"ok": True})

        self._reply(404, {"ok": False})


_UNSAFE_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def scan_title(folder):
    """The name the scan was given in the app (meta.json), or None."""
    try:
        with open(os.path.join(folder, "meta.json"), "r", encoding="utf-8") as f:
            title = str(json.load(f).get("title", "")).strip()
        return title or None
    except (OSError, ValueError):
        return None


def name_folder_after_scan(folder):
    """Renames a received scan folder from its ID (PT_...) to the scan's name, adding " (2)",
    " (3)", ... if that name is taken. Returns the final folder."""
    title = scan_title(folder)
    if not title:
        return folder
    safe = _UNSAFE_CHARS.sub("_", title).strip(" .")[:80] or os.path.basename(folder)
    parent = os.path.dirname(folder)
    target = os.path.join(parent, safe)
    n = 2
    while os.path.exists(target) and os.path.normcase(target) != os.path.normcase(folder):
        target = os.path.join(parent, "%s (%d)" % (safe, n))
        n += 1
    if os.path.normcase(target) != os.path.normcase(folder):
        try:
            os.replace(folder, target)
        except OSError:
            return folder
    return target


def local_ipv4_addresses():
    """LAN IPv4 addresses of this PC, the default-route one first."""
    found = []
    try:
        # No packets are sent; this just asks the OS which interface would route outward.
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        found.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    result = []
    for ip in found:
        if ip not in result and not ip.startswith("127.") and not ip.startswith("169.254."):
            result.append(ip)
    return result[:4] or ["127.0.0.1"]
