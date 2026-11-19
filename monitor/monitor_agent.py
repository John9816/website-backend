#!/usr/bin/env python3
"""轻量 ECS 指标采集器：只用标准库，绑定 127.0.0.1，供 nginx 同源反代。

采集：CPU/内存/磁盘/网络/负载/温度(可选)/服务存活/包体积与发布信息。
历史：SQLite 落盘，默认保留 48 小时，前端按需拉取区间。
鉴权：无（按用户明确要求公开；请勿在公网暴露除只读指标以外的内容）。
"""
import json
import os
import sqlite3
import subprocess
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 3219
DB_PATH = "/opt/website/monitor/history.db"
RETENTION_HOURS = 48
SAMPLE_SECONDS = 10

WEB_ROOT = "/opt/website"
DIST_LINK = "/opt/website/dist"
JAR_LINK = "/opt/website/current/backend/website.jar"
RELEASES_DIR = "/opt/website/releases"
LATEST_JSON = "/opt/website/updates/latest.json"

# 服务名 -> 展示名；用 systemctl is-active 判定，端口探测作为补充
SERVICES = {
    "website-api.service": "后端 API (8090)",
    "website-ecs.service": "前端与同源代理 (3000)",
    "frps.service": "frps 隧道",
    "nginx.service": "Nginx",
    "ssh.service": "SSH",
}

NET_IFACE = None  # 启动时自动探测默认出网网卡


def run(cmd, timeout=5):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except Exception:
        return None


def detect_iface():
    global NET_IFACE
    r = run(["ip", "route", "get", "8.8.8.8"])
    if r and r.returncode == 0:
        parts = r.stdout.split()
        if "dev" in parts:
            NET_IFACE = parts[parts.index("dev") + 1]
            return
    NET_IFACE = "eth0"


def read_proc_cpu():
    try:
        with open("/proc/stat") as f:
            parts = f.readline().split()[1:]
        vals = [int(x) for x in parts[:8]]
        idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
        return sum(vals), idle
    except Exception:
        return None, None


_prev_cpu = None


def cpu_percent():
    global _prev_cpu
    total, idle = read_proc_cpu()
    if total is None:
        return 0.0
    if _prev_cpu is None:
        _prev_cpu = (total, idle)
        return 0.0
    dt = total - _prev_cpu[0]
    di = idle - _prev_cpu[1]
    _prev_cpu = (total, idle)
    if dt <= 0:
        return 0.0
    return round(max(0.0, min(100.0, 100.0 * (1 - di / dt))), 1)


def mem_info():
    info = {}
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                k, v = line.split(":", 1)
                info[k] = int(v.strip().split()[0])
        total = info.get("MemTotal", 0)
        avail = info.get("MemAvailable", info.get("MemFree", 0))
        swap_total = info.get("SwapTotal", 0)
        swap_free = info.get("SwapFree", 0)
        return {
            "total_mb": round(total / 1024, 1),
            "used_mb": round((total - avail) / 1024, 1),
            "available_mb": round(avail / 1024, 1),
            "percent": round(100.0 * (total - avail) / total, 1) if total else 0.0,
            "swap_total_mb": round(swap_total / 1024, 1),
            "swap_used_mb": round((swap_total - swap_free) / 1024, 1),
        }
    except Exception:
        return {}


def disk_info():
    try:
        st = os.statvfs("/")
        total = st.f_blocks * st.f_frsize
        free = st.f_bavail * st.f_frsize
        used = total - free
        return {
            "total_gb": round(total / 1024**3, 1),
            "used_gb": round(used / 1024**3, 1),
            "free_gb": round(free / 1024**3, 1),
            "percent": round(100.0 * used / total, 1) if total else 0.0,
        }
    except Exception:
        return {}


def read_net_bytes():
    iface = NET_IFACE or "eth0"
    try:
        with open("/proc/net/dev") as f:
            for line in f:
                if iface + ":" not in line:
                    continue
                parts = line.split(":")[1].split()
                return int(parts[0]), int(parts[8])
    except Exception:
        pass
    return None, None


_prev_net = None
_prev_net_ts = None


def net_rates():
    global _prev_net, _prev_net_ts
    rx, tx = read_net_bytes()
    now = time.time()
    if rx is None:
        return {"rx_mbps": 0.0, "tx_mbps": 0.0}
    result = {"rx_mbps": 0.0, "tx_mbps": 0.0}
    if _prev_net is not None and _prev_net_ts and now > _prev_net_ts:
        dt = now - _prev_net_ts
        result["rx_mbps"] = round(max(0, rx - _prev_net[0]) * 8 / dt / 1e6, 3)
        result["tx_mbps"] = round(max(0, tx - _prev_net[1]) * 8 / dt / 1e6, 3)
    _prev_net = (rx, tx)
    _prev_net_ts = now
    return result


def load_avg():
    try:
        with open("/proc/loadavg") as f:
            p = f.read().split()
        return {"one": float(p[0]), "five": float(p[1]), "fifteen": float(p[2])}
    except Exception:
        return {"one": 0.0, "five": 0.0, "fifteen": 0.0}


def public_ip():
    r = run(["curl", "-sS", "--max-time", "3", "https://api.ipify.org"], timeout=5)
    if r and r.returncode == 0 and r.stdout.strip():
        return r.stdout.strip()
    return "8.156.95.93"  # 已知静态 IP 兜底，避免每次外网请求


def dir_size(path):
    total = 0
    for root, _dirs, files in os.walk(path, followlinks=False):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass
    return total


def human(size):
    for unit in ["B", "KB", "MB", "GB"]:
        if size < 1024 or unit == "GB":
            return f"{size:.1f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024
    return f"{size:.1f} GB"


def package_info():
    result = {"frontend": {}, "backend": {}, "release": {}}
    try:
        target = os.path.realpath(DIST_LINK)
        size = dir_size(DIST_LINK)
        result["frontend"] = {
            "size_bytes": size,
            "size": human(size),
            "path": target,
            "exists": os.path.isdir(DIST_LINK),
        }
    except Exception as e:
        result["frontend"] = {"error": str(e)}
    try:
        target = os.path.realpath(JAR_LINK)
        size = os.path.getsize(JAR_LINK)
        mtime = os.path.getmtime(JAR_LINK)
        result["backend"] = {
            "size_bytes": size,
            "size": human(size),
            "path": target,
            "built_at": datetime.fromtimestamp(mtime).strftime("%Y-%m-%d %H:%M:%S"),
            "exists": os.path.isfile(JAR_LINK),
        }
    except Exception as e:
        result["backend"] = {"error": str(e)}
    try:
        with open(LATEST_JSON) as f:
            manifest = json.load(f)
        result["release"] = {
            "version": manifest.get("version"),
            "build": manifest.get("buildNumber"),
            "has_apk": bool(manifest.get("downloadUrl")),
        }
        mtime = os.path.getmtime(LATEST_JSON)
        result["release"]["published_at"] = datetime.fromtimestamp(mtime).strftime(
            "%Y-%m-%d %H:%M:%S"
        )
    except Exception as e:
        result["release"] = {"error": str(e)}
    try:
        cur = os.path.realpath("/opt/website/current")
        result["release"]["current_release"] = os.path.basename(cur)
    except Exception:
        pass
    return result


def service_status():
    out = {}
    for unit, label in SERVICES.items():
        r = run(["systemctl", "is-active", unit], timeout=3)
        active = bool(r and r.returncode == 0 and r.stdout.strip() == "active")
        out[label] = {"active": active, "unit": unit}
    # 前端/后端 HTTP 探活（同源）
    for label, url in [
        ("后端 API (8090)", "http://127.0.0.1:8090/health"),
        ("前端与同源代理 (3000)", "http://127.0.0.1:3000/"),
    ]:
        r = run(["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "3", url], timeout=5)
        code = r.stdout.strip() if r else ""
        out[label]["http"] = code if code.isdigit() else "ERR"
    return out


def uptime():
    try:
        with open("/proc/uptime") as f:
            seconds = float(f.read().split()[0])
        days = int(seconds // 86400)
        hours = int((seconds % 86400) // 3600)
        mins = int((seconds % 3600) // 60)
        return f"{days}天 {hours}小时 {mins}分"
    except Exception:
        return "未知"


def snapshot():
    return {
        "ts": int(time.time()),
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "cpu": cpu_percent(),
        "cores": os.cpu_count() or 1,
        "mem": mem_info(),
        "disk": disk_info(),
        "net": net_rates(),
        "iface": NET_IFACE,
        "load": load_avg(),
        "uptime": uptime(),
        "ip": public_ip(),
    }


# ---------- SQLite 历史 ----------
def init_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """CREATE TABLE IF NOT EXISTS metrics (
            ts INTEGER PRIMARY KEY, cpu REAL, mem REAL, disk REAL,
            rx REAL, tx REAL, load1 REAL
        )"""
    )
    conn.commit()
    conn.close()


def save_point(s):
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        "INSERT OR REPLACE INTO metrics VALUES (?,?,?,?,?,?,?)",
        (
            s["ts"], s["cpu"], s.get("mem", {}).get("percent", 0),
            s.get("disk", {}).get("percent", 0),
            s.get("net", {}).get("rx_mbps", 0),
            s.get("net", {}).get("tx_mbps", 0),
            s.get("load", {}).get("one", 0),
        ),
    )
    conn.execute("DELETE FROM metrics WHERE ts < ?", (int(time.time()) - RETENTION_HOURS * 3600,))
    conn.commit()
    conn.close()


def query_history(hours):
    conn = sqlite3.connect(DB_PATH)
    since = int(time.time()) - hours * 3600
    rows = conn.execute(
        "SELECT ts,cpu,mem,disk,rx,tx,load1 FROM metrics WHERE ts>=? ORDER BY ts",
        (since,),
    ).fetchall()
    conn.close()
    return [
        {"ts": r[0], "cpu": r[1], "mem": r[2], "disk": r[3],
         "rx": r[4], "tx": r[5], "load": r[6]}
        for r in rows
    ]


# ---------- 采样线程 ----------
def sampler():
    save_point(snapshot())
    while True:
        time.sleep(SAMPLE_SECONDS)
        try:
            save_point(snapshot())
        except Exception:
            pass


# ---------- HTTP ----------
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def _send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/live":
            s = snapshot()
            s["services"] = service_status()
            s["packages"] = package_info()
            self._send(s)
        elif path == "/api/history":
            hours = 6
            if "?" in self.path:
                for kv in self.path.split("?", 1)[1].split("&"):
                    if kv.startswith("hours="):
                        try:
                            hours = min(max(int(kv.split("=")[1]), 1), RETENTION_HOURS)
                        except ValueError:
                            pass
            self._send({"hours": hours, "points": query_history(hours)})
        elif path == "/api/health":
            self._send({"ok": True, "ts": int(time.time())})
        else:
            self._send({"error": "not found"}, 404)


def main():
    detect_iface()
    init_db()
    threading.Thread(target=sampler, daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
