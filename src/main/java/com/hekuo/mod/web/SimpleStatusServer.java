package com.hekuo.mod.web;

import com.hekuo.mod.HekuosMod;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 简易HTTP状态API服务器
 * 使用JDK内置的HttpServer提供JSON状态数据
 * Python前端页面通过此API获取服务器状态
 */
public class SimpleStatusServer {

    private final MinecraftServer server;
    private final int port;
    private HttpServer httpServer;

    public SimpleStatusServer(MinecraftServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * 启动HTTP服务器
     */
    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);

        httpServer.createContext("/status", this::handleStatusRequest);

        httpServer.setExecutor(Executors.newSingleThreadExecutor());
        httpServer.start();

        HekuosMod.LOGGER.info("状态API服务器已启动在端口 {}", port);
    }

    /**
     * 停止HTTP服务器
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            HekuosMod.LOGGER.info("状态API服务器已停止");
        }
    }

    /**
     * 处理状态请求
     */
    private void handleStatusRequest(HttpExchange exchange) throws IOException {
        try {
            // 设置CORS头
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET");

            String json = StatusServerManager.getInstance().getServerStatusJson();
            byte[] response = json.getBytes("UTF-8");

            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        } catch (Exception e) {
            HekuosMod.LOGGER.error("处理状态请求失败", e);
            String error = "{\"error\":\"" + e.getMessage() + "\"}";
            byte[] response = error.getBytes("UTF-8");
            exchange.sendResponseHeaders(500, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
}
