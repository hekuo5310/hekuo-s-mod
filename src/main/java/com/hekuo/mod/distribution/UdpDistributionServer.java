package com.hekuo.mod.distribution;

import com.hekuo.mod.HekuosMod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * UDP mod 分发服务端发送引擎
 *
 * 接收循环跑在单线程,按包类型分发:
 *  - LIST_REQUEST -> 回清单
 *  - FILE_REQUEST -> 为该 (客户端,文件) 启停等发送循环(每客户端单线程串行)
 *  - ACK -> 唤醒对应发送循环前进到下一块
 *  - DONE -> 清理该客户端会话
 *
 * 停等:发一块,等该 seq 的 ACK,超时重发,最大 maxRetries 次后放弃。
 */
public class UdpDistributionServer {

    private final DatagramSocket socket;
    private final FileManifest manifest;
    private final Map<String, byte[]> fileDataCache;   // 文件名 -> 全文件字节(小文件缓存)
    private final ModConfigHolder config;

    /** 每个 (fileHash) 的进行中发送会话 */
    private final Map<Integer, SendSession> sessions = new ConcurrentHashMap<>();

    private final ExecutorService sendExecutor = Executors.newCachedThreadPool();
    private final ExecutorService receiveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hekuos-udp-server-recv");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;

    public UdpDistributionServer(DatagramSocket socket, FileManifest manifest,
                                  Map<String, byte[]> fileDataCache, ModConfigHolder config) {
        this.socket = socket;
        this.manifest = manifest;
        this.fileDataCache = fileDataCache;
        this.config = config;
    }

    public void start() {
        running = true;
        receiveExecutor.submit(this::receiveLoop);
        HekuosMod.LOGGER.info("[UdpServer] 分发服务端已启动, 监听 {}", socket.getLocalSocketAddress());
    }

    public void stop() {
        running = false;
        sendExecutor.shutdownNow();
        receiveExecutor.shutdownNow();
        sessions.values().forEach(s -> {
            s.lock.lock();
            try { s.cond.signalAll(); } finally { s.lock.unlock(); }
        });
        sessions.clear();
    }

    private void receiveLoop() {
        byte[] buf = new byte[DistributionProtocol.MAX_PACKET];
        while (running && !Thread.currentThread().isInterrupted()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) HekuosMod.LOGGER.warn("[UdpServer] receive 异常", e);
                break;
            }
            try {
                handlePacket(packet);
            } catch (Exception e) {
                HekuosMod.LOGGER.warn("[UdpServer] 处理包异常", e);
            }
        }
    }

    private void handlePacket(DatagramPacket packet) throws IOException {
        byte type = DistributionProtocol.readType(packet);
        SocketAddress client = packet.getSocketAddress();

        switch (type) {
            case DistributionProtocol.LIST_REQUEST:
                socket.send(DistributionProtocol.buildListResponse(manifest, client));
                break;

            case DistributionProtocol.FILE_REQUEST: {
                Object[] req = DistributionProtocol.parseFileRequest(packet);
                if (req == null) {
                    socket.send(DistributionProtocol.buildError("格式非法的 FILE_REQUEST", client));
                    return;
                }
                String fileName = (String) req[0];
                int startSeq = (Integer) req[1];
                handleFileRequest(fileName, startSeq, client);
                break;
            }

            case DistributionProtocol.ACK: {
                int[] ack = DistributionProtocol.parseAck(packet);
                int fileHash = ack[0];
                int seq = ack[1];
                SendSession s = sessions.get(fileHash);
                if (s != null && s.client.equals(client)) {
                    s.lock.lock();
                    try {
                        if (seq == s.expectedSeq) {
                            s.acked = true;
                            s.cond.signal();
                        }
                        // 重复 ACK 忽略(幂等)
                    } finally { s.lock.unlock(); }
                }
                break;
            }

            case DistributionProtocol.DONE: {
                // 客户端完成,清理该客户端所有会话
                sessions.entrySet().removeIf(e -> e.getValue().client.equals(client));
                break;
            }

            default:
                // 未知类型,忽略
                break;
        }
    }

    private void handleFileRequest(String fileName, int startSeq, SocketAddress client) throws IOException {
        // 校验文件名
        if (!DistributionProtocol.isSafeFileName(fileName)) {
            socket.send(DistributionProtocol.buildError("非法文件名: " + fileName, client));
            return;
        }
        // 查清单
        FileManifest.Entry entry = null;
        for (FileManifest.Entry e : manifest.files) {
            if (fileName.equals(e.name)) { entry = e; break; }
        }
        if (entry == null) {
            socket.send(DistributionProtocol.buildError("文件不在清单: " + fileName, client));
            return;
        }
        // 大小限制
        if (entry.size > (long) config.maxFileSizeMb * 1024 * 1024) {
            socket.send(DistributionProtocol.buildError("文件过大: " + fileName, client));
            return;
        }

        byte[] data = fileDataCache.get(fileName);
        if (data == null) {
            socket.send(DistributionProtocol.buildError("文件数据不可用: " + fileName, client));
            return;
        }

        int fileHash = DistributionProtocol.fileNameHash(fileName);
        int totalChunks = (int) ((data.length + config.chunkSize - 1) / config.chunkSize);

        // 已有该文件会话则忽略(防止重复请求)
        if (sessions.containsKey(fileHash)) {
            HekuosMod.LOGGER.debug("[UdpServer] 文件 {} 会话已存在, 忽略重复请求", fileName);
            return;
        }

        SendSession session = new SendSession(client, startSeq);
        sessions.put(fileHash, session);

        sendExecutor.submit(() -> sendFileLoop(fileHash, fileName, data, totalChunks, session));
    }

    /** 停等发送循环 */
    private void sendFileLoop(int fileHash, String fileName, byte[] data,
                              int totalChunks, SendSession session) {
        try {
            for (int seq = session.expectedSeq; seq < totalChunks; seq++) {
                int off = seq * config.chunkSize;
                int len = Math.min(config.chunkSize, data.length - off);
                DatagramPacket chunkPkt = DistributionProtocol.buildChunk(
                    fileHash, seq, totalChunks, data, len, session.client);

                session.lock.lock();
                try {
                    session.acked = false;
                    session.expectedSeq = seq;
                } finally { session.lock.unlock(); }

                int retries = 0;
                boolean got = false;
                while (retries < config.maxRetries && running) {
                    try {
                        socket.send(chunkPkt);
                    } catch (IOException e) {
                        HekuosMod.LOGGER.warn("[UdpServer] 发送 chunk {} 失败", seq, e);
                        break;
                    }
                    // 等 ACK
                    session.lock.lock();
                    try {
                        if (session.acked) { got = true; break; }
                        boolean signaled = session.cond.await(config.ackTimeoutMs, TimeUnit.MILLISECONDS);
                        if (session.acked) { got = true; break; }
                        if (!signaled) retries++; // 超时,重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally { session.lock.unlock(); }
                }
                if (!got) {
                    HekuosMod.LOGGER.warn("[UdpServer] 文件 {} 块 {} 超时放弃", fileName, seq);
                    sessions.remove(fileHash);
                    return;
                }
            }

            // 全部发完,发 COMPLETE
            try {
                socket.send(DistributionProtocol.buildComplete(fileHash, session.client));
            } catch (IOException e) {
                HekuosMod.LOGGER.warn("[UdpServer] 发送 COMPLETE 失败: {}", fileName, e);
            }
            // 不立即移除会话,等客户端 DONE 或超时;此处保留短时间防重复
            HekuosMod.LOGGER.info("[UdpServer] 文件 {} 分发完成 ({}/{} 块)", fileName, totalChunks, totalChunks);
        } finally {
            // 短延迟后清理,容忍 COMPLETE 丢失后客户端重请求
            sessions.remove(fileHash);
        }
    }

    /** 单文件发送会话状态 */
    private static class SendSession {
        final SocketAddress client;
        int expectedSeq;       // 当前等待 ACK 的块号
        boolean acked;         // 该块是否已 ACK
        final ReentrantLock lock = new ReentrantLock();
        final Condition cond = lock.newCondition();

        SendSession(SocketAddress client, int startSeq) {
            this.client = client;
            this.expectedSeq = startSeq;
        }
    }

    /** 配置持有(避免循环引用,仅取需要的字段) */
    public static class ModConfigHolder {
        public final int chunkSize;
        public final int ackTimeoutMs;
        public final int maxRetries;
        public final int maxFileSizeMb;

        public ModConfigHolder(int chunkSize, int ackTimeoutMs, int maxRetries, int maxFileSizeMb) {
            this.chunkSize = chunkSize;
            this.ackTimeoutMs = ackTimeoutMs;
            this.maxRetries = maxRetries;
            this.maxFileSizeMb = maxFileSizeMb;
        }
    }
}
