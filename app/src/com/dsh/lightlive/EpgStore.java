package com.dsh.lightlive;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 极简 XMLTV 节目单：只保留近期节目，按频道 id 索引。
 */
public class EpgStore {

    public static class Program {
        public final long start;
        public final long stop;
        public final String title;
        public final String desc;

        Program(long s, long e, String t, String d) {
            start = s;
            stop = e;
            title = t;
            desc = d;
        }
    }

    private final Map<String, List<Program>> byChannel = new HashMap<String, List<Program>>();
    private final Map<String, List<Program>> byNorm = new HashMap<String, List<Program>>();
    private volatile boolean ready = false;
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    public boolean isReady() {
        return ready;
    }

    public synchronized void clear() {
        byChannel.clear();
        byNorm.clear();
        ready = false;
    }

    private static String norm(String id) {
        if (id == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toUpperCase(ch));
        }
        return sb.toString();
    }

    public synchronized int channelCount() {
        return byChannel.size();
    }

    public synchronized List<Program> programs(String channelId) {
        if (channelId == null) return new ArrayList<Program>();
        List<Program> l = byChannel.get(channelId);
        if (l == null) l = byNorm.get(norm(channelId));
        return l == null ? new ArrayList<Program>() : l;
    }

    public synchronized Program now(String channelId) {
        long t = System.currentTimeMillis();
        for (Program p : programs(channelId)) {
            if (p.start <= t && t < p.stop) return p;
        }
        return null;
    }

    public synchronized Program next(String channelId) {
        long t = System.currentTimeMillis();
        for (Program p : programs(channelId)) {
            if (p.start > t) return p;
        }
        return null;
    }

    /** 解析 XMLTV；失败返回 false */
    public boolean parse(String xml) {
        Map<String, List<Program>> map = new HashMap<String, List<Program>>();
        long now = System.currentTimeMillis();
        long from = now - 6L * 3600 * 1000;
        long to = now + 36L * 3600 * 1000;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            int ev = p.getEventType();
            String chId = null;
            long start = 0, stop = 0;
            StringBuilder text = null;
            String title = null, desc = null;
            String tag = null;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("programme".equals(tag)) {
                        chId = p.getAttributeValue(null, "channel");
                        start = parseTime(p.getAttributeValue(null, "start"));
                        stop = parseTime(p.getAttributeValue(null, "stop"));
                        title = null;
                        desc = null;
                        text = null;
                    } else if ("title".equals(tag) || "desc".equals(tag)) {
                        text = new StringBuilder();
                    }
                } else if (ev == XmlPullParser.TEXT && text != null) {
                    text.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = p.getName();
                    if ("title".equals(name) && text != null) {
                        title = text.toString().trim();
                        text = null;
                    } else if ("desc".equals(name) && text != null) {
                        desc = text.toString().trim();
                        text = null;
                    } else if ("programme".equals(name)) {
                        if (chId != null && start > 0 && stop > start && stop > from && start < to) {
                            List<Program> l = map.get(chId);
                            if (l == null) {
                                l = new ArrayList<Program>();
                                map.put(chId, l);
                            }
                            l.add(new Program(start, stop, title == null ? "" : title,
                                    desc == null ? "" : desc));
                        }
                        chId = null;
                        text = null;
                    }
                }
                ev = p.next();
            }
        } catch (Exception e) {
            return false;
        }

        for (List<Program> l : map.values()) {
            Collections.sort(l, new Comparator<Program>() {
                public int compare(Program a, Program b) {
                    return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0);
                }
            });
        }
        Map<String, List<Program>> nm = new HashMap<String, List<Program>>();
        for (Map.Entry<String, List<Program>> e : map.entrySet()) {
            String k = norm(e.getKey());
            if (k.length() > 0 && !nm.containsKey(k)) nm.put(k, e.getValue());
        }
        synchronized (this) {
            byChannel.clear();
            byChannel.putAll(map);
            byNorm.clear();
            byNorm.putAll(nm);
            ready = !map.isEmpty();
        }
        return ready;
    }

    private static long parseTime(String s) {
        if (s == null) return 0;
        String v = s.trim();
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
            f.setTimeZone(TZ);
            return f.parse(v).getTime();
        } catch (Exception ignore) {
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(TZ);
            return f.parse(v.substring(0, 14)).getTime();
        } catch (Exception ignore) {
        }
        return 0;
    }

    public static String hhmm(long t) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TZ);
        return f.format(new java.util.Date(t));
    }

    public static void readFile(File f, StringBuilder out) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            out.append(bos.toString("UTF-8"));
        } catch (Exception ignore) {
        }
    }
}
