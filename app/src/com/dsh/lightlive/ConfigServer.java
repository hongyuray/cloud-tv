package com.dsh.lightlive;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;

/**
 * 手机推送用的极简 HTTP 服务：电视上开 8099 端口，
 * 手机浏览器打开 → 填 m3u 地址 → 推送 → 电视立即切换。
 */
public class ConfigServer {

    public interface Listener {
        String currentSource();

        String currentEpg();

        int channelCount();

        void onSourcePushed(String url);

        void onEpgPushed(String url);
    }

    private ServerSocket server;
    private volatile boolean running;
    private int port = -1;
    private Listener listener;

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running && port > 0;
    }

    public int start(Listener l) {
        listener = l;
        for (int p = 8099; p <= 8105 && !running; p++) {
            try {
                server = new ServerSocket(p);
                port = p;
                running = true;
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        loop();
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (Exception ignore) {
            }
        }
        return port;
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignore) {
        }
    }

    private void loop() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
                handle(s);
            } catch (Exception ignore) {
            } finally {
                try {
                    if (s != null) s.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void handle(Socket s) throws Exception {
        s.setSoTimeout(6000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String line = in.readLine();
        if (line == null) return;
        String path = "/";
        String[] parts = line.split(" ");
        if (parts.length >= 2) path = parts[1];
        while (true) {
            String h = in.readLine();
            if (h == null || h.length() == 0) break;
        }

        String body;
        String type = "text/html; charset=utf-8";
        if (path.startsWith("/api/set")) {
            String url = param(path, "url");
            if (url != null && url.length() > 0) listener.onSourcePushed(url);
            body = "{\"ok\":true}";
            type = "application/json; charset=utf-8";
        } else if (path.startsWith("/api/epg")) {
            String url = param(path, "url");
            if (url != null && url.length() > 0) listener.onEpgPushed(url);
            body = "{\"ok\":true}";
            type = "application/json; charset=utf-8";
        } else if (path.startsWith("/api/info")) {
            body = "{\"source\":\"" + esc(listener.currentSource()) + "\",\"epg\":\""
                    + esc(listener.currentEpg()) + "\",\"channels\":" + listener.channelCount() + "}";
            type = "application/json; charset=utf-8";
        } else {
            body = page();
        }
        byte[] data = body.getBytes("UTF-8");
        OutputStream os = s.getOutputStream();
        PrintWriter pw = new PrintWriter(new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(os, "UTF-8")), false);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: " + type + "\r\n");
        pw.print("Content-Length: " + data.length + "\r\n");
        pw.print("Cache-Control: no-store\r\n");
        pw.print("Connection: close\r\n\r\n");
        pw.flush();
        os.write(data);
        os.flush();
    }

    private static String param(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String qs = path.substring(q + 1);
        for (String kv : qs.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            if (kv.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && ip.startsWith("192.") || ip != null && ip.startsWith("10.")) return ip;
                        if (ip != null && ip.startsWith("172.")) return ip;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String page() {
        String cur = listener.currentSource();
        String epg = listener.currentEpg();
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>云电视 · 推送</title><style>"
                + "body{margin:0;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "background:#0b1220;color:#e6f0ff;padding:18px}"
                + "h1{font-size:19px;margin:6px 0 16px}"
                + ".card{background:#131c2e;border:1px solid #1f2c45;border-radius:14px;padding:16px;margin-bottom:14px}"
                + "label{display:block;font-size:13px;color:#8fa8c8;margin-bottom:8px}"
                + "input,textarea{width:100%;box-sizing:border-box;background:#0a1120;border:1px solid #26364f;"
                + "color:#e6f0ff;border-radius:10px;padding:12px;font-size:15px}"
                + "button{width:100%;margin-top:12px;padding:13px;border:0;border-radius:10px;"
                + "background:#1565c0;color:#fff;font-size:16px;font-weight:600}"
                + "button.ghost{background:#1c2a42;color:#9fc2ee;font-weight:500}"
                + ".tip{font-size:12px;color:#7e93af;margin-top:10px;line-height:1.6}"
                + ".ok{color:#4ade80}"
                + "</style></head><body>"
                + "<h1>云电视 · 手机推送</h1>"
                + "<div class=\"card\"><label>直播源地址（m3u / m3u8 订阅链接）</label>"
                + "<textarea id=\"u\" rows=\"3\">" + html(cur) + "</textarea>"
                + "<button onclick=\"push('/api/set','u','ok1')\">推送到电视</button>"
                + "<div class=\"tip\" id=\"ok1\">当前：" + html(cur) + "</div></div>"
                + "<div class=\"card\"><label>节目单地址（XMLTV，可留空）</label>"
                + "<textarea id=\"e\" rows=\"2\">" + html(epg) + "</textarea>"
                + "<button class=\"ghost\" onclick=\"push('/api/epg','e','ok2')\">保存节目单地址</button>"
                + "<div class=\"tip\" id=\"ok2\">频道数：" + listener.channelCount() + "</div></div>"
                + "<script>function push(a,id,st){var v=document.getElementById(id).value.trim();"
                + "fetch(a+'?url='+encodeURIComponent(v)).then(r=>r.json()).then(d=>{"
                + "document.getElementById(st).innerHTML='<span class=\"ok\">✓ 已推送到电视</span>';})"
                + ".catch(e=>{document.getElementById(st).textContent='✗ 推送失败：'+e;});}</script>"
                + "</body></html>";
    }

    private static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
