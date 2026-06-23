package com.hekuo.mod.distribution;

import java.util.ArrayList;
import java.util.List;

/**
 * mod 文件清单 - 服务端 mods 目录扫描结果
 * 序列化为 JSON 发给客户端
 */
public class FileManifest {

    /** 单个文件条目 */
    public static class Entry {
        public String name;
        public long size;
        public String sha256;

        public Entry() {}

        public Entry(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public List<Entry> files = new ArrayList<>();

    public FileManifest() {}

    public FileManifest(List<Entry> files) {
        this.files = files;
    }
}
