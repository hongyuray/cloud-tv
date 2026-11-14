package com.dsh.lightlive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3U 解析器：按「分组 + 频道名」聚合，同名频道的多条 URL 归并为多线路。
 */
public class M3UParser {

    private static final int MAX_LINES = 10;

    public static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<Channel>();
        if (text == null) return out;
        String[] lines = text.split("\r?\n");

        Map<String, Channel> map = new LinkedHashMap<String, Channel>();
        Set<String> seen = new HashSet<String>();

        String pendingName = null;
        String pendingGroup = "";
        String pendingId = "";
        String pendingCatchup = null;
        String pendingCatchupSource = null;
        int pendingDays = 0;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.length() == 0) continue;

            if (line.startsWith("#EXTINF")) {
                pendingGroup = attr(line, "group-title");
                pendingId = attr(line, "tvg-id");
                pendingCatchup = attr(line, "catchup");
                pendingCatchupSource = attr(line, "catchup-source");
                String days = attr(line, "catchup-days");
                pendingDays = 0;
                if (days != null) {
                    try {
                        pendingDays = Integer.parseInt(days.trim());
                    } catch (Exception ignore) {
                    }
                }
                String name = attr(line, "tvg-name");
                int comma = line.lastIndexOf(',');
                String tail = comma >= 0 ? line.substring(comma + 1).trim() : "";
                if (tail.length() > 0) name = tail;
                if (name == null || name.length() == 0) name = "未命名";
                pendingName = name.trim();
                continue;
            }
            if (line.startsWith("#")) continue;

            if (pendingName != null && (line.startsWith("http://") || line.startsWith("https://")
                    || line.startsWith("rtmp://"))) {
                if (!isJunk(pendingName)) {
                    String g = (pendingGroup == null || pendingGroup.length() == 0) ? "其他" : pendingGroup;
                    String key = g + "\u0001" + pendingName;
                    Channel c = map.get(key);
                    if (c == null) {
                        c = new Channel(pendingName, g,
                                pendingId == null ? "" : pendingId.trim(),
                                pendingCatchup, pendingCatchupSource, pendingDays);
                        map.put(key, c);
                        out.add(c);
                    }
                    String u = line;
                    if (!seen.contains(key + "\u0002" + u) && c.urls.size() < MAX_LINES) {
                        seen.add(key + "\u0002" + u);
                        c.urls.add(u);
                    }
                }
                pendingName = null;
                pendingGroup = "";
                pendingId = "";
                pendingCatchup = null;
                pendingCatchupSource = null;
            }
        }

        List<Channel> result = new ArrayList<Channel>();
        for (Channel c : out) {
            if (!c.urls.isEmpty()) result.add(c);
        }
        return result;
    }

    /** 过滤“更新时间”之类的伪频道 */
    private static boolean isJunk(String name) {
        if (name == null) return true;
        String n = name.trim();
        if (n.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?")) return true;
        if (n.equalsIgnoreCase("更新时间") || n.startsWith("更新时")) return true;
        return false;
    }

    private static String attr(String line, String key) {
        String k = key + "=\"";
        int i = line.indexOf(k);
        if (i < 0) return null;
        int s = i + k.length();
        int e = line.indexOf('"', s);
        if (e < 0) return null;
        return line.substring(s, e).trim();
    }
}
