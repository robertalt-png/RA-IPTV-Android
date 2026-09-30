#!/usr/bin/env python3
import argparse, base64, json, os, time, urllib.parse
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MEDIA = ROOT / "media"
HOST_FOR_EMULATOR = "10.0.2.2"
PORT = int(os.environ.get("NENOTV_QA_PORT", "8787"))
BASE = f"http://{HOST_FOR_EMULATOR}:{PORT}"

PNG_1X1 = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z8iAAAAAASUVORK5CYII=")

def now_rows():
    now = datetime.now(timezone.utc).replace(microsecond=0)
    a0, a1, b1 = now - timedelta(minutes=20), now + timedelta(minutes=40), now + timedelta(minutes=100)
    fmt = lambda d: d.strftime("%Y-%m-%d %H:%M:%S")
    return [
        {"title": base64.b64encode(b"QA Now: NenoTV News").decode(), "description": base64.b64encode(b"Automated QA current programme").decode(),
         "start": fmt(a0), "end": fmt(a1), "start_timestamp": int(a0.timestamp()), "stop_timestamp": int(a1.timestamp())},
        {"title": base64.b64encode(b"QA Next: NenoTV Test Show").decode(), "description": base64.b64encode(b"Automated QA next programme").decode(),
         "start": fmt(a1), "end": fmt(b1), "start_timestamp": int(a1.timestamp()), "stop_timestamp": int(b1.timestamp())},
    ]

def xmltv():
    rows = now_rows()
    xmltime = lambda epoch: datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y%m%d%H%M%S +0000")
    ps = []
    for row in rows:
        title = base64.b64decode(row["title"]).decode()
        desc = base64.b64decode(row["description"]).decode()
        ps.append(f'<programme start="{xmltime(row["start_timestamp"])}" stop="{xmltime(row["stop_timestamp"])}" channel="qa.nl"><title lang="nl">{title}</title><desc lang="nl">{desc}</desc></programme>')
    return ('<?xml version="1.0" encoding="UTF-8"?><tv><channel id="qa.nl"><display-name>QA NenoTV Live NL</display-name></channel>' + ''.join(ps) + '</tv>').encode()

LIVE_CATEGORIES = [{"category_id":"1","category_name":"|NL| Nederland"},{"category_id":"2","category_name":"|MULTI| Test"}]
VOD_CATEGORIES = [{"category_id":"10","category_name":"|NL| Films"}]
SERIES_CATEGORIES = [{"category_id":"20","category_name":"|NL| Series"}]

def live_streams():
    return [
        {"num":1,"name":"QA NenoTV Live NL","stream_type":"live","stream_id":1,"stream_icon":f"{BASE}/art.png","epg_channel_id":"qa.nl","category_id":"1","tv_archive":1,"tv_archive_duration":2,"direct_source":f"{BASE}/media/live.m3u8"},
        {"num":2,"name":"QA NenoTV Live MULTI","stream_type":"live","stream_id":2,"stream_icon":f"{BASE}/art.png","epg_channel_id":"qa.multi","category_id":"2","direct_source":f"{BASE}/media/live.m3u8"}
    ]

def vod_streams():
    return [{"num":1,"name":"QA NenoTV Test Movie","stream_type":"movie","stream_id":101,"stream_icon":f"{BASE}/art.png","category_id":"10","container_extension":"mp4","rating":"8.2","year":"2026","plot":"NenoTV automated QA movie.","direct_source":f"{BASE}/media/vod.mp4"}]

def series_rows():
    return [{"num":1,"name":"QA NenoTV Test Series","series_id":201,"cover":f"{BASE}/art.png","category_id":"20","rating":"8.5","year":"2026","plot":"Automated QA series."}]

class Handler(BaseHTTPRequestHandler):
    server_version = "NenoTV-QA/1.0"
    def log_message(self, fmt, *args): print("[mock]", self.address_string(), fmt % args, flush=True)

    def send_bytes(self, data, content_type="application/octet-stream", code=200):
        self.send_response(code); self.send_header("Content-Type", content_type); self.send_header("Content-Length", str(len(data))); self.send_header("Cache-Control","no-store"); self.end_headers(); self.wfile.write(data)

    def send_json(self, obj, code=200): self.send_bytes(json.dumps(obj).encode(), "application/json; charset=utf-8", code)

    def serve_file(self, path, content_type):
        path = Path(path)
        if not path.exists(): return self.send_json({"error":"media not generated","file":str(path)},404)
        return self.send_bytes(path.read_bytes(), content_type)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path); q = urllib.parse.parse_qs(u.query); p = u.path
        if p == "/health": return self.send_json({"ok":True,"service":"nenotv-qa"})
        if p == "/art.png": return self.send_bytes(PNG_1X1,"image/png")
        if p == "/playlist.m3u":
            body = f'''#EXTM3U x-tvg-url="{BASE}/epg.xml"
#EXTINF:-1 tvg-id="qa.nl" tvg-name="QA NenoTV Live NL" group-title="|NL| Nederland",QA NenoTV Live NL
{BASE}/media/live.m3u8
#EXTINF:-1 tvg-id="qa.multi" tvg-name="QA NenoTV Live MULTI" group-title="|MULTI| Test",QA NenoTV Live MULTI
{BASE}/media/live.m3u8
'''.encode()
            return self.send_bytes(body,"audio/x-mpegurl; charset=utf-8")
        if p == "/epg.xml": return self.send_bytes(xmltv(),"application/xml; charset=utf-8")
        if p == "/player_api.php":
            user = q.get("username",[""])[0]; password = q.get("password",[""])[0]
            if user != "qa" or password != "qa-pass":
                return self.send_json({"user_info":{"auth":0,"status":"Disabled"},"server_info":{"url":HOST_FOR_EMULATOR}})
            action = q.get("action",[""])[0]; category_id = q.get("category_id",[""])[0]
            if not action: return self.send_json({"user_info":{"auth":1,"status":"Active","username":user},"server_info":{"url":HOST_FOR_EMULATOR,"port":str(PORT)}})
            if action == "get_live_categories": return self.send_json(LIVE_CATEGORIES)
            if action == "get_vod_categories": return self.send_json(VOD_CATEGORIES)
            if action == "get_series_categories": return self.send_json(SERIES_CATEGORIES)
            if action == "get_live_streams":
                rows=live_streams(); return self.send_json(rows if not category_id else [x for x in rows if x["category_id"]==category_id])
            if action == "get_vod_streams":
                rows=vod_streams(); return self.send_json(rows if not category_id else [x for x in rows if x["category_id"]==category_id])
            if action == "get_series":
                rows=series_rows(); return self.send_json(rows if not category_id else [x for x in rows if x["category_id"]==category_id])
            if action == "get_short_epg": return self.send_json({"epg_listings":now_rows()})
            if action == "get_vod_info":
                return self.send_json({"info":{"name":"QA NenoTV Test Movie","year":"2026","genre":"Drama","duration":"00:00:08","plot":"NenoTV automated QA movie.","rating":"8.2","movie_image":f"{BASE}/art.png"},"movie_data":vod_streams()[0]})
            if action == "get_series_info":
                return self.send_json({"info":{"name":"QA NenoTV Test Series","year":"2026","genre":"Drama","plot":"Automated QA series.","cover":f"{BASE}/art.png"},"episodes":{"1":[
                    {"id":"301","episode_num":1,"title":"QA Pilot","container_extension":"mp4","direct_source":f"{BASE}/media/vod.mp4","info":{"plot":"QA pilot episode","movie_image":f"{BASE}/art.png"}},
                    {"id":"302","episode_num":2,"title":"QA Second Episode","container_extension":"mp4","direct_source":f"{BASE}/media/vod.mp4","info":{"plot":"QA second episode","movie_image":f"{BASE}/art.png"}}
                ]}})
            return self.send_json([])
        if p == "/error/500": return self.send_json({"error":"intentional QA failure"},500)
        if p == "/slow": time.sleep(4); return self.send_json({"ok":True})
        if p.startswith("/live/"): return self.serve_file(MEDIA/"live.m3u8","application/vnd.apple.mpegurl")
        if p.startswith("/movie/") or p.startswith("/series/"): return self.serve_file(MEDIA/"vod.mp4","video/mp4")
        if p.startswith("/media/"):
            name=p.split("/")[-1]
            ctype="application/octet-stream"
            if name.endswith(".m3u8"): ctype="application/vnd.apple.mpegurl"
            elif name.endswith(".ts"): ctype="video/mp2t"
            elif name.endswith(".mp4"): ctype="video/mp4"
            return self.serve_file(MEDIA/name,ctype)
        return self.send_json({"error":"not found","path":p},404)

def main():
    global PORT, BASE
    ap=argparse.ArgumentParser(); ap.add_argument("--port",type=int,default=PORT); args=ap.parse_args()
    PORT=args.port; BASE=f"http://{HOST_FOR_EMULATOR}:{PORT}"
    print(f"NenoTV QA mock server on 0.0.0.0:{PORT}; emulator base {BASE}",flush=True)
    ThreadingHTTPServer(("0.0.0.0",PORT),Handler).serve_forever()

if __name__ == "__main__": main()
