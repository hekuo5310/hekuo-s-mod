package com.hekuo.mod.distribution;

import com.hekuo.mod.HekuosMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP mod 分发客户端
 *
 * 进服后自动:
 *  1. 向服务端 UDP 端口请求清单
 *  2. 对比本地 mods/hekuos-mod-sync/ 已有文件 sha256
 *  3. 缺失/过期文件逐个用停等协议接收
 *  4. 完成后聊天提示重启
 *
 * 异步线程跑,不阻塞 MC 主线程。
 */
public class UdpDistributionClient {

    private static UdpDistributionClient instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hekuos-udp-client");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    private UdpDistributionClient() {}

    public static synchronized UdpDistributionClient getInstance() {
        if (instance == null) instance = new UdpDistributionClient();
        return instance;
    }

    /**
     * 开始同步
     * @param serverHost 服务端 UDP 主机(空=用当前连接的服务器 IP)
     * @param port UDP 端口
     * @param chunkSize 块大小
     * @param ackTimeoutMs ACK 超时
     * @param maxRetries 单块最大重试
     * @param maxFileSizeMb 单文件大小上限
     */
    public void startSync(String serverHost, int port, int chunkSize,
                          int ackTimeoutMs, int maxRetries, int maxFileSizeMb) {
        if (!syncing.compareAndSet(false, true)) {
            HekuosMod.LOGGER.info("[UdpClient] 同步进行中, 忽略");
            return;
        }
        executor.submit(() -> {
            try {
                doSync(serverHost, port, chunkSize, ackTimeoutMs, maxRetries, maxFileSizeMb);
            } catch (Exception e) {
                HekuosMod.LOGGER.error("[UdpClient] 同步失败", e);
                chatNotify("mod 同步失败: " + e.getMessage(), Formatting.RED);
            } finally {
                syncing.set(false);
            }
        });
    }

    private void doSync(String serverHost, int port, int chunkSize,
                        int ackTimeoutMs, int maxRetries, int maxFileSizeMb) throws Exception {
        // 1. 解析服务端地址
        String host = serverHost;
        if (host == null || host.isEmpty()) {
            host = resolveCurrentServerHost();
            if (host == null) {
                HekuosMod.LOGGER.warn("[UdpClient] 无法确定服务端地址, 跳过同步");
                return;
            }
        }
        InetSocketAddress serverAddr = new InetSocketAddress(host, port);
        HekuosMod.LOGGER.info("[UdpClient] 开始同步, 服务端 {}:{}", host, port);

        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(ackTimeoutMs);

        try {
            // 2. 请求清单
            FileManifest manifest = requestManifest(socket, serverAddr, ackTimeoutMs, maxRetries);
            if (manifest == null || manifest.files.isEmpty()) {
                chatNotify("服务端无可同步的 mod", Formatting.YELLOW);
                return;
            }
            HekuosMod.LOGGER.info("[UdpClient] 收到清单, {} 个文件", manifest.files.size());

            // 3. 准备同步目录
            Path syncDir = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("hekuos-mod-sync");
            Files.createDirectories(syncDir);

            // 4. 对比本地, 算出需下载
            List<FileManifest.Entry> toDownload = new ArrayList<>();
            for (FileManifest.Entry e : manifest.files) {
                if (!DistributionProtocol.isSafeFileName(e.name)) {
                    HekuosMod.LOGGER.warn("[UdpClient] 跳过非安全文件名: {}", e.name);
                    continue;
                }
                Path local = syncDir.resolve(e.name);
                if (Files.exists(local) && sha256Hex(Files.readAllBytes(local)).equals(e.sha256)) {
                    continue; // 已是最新
                }
                toDownload.add(e);
            }

            if (toDownload.isEmpty()) {
                chatNotify("mod 已是最新, 无需下载", Formatting.GREEN);
                return;
            }

            // 5. 逐个下载
            int success = 0;
            for (FileManifest.Entry e : toDownload) {
                boolean ok = downloadFile(socket, serverAddr, e, syncDir, chunkSize,
                    ackTimeoutMs, maxRetries, maxFileSizeMb);
                if (ok) success++;
                else HekuosMod.LOGGER.warn("[UdpClient] 文件 {} 下载失败", e.name);
            }

            // 6. 发 DONE
            try {
                socket.send(DistributionProtocol.buildDone(serverAddr));
            } catch (IOException ignored) {}

            // 7. 提示
            if (success > 0) {
                chatNotify(String.format("已下载 %d/%d 个 mod, 请重启游戏生效", success, toDownload.size()),
                    Formatting.GOLD);
            } else {
                chatNotify("mod 下载全部失败, 详见日志", Formatting.RED);
            }
        } finally {
            socket.close();
        }
    }

    /** 请求清单(带重试) */
    private FileManifest requestManifest(DatagramSocket socket, InetSocketAddress serverAddr,
                                          int timeoutMs, int maxRetries) throws IOException {
        DatagramPacket req = DistributionProtocol.buildListRequest(serverAddr);
        byte[] buf = new byte[DistributionProtocol.MAX_PACKET];
        for (int i = 0; i < maxRetries; i++) {
            socket.send(req);
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(resp);
                if (DistributionProtocol.readType(resp) == DistributionProtocol.LIST_RESPONSE) {
                    return DistributionProtocol.parseListResponse(resp);
                }
            } catch (SocketTimeoutException ste) {
                HekuosMod.LOGGER.debug("[UdpClient] 清单请求超时, 重试 {}/{}", i + 1, maxRetries);
            }
        }
        return null;
    }

    /** 停等下载单个文件 */
    private boolean downloadFile(DatagramSocket socket, InetSocketAddress serverAddr,
                                  FileManifest.Entry entry, Path syncDir, int chunkSize,
                                  int timeoutMs, int maxRetries, int maxFileSizeMb) throws IOException {
        int fileHash = DistributionProtocol.fileNameHash(entry.name);
        Path partFile = syncDir.resolve(entry.name + ".part");
        Path finalFile = syncDir.resolve(entry.name);

        // 发 FILE_REQUEST 从块 0 开始
        DatagramPacket req = DistributionProtocol.buildFileRequest(entry.name, 0, serverAddr);
        socket.send(req);

        // 收集块: seq -> data
        Map<Integer, byte[]> chunks = new HashMap<>();
        int totalChunks = -1;
        byte[] buf = new byte[DistributionProtocol.MAX_PACKET];

        // 接收循环:收 CHUNK 回 ACK, 收 COMPLETE 结束
        int consecutiveTimeouts = 0;
        while (consecutiveTimeouts < maxRetries) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(pkt);
                consecutiveTimeouts = 0;
            } catch (SocketTimeoutException ste) {
                consecutiveTimeouts++;
                // 超时:若已收过块但没 COMPLETE,重发最近未确认块的请求?简单起见重发 FILE_REQUEST 续传
                if (!chunks.isEmpty()) {
                    int nextSeq = chunks.size(); // 简化:请求下一块
                    socket.send(DistributionProtocol.buildFileRequest(entry.name, nextSeq, serverAddr));
                }
                continue;
            }

            byte type = DistributionProtocol.readType(pkt);
            if (type == DistributionProtocol.CHUNK) {
                int[] hdr = DistributionProtocol.parseChunkHeader(pkt);
                int hash = hdr[0], seq = hdr[1], total = hdr[2];
                if (hash != fileHash) continue; // 非本文件
                totalChunks = total;
                int dataLen = DistributionProtocol.chunkDataLength(pkt);
                byte[] data = new byte[dataLen];
                System.arraycopy(pkt.getData(), pkt.getOffset() + 13, data, 0, dataLen);
                chunks.put(seq, data);
                // 回 ACK
                socket.send(DistributionProtocol.buildAck(fileHash, seq, serverAddr));
            } else if (type == DistributionProtocol.COMPLETE) {
                int hash = DistributionProtocol.parseComplete(pkt);
                if (hash == fileHash) break;
            } else if (type == DistributionProtocol.ERROR) {
                HekuosMod.LOGGER.warn("[UdpClient] 服务端错误: {}", DistributionProtocol.parseError(pkt));
                return false;
            }
        }

        if (totalChunks < 0 || chunks.size() < totalChunks) {
            HekuosMod.LOGGER.warn("[UdpClient] 文件 {} 块不全: {}/{}", entry.name, chunks.size(), totalChunks);
            return false;
        }

        // 拼接写临时文件
        try (var out = Files.newOutputStream(partFile)) {
            for (int i = 0; i < totalChunks; i++) {
                byte[] d = chunks.get(i);
                if (d == null) {
                    HekuosMod.LOGGER.warn("[UdpClient] 文件 {} 缺块 {}", entry.name, i);
                    return false;
                }
                out.write(d);
            }
        }

        // 校验 sha256
        byte[] all = Files.readAllBytes(partFile);
        if (all.length > (long) maxFileSizeMb * 1024 * 1024) {
            Files.deleteIfExists(partFile);
            HekuosMod.LOGGER.warn("[UdpClient] 文件 {} 超过大小上限", entry.name);
            return false;
        }
        String sha = sha256Hex(all);
        if (!sha.equals(entry.sha256)) {
            Files.deleteIfExists(partFile);
            HekuosMod.LOGGER.warn("[UdpClient] 文件 {} 校验失败: 期望 {} 实际 {}", entry.name, entry.sha256, sha);
            return false;
        }

        // 校验通过,改名落地
        Files.deleteIfExists(finalFile);
        Files.move(partFile, finalFile);
        HekuosMod.LOGGER.info("[UdpClient] 文件 {} 下载完成 ({} 字节)", entry.name, all.length);
        return true;
    }

    /** 取当前连接的服务器主机 */
    private String resolveCurrentServerHost() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getCurrentServerEntry() != null) {
                return mc.getCurrentServerEntry().address;
            }
        } catch (Throwable t) {
            HekuosMod.LOGGER.debug("[UdpClient] 解析服务器地址失败", t);
        }
        return null;
    }

    private void chatNotify(String message, Formatting color) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                ClientPlayerEntity player = mc.player;
                if (player != null) {
                    player.sendMessage(Text.literal("[Mod同步] " + message).formatted(color), false);
                }
            });
        }
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
