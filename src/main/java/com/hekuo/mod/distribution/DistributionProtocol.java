package com.hekuo.mod.distribution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * UDP mod 分发协议 - 包类型常量与编解码工具
 *
 * 包格式: 首字节 = 类型码, 后续为类型特定载荷(二进制)
 *
 * 类型码:
 *  0x01 LIST_REQUEST   C->S  无载荷(请求清单)
 *  0x02 LIST_RESPONSE  S->C  UTF-8 JSON 清单
 *  0x03 FILE_REQUEST   C->S  UTF-8 文件名 + '\0' + 起始块号(4B int, big-endian)
 *  0x04 CHUNK          S->C  文件hash(4B) + 块号(4B) + 总块数(4B) + 数据
 *  0x05 ACK            C->S  文件hash(4B) + 块号(4B)
 *  0x06 COMPLETE       S->C  文件hash(4B)
 *  0x07 ERROR          S->C  UTF-8 错误消息
 *  0x08 DONE           C->S  无载荷(客户端全部完成,会话结束)
 */
public final class DistributionProtocol {

    public static final byte LIST_REQUEST = 0x01;
    public static final byte LIST_RESPONSE = 0x02;
    public static final byte FILE_REQUEST = 0x03;
    public static final byte CHUNK = 0x04;
    public static final byte ACK = 0x05;
    public static final byte COMPLETE = 0x06;
    public static final byte ERROR = 0x07;
    public static final byte DONE = 0x08;

    /** 单个 UDP 包最大字节数(含头部)。留足以太网 MTU 余量。 */
    public static final int MAX_PACKET = 1400;

    static final Gson GSON = new GsonBuilder().create();

    private DistributionProtocol() {}

    /** 读包类型码 */
    public static byte readType(DatagramPacket p) {
        return p.getData()[p.getOffset()];
    }

    // ---- LIST_REQUEST ----
    public static DatagramPacket buildListRequest(SocketAddress target) {
        byte[] data = new byte[] { LIST_REQUEST };
        return new DatagramPacket(data, data.length, target);
    }

    // ---- LIST_RESPONSE ----
    public static DatagramPacket buildListResponse(FileManifest manifest, SocketAddress target) {
        byte[] json = GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1 + json.length];
        data[0] = LIST_RESPONSE;
        System.arraycopy(json, 0, data, 1, json.length);
        return new DatagramPacket(data, data.length, target);
    }

    public static FileManifest parseListResponse(DatagramPacket p) {
        byte[] d = p.getData();
        int off = p.getOffset() + 1;
        int len = p.getLength() - 1;
        String json = new String(d, off, len, StandardCharsets.UTF_8);
        return GSON.fromJson(json, FileManifest.class);
    }

    // ---- FILE_REQUEST ----
    public static DatagramPacket buildFileRequest(String fileName, int startSeq, SocketAddress target) {
        byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1 + name.length + 1 + 4];
        int i = 0;
        data[i++] = FILE_REQUEST;
        System.arraycopy(name, 0, data, i, name.length);
        i += name.length;
        data[i++] = 0x00; // 文件名终止符
        writeIntBE(data, i, startSeq);
        return new DatagramPacket(data, data.length, target);
    }

    /** 返回 [fileName, startSeq];格式非法返回 null */
    public static Object[] parseFileRequest(DatagramPacket p) {
        byte[] d = p.getData();
        int off = p.getOffset() + 1;
        int end = p.getOffset() + p.getLength();
        int nul = -1;
        for (int i = off; i < end; i++) {
            if (d[i] == 0x00) { nul = i; break; }
        }
        if (nul < 0 || nul + 5 > end) return null;
        String name = new String(d, off, nul - off, StandardCharsets.UTF_8);
        int startSeq = readIntBE(d, nul + 1);
        return new Object[] { name, startSeq };
    }

    // ---- CHUNK ----
    // 布局: type(1) + fileHash(4) + seq(4) + total(4) + data
    public static DatagramPacket buildChunk(int fileHash, int seq, int total,
                                             byte[] chunkData, int chunkLen, SocketAddress target) {
        byte[] data = new byte[1 + 4 + 4 + 4 + chunkLen];
        int i = 0;
        data[i++] = CHUNK;
        writeIntBE(data, i, fileHash); i += 4;
        writeIntBE(data, i, seq); i += 4;
        writeIntBE(data, i, total); i += 4;
        System.arraycopy(chunkData, 0, data, i, chunkLen);
        return new DatagramPacket(data, data.length, target);
    }

    /** 返回 int[]{fileHash, seq, total},数据偏移通过 p.getOffset()+13 */
    public static int[] parseChunkHeader(DatagramPacket p) {
        byte[] d = p.getData();
        int off = p.getOffset();
        int fileHash = readIntBE(d, off + 1);
        int seq = readIntBE(d, off + 5);
        int total = readIntBE(d, off + 9);
        return new int[] { fileHash, seq, total };
    }

    public static int chunkDataLength(DatagramPacket p) {
        return p.getLength() - 13;
    }

    // ---- ACK ----
    public static DatagramPacket buildAck(int fileHash, int seq, SocketAddress target) {
        byte[] data = new byte[1 + 4 + 4];
        data[0] = ACK;
        writeIntBE(data, 1, fileHash);
        writeIntBE(data, 5, seq);
        return new DatagramPacket(data, data.length, target);
    }

    public static int[] parseAck(DatagramPacket p) {
        byte[] d = p.getData();
        int off = p.getOffset();
        return new int[] { readIntBE(d, off + 1), readIntBE(d, off + 5) };
    }

    // ---- COMPLETE ----
    public static DatagramPacket buildComplete(int fileHash, SocketAddress target) {
        byte[] data = new byte[1 + 4];
        data[0] = COMPLETE;
        writeIntBE(data, 1, fileHash);
        return new DatagramPacket(data, data.length, target);
    }

    public static int parseComplete(DatagramPacket p) {
        return readIntBE(p.getData(), p.getOffset() + 1);
    }

    // ---- ERROR ----
    public static DatagramPacket buildError(String message, SocketAddress target) {
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1 + msg.length];
        data[0] = ERROR;
        System.arraycopy(msg, 0, data, 1, msg.length);
        return new DatagramPacket(data, data.length, target);
    }

    public static String parseError(DatagramPacket p) {
        return new String(p.getData(), p.getOffset() + 1, p.getLength() - 1, StandardCharsets.UTF_8);
    }

    // ---- DONE ----
    public static DatagramPacket buildDone(SocketAddress target) {
        byte[] data = new byte[] { DONE };
        return new DatagramPacket(data, data.length, target);
    }

    // ---- big-endian int helpers ----
    static void writeIntBE(byte[] buf, int off, int v) {
        buf[off]     = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    static int readIntBE(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
             | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8)
             | (buf[off + 3] & 0xFF);
    }

    /** 文件名转稳定 hash(用作会话/路由 key),避免每次传完整文件名 */
    public static int fileNameHash(String name) {
        return name.hashCode();
    }

    /** 文件名校验:只允许 [A-Za-z0-9._-],防路径穿越 */
    public static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
