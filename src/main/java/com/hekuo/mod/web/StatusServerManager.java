package com.hekuo.mod.web;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 服务器状态网页管理器
 *
 * 启动策略（优先级从高到低）：
 * 1. Nuitka编译的独立可执行文件（无需Python环境）
 * 2. python3 解释器 + 脚本（需要Python环境，作为fallback）
 *
 * 架构：
 * - 前端：Python/Nuitka二进制提供静态HTML + 反向代理API
 * - 后端：Java内嵌HttpServer提供JSON状态数据（端口=前端端口+1）
 */
public class StatusServerManager {

    private static StatusServerManager instance;
    private Process webProcess;
    private MinecraftServer server;
    private ModConfig.WebStatusConfig config;
    private SimpleStatusServer statusApiServer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Path webWorkDir;

    // Nuitka编译后的二进制文件名
    private static final String NUITKA_BINARY_NAME_LINUX = "hekuos-mod-web";
    private static final String NUITKA_BINARY_NAME_WINDOWS = "hekuos-mod-web.exe";

    public static StatusServerManager getInstance() {
        if (instance == null) {
            instance = new StatusServerManager();
        }
        return instance;
    }

    /**
     * 启动状态网页服务
     */
    public void start(MinecraftServer server, ModConfig.WebStatusConfig config) {
        this.server = server;
        this.config = config;

        try {
            // 准备工作目录
            webWorkDir = Paths.get("hekuos-mod-web");
            if (!Files.exists(webWorkDir)) {
                Files.createDirectories(webWorkDir);
            }

            // 生成HTML页面
            Path htmlPath = webWorkDir.resolve("index.html");
            Files.writeString(htmlPath, generateHtmlPage(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // 生成Python脚本（fallback用）
            Path scriptPath = webWorkDir.resolve("status_server.py");
            Files.writeString(scriptPath, generatePythonScriptCode(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // 启动Java内嵌状态API服务器（端口=前端端口+1）
            int apiPort = config.port + 1;
            statusApiServer = new SimpleStatusServer(server, apiPort);
            statusApiServer.start();

            // 按优先级启动前端服务器
            boolean started = tryStartNuitkaBinary() || tryStartPythonScript();

            if (started) {
                HekuosMod.LOGGER.info("服务器状态网页已启动 - http://localhost:{}", config.port);
            } else {
                HekuosMod.LOGGER.warn("无法启动前端网页服务器，仅状态API可用 - http://localhost:{}", apiPort);
                HekuosMod.LOGGER.warn("请确保Nuitka编译的二进制文件存在于 hekuos-mod-web/ 目录，或安装Python 3");
            }

        } catch (Exception e) {
            HekuosMod.LOGGER.error("启动状态网页失败", e);
        }
    }

    /**
     * 尝试使用Nuitka编译的二进制文件启动
     * @return 是否启动成功
     */
    private boolean tryStartNuitkaBinary() {
        // 按操作系统选择二进制文件名
        String osName = System.getProperty("os.name", "").toLowerCase();
        String binaryName = osName.contains("win") ? NUITKA_BINARY_NAME_WINDOWS : NUITKA_BINARY_NAME_LINUX;

        // 搜索路径：1) web工作目录 2) mod配置目录 3) 服务器根目录
        Path[] searchPaths = {
            webWorkDir.resolve(binaryName),
            Paths.get("config", "hekuos-mod", binaryName),
            Paths.get(binaryName)
        };

        Path binaryPath = null;
        for (Path p : searchPaths) {
            if (Files.exists(p) && Files.isRegularFile(p)) {
                binaryPath = p;
                break;
            }
        }

        if (binaryPath == null) {
            HekuosMod.LOGGER.info("未找到Nuitka编译的二进制文件，跳过");
            return false;
        }

        try {
            // 确保可执行权限（Linux/macOS）
            if (!osName.contains("win")) {
                binaryPath.toFile().setExecutable(true);
            }

            HekuosMod.LOGGER.info("使用Nuitka二进制启动: {}", binaryPath);

            ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toAbsolutePath().toString(),
                String.valueOf(config.port),
                webWorkDir.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            pb.directory(webWorkDir.toFile());

            webProcess = pb.start();

            if (waitForStartup(webProcess, 10)) {
                HekuosMod.LOGGER.info("Nuitka前端服务器启动成功");
                startProcessMonitor();
                return true;
            } else {
                HekuosMod.LOGGER.warn("Nuitka前端服务器启动失败");
                return false;
            }

        } catch (Exception e) {
            HekuosMod.LOGGER.error("Nuitka二进制启动失败", e);
            return false;
        }
    }

    /**
     * 尝试使用Python解释器启动（fallback）
     * @return 是否启动成功
     */
    private boolean tryStartPythonScript() {
        Path scriptPath = webWorkDir.resolve("status_server.py");
        if (!Files.exists(scriptPath)) {
            HekuosMod.LOGGER.warn("未找到status_server.py脚本");
            return false;
        }

        // 搜索可用的Python解释器
        String pythonCmd = findPythonCommand();
        if (pythonCmd == null) {
            HekuosMod.LOGGER.warn("未找到Python 3解释器");
            return false;
        }

        try {
            HekuosMod.LOGGER.info("使用Python ({}) 启动前端服务器", pythonCmd);

            ProcessBuilder pb = new ProcessBuilder(
                pythonCmd, scriptPath.toAbsolutePath().toString(),
                String.valueOf(config.port),
                webWorkDir.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            pb.directory(webWorkDir.toFile());

            webProcess = pb.start();

            if (waitForStartup(webProcess, 10)) {
                HekuosMod.LOGGER.info("Python前端服务器启动成功");
                startProcessMonitor();
                return true;
            } else {
                HekuosMod.LOGGER.warn("Python前端服务器启动失败");
                return false;
            }

        } catch (Exception e) {
            HekuosMod.LOGGER.error("Python脚本启动失败", e);
            return false;
        }
    }

    /**
     * 搜索可用的Python 3解释器
     */
    private String findPythonCommand() {
        String[] candidates = {"python3", "python3.14", "python3.13", "python3.12", "python"};
        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream()))) {
                        String version = reader.readLine();
                        if (version != null && version.contains("Python 3")) {
                            HekuosMod.LOGGER.info("找到Python: {} ({})", cmd, version.trim());
                            return cmd;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 此解释器不可用，继续尝试下一个
            }
        }
        return null;
    }

    /**
     * 等待前端服务器启动
     * 检查进程输出中是否包含启动成功标识
     */
    private boolean waitForStartup(Process process, int timeoutSeconds) {
        // 读取进程输出（非阻塞）
        startOutputReader(process);

        // 等待一段时间让服务器启动
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 检查进程是否还在运行
        return process.isAlive();
    }

    /**
     * 启动进程输出读取线程
     */
    private void startOutputReader(Process process) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("HEKUO_WEB_OK:")) {
                        HekuosMod.LOGGER.info("前端服务器确认启动: {}", line);
                    } else if (line.startsWith("ERROR:")) {
                        HekuosMod.LOGGER.error("前端服务器错误: {}", line);
                    } else {
                        HekuosMod.LOGGER.debug("[Web] {}", line);
                    }
                }
            } catch (IOException e) {
                // 进程已关闭
            }
        }, "hekuos-web-reader").start();
    }

    /**
     * 启动进程监控 - 自动重启
     */
    private void startProcessMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            if (webProcess != null && !webProcess.isAlive()) {
                HekuosMod.LOGGER.warn("前端服务器进程已退出，尝试重启...");
                boolean restarted = tryStartNuitkaBinary() || tryStartPythonScript();
                if (!restarted) {
                    HekuosMod.LOGGER.error("前端服务器重启失败");
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 停止状态网页服务
     */
    public void stop() {
        if (webProcess != null && webProcess.isAlive()) {
            webProcess.destroy();
            try {
                if (!webProcess.waitFor(5, TimeUnit.SECONDS)) {
                    webProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                webProcess.destroyForcibly();
            }
        }

        if (statusApiServer != null) {
            statusApiServer.stop();
        }

        scheduler.shutdown();
        HekuosMod.LOGGER.info("服务器状态网页已停止");
    }

    /**
     * 生成Python HTTP服务器脚本
     */
    private String generatePythonScriptCode() {
        return """
#!/usr/bin/env python3
\"\"\"Hekuo's Mod - 服务器状态网页 HTTP服务器\"\"\"

import http.server
import socketserver
import sys
import os
import json
import urllib.request
import urllib.error
import signal

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
DIRECTORY = sys.argv[2] if len(sys.argv) > 2 else '.'

class ReuseAddrTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

class StatusHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def do_GET(self):
        if self.path == '/api/status':
            self._handle_status_api()
        elif self.path == '/api/health':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        else:
            if self.path == '/':
                self.path = '/index.html'
            super().do_GET()

    def _handle_status_api(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        try:
            api_url = f'http://localhost:{PORT + 1}/status'
            with urllib.request.urlopen(api_url, timeout=5) as response:
                self.wfile.write(response.read())
        except Exception as e:
            error = json.dumps({'online': False, 'error': str(e)}, ensure_ascii=False)
            self.wfile.write(error.encode('utf-8'))

    def log_message(self, format, *args):
        pass

httpd = None

def shutdown_handler(signum, frame):
    global httpd
    if httpd: httpd.shutdown()
    sys.exit(0)

if __name__ == '__main__':
    signal.signal(signal.SIGINT, shutdown_handler)
    signal.signal(signal.SIGTERM, shutdown_handler)
    try:
        httpd = ReuseAddrTCPServer(("", PORT), StatusHandler)
        print(f"HEKUO_WEB_OK:{PORT}")
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    except Exception as e:
        print(f"ERROR:{e}")
        sys.exit(1)
    finally:
        if httpd: httpd.server_close()
""";
    }

    /**
     * 生成HTML状态页面
     */
    private String generateHtmlPage() {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Minecraft 服务器状态</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
            color: #e0e0e0;
            min-height: 100vh;
            padding: 20px;
        }
        .container { max-width: 900px; margin: 0 auto; }
        .header {
            text-align: center;
            padding: 30px 0;
            border-bottom: 2px solid rgba(255,255,255,0.1);
            margin-bottom: 30px;
        }
        .header h1 {
            font-size: 2.5em;
            background: linear-gradient(90deg, #00d2ff, #3a7bd5);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin-bottom: 10px;
        }
        .header .subtitle { color: #888; font-size: 1.1em; }
        .status-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 20px;
            margin-bottom: 30px;
        }
        .status-card {
            background: rgba(255,255,255,0.05);
            border-radius: 12px;
            padding: 20px;
            text-align: center;
            border: 1px solid rgba(255,255,255,0.1);
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .status-card:hover {
            transform: translateY(-3px);
            box-shadow: 0 8px 25px rgba(0,0,0,0.3);
        }
        .status-card .value { font-size: 2.5em; font-weight: bold; margin: 10px 0; }
        .status-card .label { color: #888; font-size: 0.9em; }
        .status-card.online .value { color: #4caf50; }
        .status-card.players .value { color: #2196f3; }
        .status-card.tps .value { color: #ff9800; }
        .status-card.uptime .value { color: #9c27b0; }
        .player-list {
            background: rgba(255,255,255,0.05);
            border-radius: 12px;
            padding: 20px;
            border: 1px solid rgba(255,255,255,0.1);
        }
        .player-list h2 { margin-bottom: 15px; color: #2196f3; }
        .player-item {
            display: flex; align-items: center; padding: 10px; margin: 5px 0;
            background: rgba(255,255,255,0.03); border-radius: 8px;
        }
        .player-item:hover { background: rgba(255,255,255,0.08); }
        .player-avatar { width: 36px; height: 36px; border-radius: 50%; margin-right: 12px; background: #333; }
        .player-name { font-weight: 500; }
        .player-ping { margin-left: auto; color: #888; font-size: 0.85em; }
        .player-ping.good { color: #4caf50; }
        .player-ping.medium { color: #ff9800; }
        .player-ping.bad { color: #f44336; }
        .no-players { text-align: center; padding: 30px; color: #666; }
        .footer { text-align: center; margin-top: 30px; padding: 20px; color: #555; font-size: 0.85em; }
        .refresh-bar {
            height: 3px; background: linear-gradient(90deg, #00d2ff, #3a7bd5);
            width: 0%; transition: width 1s linear; margin-bottom: 20px; border-radius: 2px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Minecraft 服务器状态</h1>
            <div class="subtitle" id="server-motd">Hekuo's Mod Server</div>
        </div>
        <div class="refresh-bar" id="refresh-bar"></div>
        <div class="status-grid">
            <div class="status-card online">
                <div class="label">服务器状态</div>
                <div class="value" id="server-status">--</div>
            </div>
            <div class="status-card players">
                <div class="label">在线玩家</div>
                <div class="value" id="player-count">--</div>
            </div>
            <div class="status-card tps">
                <div class="label">TPS</div>
                <div class="value" id="server-tps">--</div>
            </div>
            <div class="status-card uptime">
                <div class="label">运行时间</div>
                <div class="value" id="uptime">--</div>
            </div>
        </div>
        <div class="player-list">
            <h2>在线玩家列表</h2>
            <div id="player-list-content"><div class="no-players">加载中...</div></div>
        </div>
        <div class="footer">Powered by Hekuo's Mod | 数据每5秒刷新</div>
    </div>
    <script>
        const RI = 5000;
        async function fetchStatus() {
            try {
                const r = await fetch('/api/status');
                const d = await r.json();
                updateUI(d);
            } catch(e) {
                document.getElementById('server-status').textContent = '离线';
                document.getElementById('server-status').style.color = '#f44336';
            }
            const bar = document.getElementById('refresh-bar');
            bar.style.width = '0%';
            setTimeout(() => bar.style.width = '100%', 100);
            setTimeout(fetchStatus, RI);
        }
        function updateUI(d) {
            const s = document.getElementById('server-status');
            if(d.online) { s.textContent='在线'; s.style.color='#4caf50'; }
            else { s.textContent='离线'; s.style.color='#f44336'; }
            if(d.motd) document.getElementById('server-motd').textContent=d.motd;
            document.getElementById('player-count').textContent=d.onlinePlayers+'/'+d.maxPlayers;
            const t=document.getElementById('server-tps');
            const tps=d.tps||0; t.textContent=tps.toFixed(1);
            t.style.color=tps>=18?'#4caf50':tps>=12?'#ff9800':'#f44336';
            document.getElementById('uptime').textContent=fmtTime(d.uptime||0);
            const pl=document.getElementById('player-list-content');
            if(d.players&&d.players.length>0){
                pl.innerHTML=d.players.map(p=>{
                    const c=p.ping<50?'good':p.ping<150?'medium':'bad';
                    return '<div class="player-item"><img class="player-avatar" src="https://mc-heads.net/avatar/'+p.name+'/36"><span class="player-name">'+p.name+'</span><span class="player-ping '+c+'">'+p.ping+'ms</span></div>';
                }).join('');
            } else { pl.innerHTML='<div class="no-players">暂无在线玩家</div>'; }
        }
        function fmtTime(s) {
            if(s<60) return s+'秒';
            if(s<3600) return Math.floor(s/60)+'分'+(s%60)+'秒';
            return Math.floor(s/3600)+'时'+Math.floor((s%3600)/60)+'分';
        }
        fetchStatus();
    </script>
</body>
</html>
""";
    }

    /**
     * 获取服务器状态数据的JSON
     */
    public String getServerStatusJson() {
        if (server == null) return "{\"online\":false}";

        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"online\":true,");
            json.append("\"motd\":\"").append(escapeJson(server.getServerMotd())).append("\",");
            json.append("\"version\":\"").append(server.getVersion()).append("\",");

            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            json.append("\"onlinePlayers\":").append(players.size()).append(",");
            json.append("\"maxPlayers\":").append(server.getMaxPlayerCount()).append(",");

            double tps = calculateTPS();
            json.append("\"tps\":").append(String.format("%.1f", tps)).append(",");

            long uptimeTicks = server.getOverworld().getTime();
            long uptimeSeconds = uptimeTicks / 20;
            json.append("\"uptime\":").append(uptimeSeconds).append(",");

            json.append("\"players\":[");
            for (int i = 0; i < players.size(); i++) {
                ServerPlayerEntity p = players.get(i);
                if (i > 0) json.append(",");
                json.append("{");
                json.append("\"name\":\"").append(escapeJson(p.getName().getString())).append("\",");
                json.append("\"ping\":").append(p.networkHandler != null ? p.networkHandler.latency : 0);
                json.append("}");
            }
            json.append("]}");
            return json.toString();
        } catch (Exception e) {
            return "{\"online\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private double calculateTPS() {
        try {
            long[] tickTimes = server.getTickTimes();
            if (tickTimes == null || tickTimes.length == 0) return 20.0;
            double average = 0;
            for (long time : tickTimes) average += time;
            average /= tickTimes.length;
            double tps = 1000000000.0 / Math.max(average, 50000000.0);
            return Math.min(tps, 20.0);
        } catch (Exception e) {
            return 20.0;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
