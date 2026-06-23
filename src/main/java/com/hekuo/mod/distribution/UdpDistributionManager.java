package com.hekuo.mod.distribution;

import com.hekuo.mod.HekuosMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * UDP mod 分发服务端管理器(单例)
 *
 * 职责:
 *  - 扫描服务端 mods 目录生成清单(文件名/大小/sha256)
 *  - 缓存文件字节(供发送引擎使用)
 *  - 启动/停止 UdpDistributionServer
 *
 * 参照 web/StatusServerManager 单例 + start/stop 生命周期模式。
 */
public class UdpDistributionManager {

    private static UdpDistributionManager instance;

    private final Map<String, byte[]> fileDataCache = new HashMap<>();
    private FileManifest manifest = new FileManifest();
    private DatagramSocket socket;
    private UdpDistributionServer server;
    private volatile boolean started = false;

    private UdpDistributionManager() {}

    public static synchronized UdpDistributionManager getInstance() {
        if (instance == null) instance = new UdpDistributionManager();
        return instance;
    }

    /**
     * 启动 UDP 分发服务
     * @param port UDP 端口
     * @param chunkSize 块大小
     * @param ackTimeoutMs ACK 超时
     * @param maxRetries 单块最大重试
     * @param maxFileSizeMb 单文件大小上限(MB)
     */
    public void start(int port, int chunkSize, int ackTimeoutMs, int maxRetries, int maxFileSizeMb) {
        if (started) {
            HekuosMod.LOGGER.warn("[UdpManager] 已启动, 忽略重复 start");
            return;
        }
        try {
            // 1. 扫描 mods 目录
            scanModsDirectory(maxFileSizeMb);
            HekuosMod.LOGGER.info("[UdpManager] 已扫描 {} 个文件, 总 {} 字节",
                manifest.files.size(), totalSize());

            // 2. 绑定 UDP 端口
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));

            // 3. 启动发送引擎
            UdpDistributionServer.ModConfigHolder holder =
                new UdpDistributionServer.ModConfigHolder(chunkSize, ackTimeoutMs, maxRetries, maxFileSizeMb);
            server = new UdpDistributionServer(socket, manifest, fileDataCache, holder);
            server.start();
            started = true;
            HekuosMod.LOGGER.info("[UdpManager] UDP 分发服务启动于端口 {}", port);
        } catch (Exception e) {
            HekuosMod.LOGGER.error("[UdpManager] 启动失败", e);
            stop();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        socket = null;
        fileDataCache.clear();
        manifest = new FileManifest();
        started = false;
    }

    private void scanModsDirectory(int maxFileSizeMb) throws IOException {
        fileDataCache.clear();
        manifest = new FileManifest();

        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            HekuosMod.LOGGER.warn("[UdpManager] mods 目录不存在: {}", modsDir);
            return;
        }

        long maxBytes = (long) maxFileSizeMb * 1024 * 1024;
        try (Stream<Path> paths = Files.list(modsDir)) {
            List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
            for (Path f : files) {
                String name = f.getFileName().toString();
                // 只同步 .jar(及 .jar.disabled 等),跳过非 mod 文件
                if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) continue;
                // 文件名安全校验
                if (!DistributionProtocol.isSafeFileName(name)) {
                    HekuosMod.LOGGER.warn("[UdpManager] 跳过非安全文件名: {}", name);
                    continue;
                }
                long size = Files.size(f);
                if (size > maxBytes) {
                    HekuosMod.LOGGER.warn("[UdpManager] 跳过超大文件 ({} MB > {} MB): {}",
                        size / 1024 / 1024, maxFileSizeMb, name);
                    continue;
                }
                byte[] data = Files.readAllBytes(f);
                String sha = sha256Hex(data);
                fileDataCache.put(name, data);
                manifest.files.add(new FileManifest.Entry(name, size, sha));
            }
        }
    }

    private long totalSize() {
        long sum = 0;
        for (FileManifest.Entry e : manifest.files) sum += e.size;
        return sum;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
