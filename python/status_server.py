#!/usr/bin/env python3
"""
Hekuo's Mod - 服务器状态网页 HTTP服务器

此脚本会被Nuitka编译为独立可执行文件，无需安装Python即可运行。

用法（直接运行时）:
  python3 status_server.py [port] [directory]

用法（Nuitka编译后）:
  ./status_server [port] [directory]

参数:
  port      - 监听端口 (默认: 8080)
  directory - 网页文件目录 (默认: 当前目录)

Nuitka编译命令:
  python -m nuitka --standalone --onefile --output-filename=hekuos-mod-web \
    --output-dir=build --python-flag=no_site status_server.py
"""

import http.server
import socketserver
import sys
import os
import json
import urllib.request
import urllib.error
import signal
import threading


class ReuseAddrTCPServer(socketserver.TCPServer):
    """允许端口复用的TCP服务器"""
    allow_reuse_address = True


class StatusHandler(http.server.SimpleHTTPRequestHandler):
    """自定义HTTP请求处理器"""

    def __init__(self, *args, directory=".", **kwargs):
        super().__init__(*args, directory=directory, **kwargs)

    def do_GET(self):
        """处理GET请求"""
        if self.path == "/api/status":
            self._handle_status_api()
        elif self.path == "/api/health":
            self._handle_health()
        else:
            if self.path == "/":
                self.path = "/index.html"
            super().do_GET()

    def _handle_status_api(self):
        """处理状态API请求 - 从Java端获取数据"""
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.end_headers()

        try:
            # Java状态API端口 = 本服务端口 + 1
            api_url = f"http://localhost:{SERVER_PORT + 1}/status"
            req = urllib.request.Request(api_url, method="GET")
            with urllib.request.urlopen(req, timeout=5) as response:
                data = response.read()
                self.wfile.write(data)
        except urllib.error.URLError:
            error_data = json.dumps(
                {"online": False, "error": "无法连接到Minecraft服务器"},
                ensure_ascii=False,
            )
            self.wfile.write(error_data.encode("utf-8"))
        except Exception as e:
            error_data = json.dumps(
                {"online": False, "error": str(e)}, ensure_ascii=False
            )
            self.wfile.write(error_data.encode("utf-8"))

    def _handle_health(self):
        """健康检查端点"""
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def log_message(self, format, *args):
        """静默日志 - 取消下面的注释可启用"""
        # sys.stderr.write("[%s] %s\n" % (self.log_date_time_string(), format % args))
        pass


# 全局变量
SERVER_PORT = 8080
SERVE_DIRECTORY = "."
httpd = None


def shutdown_handler(signum, frame):
    """信号处理 - 优雅关闭"""
    global httpd
    if httpd:
        httpd.shutdown()
    sys.exit(0)


def main():
    global SERVER_PORT, SERVE_DIRECTORY, httpd

    # 解析命令行参数
    SERVER_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    SERVE_DIRECTORY = sys.argv[2] if len(sys.argv) > 2 else "."

    # 注册信号处理
    signal.signal(signal.SIGINT, shutdown_handler)
    signal.signal(signal.SIGTERM, shutdown_handler)

    try:
        httpd = ReuseAddrTCPServer(("", SERVER_PORT), StatusHandler)
        print(f"HEKUO_WEB_OK:{SERVER_PORT}")  # Java端通过此标识确认启动成功
        httpd.serve_forever()
    except OSError as e:
        if "Address already in use" in str(e):
            print(f"ERROR: 端口 {SERVER_PORT} 已被占用")
        else:
            print(f"ERROR: {e}")
        sys.exit(1)
    except KeyboardInterrupt:
        pass
    finally:
        if httpd:
            httpd.server_close()

    sys.exit(0)


if __name__ == "__main__":
    main()
