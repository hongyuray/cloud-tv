package com.dsh.lightlive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻直播 1.1 —— 极简直播源播放器
 * 遥控器：上下选台 / OK 播放 / 左键 节目单 / 返回 列表 / 菜单 设置
 */
public class MainActivity extends Activity implements ConfigServer.Listener {

    private static final String PREFS = "lightlive";
    private static final String KEY_URL = "source_url";
    private static final String KEY_EPG = "epg_url";
    private static final String KEY_TIMEOUT = "connect_timeout";
    private static final String KEY_LIVE_ONLY = "live_only";
    private static final String KEY_AUTOLINE = "auto_line";
    private static final String KEY_AUTO_PLAY = "auto_play";
    private static final String KEY_FAV = "favorites";
    private static final String KEY_EPG_ON = "epg_on";
    private static final String KEY_LAST = "last_url";
    private static final String ROW_SETTINGS = "设置 / 换源    >";

    private static final String BUILTIN = "builtin";
    private static final String[] PRESETS = new String[]{
            BUILTIN,
            "https://jsd.onmicrosoft.cn/gh/Guovin/iptv-api@gd/output/result.m3u",
            "https://live.zbds.top/tv/iptv4.m3u",
            "https://jsd.onmicrosoft.cn/gh/fanmingming/live@main/tv/m3u/itv.m3u",
            "https://jsd.onmicrosoft.cn/gh/kimentanm/aptv@master/m3u/iptv.m3u"
    };
    private static final String[] PRESET_NAMES = new String[]{
            "源一 · 内置精选（已实测 / 离线）",
            "源二 · 综合大源（1600+ 条）",
            "源三 · 国内直连（卫视/地方）",
            "源四 · 央卫（移动 CDN）",
            "源五 · 央卫精选（小）"
    };
    private static final String DEFAULT_EPG = "http://htwmedia.dpdns.org/Home/getepg?days=7";

    private FrameLayout root;
    private VideoView video;
    private ListView list;
    private TextView status;
    private TextView hint;
    private LinearLayout menuBar;
    private final List<Button> menuButtons = new ArrayList<Button>();
    private final List<Integer> menuActions = new ArrayList<Integer>();
    private int col = 0;
    private int listSel = -1;
    private int menuSel = 0;
    private TextView liveTag;
    private boolean catchupMode;
    private String catchupLabel = "";
    private TextView connText;
    private long connectStart;
    private Channel connectChannel;
    private int connectLine;
    private int connectSeq = 0;
    private SharedPreferences prefs;

    private final List<Object> rows = new ArrayList<Object>();
    private final List<Channel> channels = new ArrayList<Channel>();
    private RowAdapter adapter;

    private int playingIndex = -1;
    private boolean listVisible = true;
    private boolean playing = false;
    private int failover = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EpgStore epg = new EpgStore();
    private ConfigServer server;
    private FrameLayout panel;
    private Channel epgLoadingFor;
    private boolean epgTried;
    private boolean autoPlayed = false;
    private String lastUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 触摸屏设备（手机）走手机版界面
        if (getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) {
            startActivity(new android.content.Intent(this, PhoneActivity.class));
            finish();
            return;
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 1.3 起默认内置精选，老版本升级时把失效的 gh-proxy 源换成内置源
        if (prefs.getInt("prefs_ver", 0) < 4) {
            prefs.edit().putString(KEY_URL, BUILTIN).putInt("prefs_ver", 4).apply();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        lastUrl = prefs.getString(KEY_LAST, null);
        server = new ConfigServer();
        server.start(this);
        loadSource(false);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private StateListDrawable focusBg(int normal, int focused) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(focused));
        d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(focused));
        d.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(focused));
        d.addState(new int[]{}, new ColorDrawable(normal));
        return d;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        video.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video.setFocusable(false);
        root.addView(video);

        list = new ListView(this);
        list.setBackgroundColor(Color.argb(150, 6, 12, 22));
        list.setSelector(new ColorDrawable(Color.argb(110, 90, 190, 255)));
        list.setDrawSelectorOnTop(true);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.max((int) (getResources().getDisplayMetrics().widthPixels * 0.20f), 260),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.START;
        list.setLayoutParams(lp);
        root.addView(list);

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = rows.get(position);
                if (o instanceof Channel) play((Channel) o);
            }
        });

        menuBar = new LinearLayout(this);
        menuBar.setOrientation(LinearLayout.VERTICAL);
        menuBar.setBackgroundColor(Color.argb(205, 10, 14, 22));
        menuBar.setPadding(dp(8), dp(84), dp(8), dp(70));
        FrameLayout.LayoutParams mbp = new FrameLayout.LayoutParams(
                Math.max((int) (getResources().getDisplayMetrics().widthPixels * 0.15f), 200),
                ViewGroup.LayoutParams.MATCH_PARENT);
        mbp.gravity = Gravity.END;
        menuBar.setLayoutParams(mbp);
        root.addView(menuBar);

        addMenuButton("手机推送", 1);
        addMenuButton("换源设置", 2);
        addMenuButton("收藏 / 取消", 3);
        addMenuButton("按键说明", 4);
        addMenuButton("刷新列表", 5);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(19);
        status.setBackgroundColor(Color.argb(150, 0, 0, 0));
        status.setPadding(dp(18), dp(10), dp(18), dp(10));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP | Gravity.END;
        sp.topMargin = dp(10);
        sp.rightMargin = dp(14);
        status.setLayoutParams(sp);
        root.addView(status);

        connText = new TextView(this);
        connText.setTextColor(Color.WHITE);
        connText.setTextSize(20);
        connText.setGravity(Gravity.CENTER);
        connText.setLineSpacing(dp(8), 1f);
        connText.setBackgroundColor(Color.argb(190, 0, 0, 0));
        connText.setPadding(dp(28), dp(20), dp(28), dp(20));
        connText.setVisibility(View.GONE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        connText.setLayoutParams(cp);
        root.addView(connText);

        liveTag = new TextView(this);
        liveTag.setTextColor(Color.WHITE);
        liveTag.setTextSize(17);
        liveTag.setBackgroundColor(Color.argb(170, 0, 0, 0));
        liveTag.setPadding(dp(16), dp(8), dp(16), dp(8));
        liveTag.setVisibility(View.GONE);
        FrameLayout.LayoutParams ltp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltp.gravity = Gravity.TOP | Gravity.START;
        ltp.leftMargin = dp(14);
        ltp.topMargin = dp(56);
        liveTag.setLayoutParams(ltp);
        root.addView(liveTag);

        hint = new TextView(this);
        hint.setTextColor(Color.rgb(210, 225, 245));
        hint.setTextSize(19);
        hint.setBackgroundColor(Color.argb(190, 0, 0, 0));
        hint.setPadding(dp(22), dp(10), dp(22), dp(10));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM | Gravity.END;
        hp.bottomMargin = dp(14);
        hp.rightMargin = dp(16);
        hint.setLayoutParams(hp);
        root.addView(hint);
        updateHint();

        setContentView(root);
        list.requestFocus();
    }

    private void addMenuButton(String text, int action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setFocusable(false);
        b.setBackgroundDrawable(focusBg(Color.rgb(20, 28, 44), Color.rgb(21, 101, 192)));
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        final int act = action;
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                performMenuAct(act);
            }
        });
        menuBar.addView(b, lp);
        menuButtons.add(b);
        menuActions.add(Integer.valueOf(action));
    }

    private void performMenuAct(int action) {
        switch (action) {
            case 1:
                showQrPanel();
                break;
            case 2:
                showSettings();
                break;
            case 3:
                toggleFavSelected();
                break;
            case 4:
                showHelp();
                break;
            case 5:
                resetAndReload();
                break;
            default:
                break;
        }
    }

    private void updateHint() {
        if (hint == null) return;
        if (panel != null) {
            hint.setText("返回键 关闭");
        } else if (playing && !listVisible) {
            hint.setText("↑ 线路   ↓ 节目单   ← → 换台   OK/返回 列表   菜单 设置");
        } else {
            hint.setText("↑↓ 选频道   OK 播放   → 菜单");
        }
    }

    // ---------------- 数据加载 ----------------

    private void loadSource(final boolean force) {
        final String url = prefs.getString(KEY_URL, PRESETS[0]);
        setStatus("正在连接源 " + sourceLabel(url) + "…（超时 10 秒自动换下一个）");

        if (!force) {
            String cache = readCache();
            if (cache != null && cache.contains("#EXTINF")) {
                applyPlaylist(cache, true);
            }
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    final String text = BUILTIN.equals(url) ? readAsset("builtin.m3u") : fetch(url, 10);
                    if (text == null || !text.contains("#EXTINF")) {
                        throw new Exception("内容不是有效的 m3u 列表");
                    }
                    if (!BUILTIN.equals(url)) writeCache(text);
                    ui.post(new Runnable() {
                        public void run() {
                            failover = 0;
                            applyPlaylist(text, false);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            tryFailover(url, e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private static String sourceLabel(String url) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].equals(url)) return PRESET_NAMES[i];
        }
        return url;
    }

    /** 当前源失败时，自动轮换到下一个内置源 */
    private void tryFailover(String failed, String reason) {
        if (failover < PRESETS.length - 1) {
            failover++;
            String next = PRESETS[failover];
            if (!next.equals(failed)) {
                prefs.edit().putString(KEY_URL, next).apply();
                setStatus("「" + sourceLabel(failed) + "」连不上，" + reason
                        + "，正在换「" + sourceLabel(next) + "」…");
                toast("源不可用：" + reason + "，已自动换下一个源");
                loadSource(true);
                return;
            }
        }
        setStatus("获取直播源失败：" + reason);
        if (channels.isEmpty()) {
            toast("所有内置源都连不上，请选「设置 / 换源」或手机推送");
        }
    }

    private String fetch(String urlStr, int timeoutSec) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 2000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/60 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 12 * 1024 * 1024) break;
            }
            in.close();
            String s = bos.toString("UTF-8");
            int bad = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\uFFFD') bad++;
            }
            if (bad > 0 && bad * 20 > s.length()) {
                try {
                    String g = new String(bos.toByteArray(), "GBK");
                    int bad2 = 0;
                    for (int i = 0; i < g.length(); i++) {
                        if (g.charAt(i) == '\uFFFD') bad2++;
                    }
                    if (bad2 < bad) s = g;
                } catch (Exception ignore) {
                }
            }
            return s.replace("\uFFFD", "");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void applyPlaylist(String text, boolean fromCache) {
        List<Channel> parsed = M3UParser.parse(text);
        if (parsed.isEmpty()) {
            if (!fromCache) setStatus("直播源里没有解析到频道");
            return;
        }
        String keep = null;
        if (playingIndex >= 0 && playingIndex < rows.size() && rows.get(playingIndex) instanceof Channel) {
            keep = ((Channel) rows.get(playingIndex)).currentUrl();
        }
        channels.clear();
        channels.addAll(parsed);
        java.util.Set<String> favSet = prefs.getStringSet(KEY_FAV, new java.util.HashSet<String>());
        for (Channel ch : channels) ch.fav = favSet.contains(favKey(ch));
        rebuildRows(keep);
        String host = "";
        String cur = prefs.getString(KEY_URL, PRESETS[0]);
        if (BUILTIN.equals(cur)) {
            host = "内置精选";
        } else {
            try {
                host = new URL(cur).getHost();
            } catch (Exception ignore) {
            }
        }
        setStatus((fromCache ? "缓存 · " : "") + channels.size() + " 个频道 · " + host);
        if (!autoPlayed) {
            autoPlayed = true;
            if (prefs.getBoolean(KEY_AUTO_PLAY, true) && lastUrl != null) {
                for (int i = 0; i < rows.size(); i++) {
                    Object o = rows.get(i);
                    if (o instanceof Channel && lastUrl.equals(((Channel) o).currentUrl())) {
                        play((Channel) o);
                        break;
                    }
                }
            }
        }
        if (epg.isReady()) adapter.notifyDataSetChanged();
    }

    private static int groupRank(String g) {
        if (g == null) return 9;
        if (g.contains("央视")) return 0;
        if (g.contains("卫视")) return 1;
        if (g.contains("港") || g.contains("澳") || g.contains("台湾")) return 2;
        if (g.contains("地方")) return 3;
        return 4;
    }

    private static int cctvNum(String n) {
        if (n == null) return 999;
        String up = n.toUpperCase().trim();
        if (!up.startsWith("CCTV")) return 999;
        String u = up.substring(4).trim();
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if (ch >= '0' && ch <= '9') {
                d.append(ch);
            } else if (d.length() > 0) {
                break;
            }
        }
        if (d.length() == 0) return 999;
        try {
            return Integer.parseInt(d.toString());
        } catch (Exception e) {
            return 999;
        }
    }

    private void rebuildRows(String keepUrl) {
        final boolean liveOnly = prefs.getBoolean(KEY_LIVE_ONLY, false);
        List<Channel> ordered = new ArrayList<Channel>();
        for (Channel c : channels) {
            if (liveOnly && c.group != null && c.group.contains("点播")) continue;
            ordered.add(c);
        }
        Collections.sort(ordered, new Comparator<Channel>() {
            public int compare(Channel a, Channel b) {
                int ra = groupRank(a.group);
                int rb = groupRank(b.group);
                if (ra != rb) return ra - rb;
                if (ra == 0) {
                    int na = cctvNum(a.name);
                    int nb = cctvNum(b.name);
                    if (na != nb) return na - nb;
                } else if (ra >= 4) {
                    int c = a.group.compareTo(b.group);
                    if (c != 0) return c;
                }
                return 0;
            }
        });

        Map<String, List<Channel>> byGroup = new LinkedHashMap<String, List<Channel>>();
        for (Channel c : ordered) {
            List<Channel> l = byGroup.get(c.group);
            if (l == null) {
                l = new ArrayList<Channel>();
                byGroup.put(c.group, l);
            }
            l.add(c);
        }
        rows.clear();
        listSel = -1;
        List<Channel> favList = new ArrayList<Channel>();
        for (Channel c : ordered) {
            if (c.fav) favList.add(c);
        }
        if (!favList.isEmpty()) {
            rows.add("★ 收藏  (" + favList.size() + ")");
            rows.addAll(favList);
        }
        for (Map.Entry<String, List<Channel>> e : byGroup.entrySet()) {
            rows.add(e.getKey() + "  (" + e.getValue().size() + ")");
            rows.addAll(e.getValue());
        }
        playingIndex = -1;
        if (keepUrl != null) {
            for (int i = 0; i < rows.size(); i++) {
                Object o = rows.get(i);
                if (o instanceof Channel && keepUrl.equals(((Channel) o).currentUrl())) {
                    playingIndex = i;
                    break;
                }
            }
        }
        adapter.notifyDataSetChanged();
        if (listSel < 0) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof Channel) {
                    listSel = i;
                    break;
                }
            }
        }
    }

    // ---------------- 播放 ----------------

    private int connectTimeout() {
        return prefs.getInt(KEY_TIMEOUT, 10);
    }

    private final Runnable connectTicker = new Runnable() {
        public void run() {
            Channel c = connectChannel;
            if (c == null || connText == null) return;
            int sec = (int) ((System.currentTimeMillis() - connectStart) / 1000);
            String host = "";
            try {
                host = new URL(c.urls.get(connectLine)).getHost();
            } catch (Exception ignore) {
            }
            connText.setText("正在连接  " + c.name
                    + "\n线路 " + (connectLine + 1) + " / " + c.urls.size()
                    + (host.length() > 0 ? "　·　" + host : "")
                    + "\n源：" + sourceLabel(prefs.getString(KEY_URL, PRESETS[0]))
                    + "\n" + sec + " 秒 / " + connectTimeout() + " 秒，超时自动换下一条");
            if (sec >= connectTimeout()) {
                connText.setText("线路 " + (connectLine + 1) + " 连接超时\n正在换下一条线路…");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (c.line + 1 < c.urls.size()) {
                            playLine(c, c.line + 1);
                        } else {
                            connText.setText("这个频道所有线路都连不上\n按上键查看线路，或返回换源");
                            ui.postDelayed(new Runnable() {
                                public void run() {
                                    hideConn();
                                }
                            }, 2600);
                        }
                    }
                }, 700);
            } else {
                ui.postDelayed(this, 500);
            }
        }
    };

    private void showLiveTag(MediaPlayer mp) {
        if (liveTag == null) return;
        if (listVisible) return;   // 列表界面不显示，避免压住频道
        if (catchupMode) {
            liveTag.setText("回放 · " + (catchupLabel.length() > 0 ? catchupLabel : nowText()));
            liveTag.setTextColor(Color.rgb(255, 196, 0));
        } else {
            int dur = 0;
            try {
                dur = mp.getDuration();
            } catch (Exception ignore) {
            }
            if (dur > 0) {
                liveTag.setText("回放 · " + nowText());
                liveTag.setTextColor(Color.rgb(255, 196, 0));
            } else {
                liveTag.setText("● 直播");
                liveTag.setTextColor(Color.rgb(120, 230, 150));
            }
        }
        liveTag.setVisibility(View.VISIBLE);
    }

    private static String nowText() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    private void hideConn() {
        ui.removeCallbacks(connectTicker);
        connectChannel = null;
        if (connText != null) connText.setVisibility(View.GONE);
    }

    private void play(Channel c) {
        playLine(c, c.line);
    }

    private void playUrl(Channel c, String url) {
        catchupMode = true;
        startVideo(c, url, false);
    }

    private void playLine(Channel c, int idx) {
        if (c == null || c.urls.isEmpty()) return;
        if (idx < 0) idx = 0;
        if (idx >= c.urls.size()) idx = 0;
        c.line = idx;
        catchupMode = c.group != null && c.group.contains("点播");
        startVideo(c, c.currentUrl(), true);
    }

    private void startVideo(final Channel c, String url, final boolean retryable) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == c) {
                playingIndex = i;
                break;
            }
        }
        playing = true;
        listVisible = false;
        list.setVisibility(View.GONE);
        if (menuBar != null) menuBar.setVisibility(View.GONE);
        video.setVisibility(View.VISIBLE);
        String tag = c.urls.size() > 1 ? (" · 线路 " + (c.line + 1) + "/" + c.urls.size()) : "";
        setStatus("正在播放：" + c.name + tag);
        updateHint();

        connectChannel = c;
        connectLine = c.line;
        connectStart = System.currentTimeMillis();
        ui.removeCallbacks(connectTicker);
        if (connText != null) {
            connText.setText("正在连接  " + c.name);
            connText.setVisibility(View.VISIBLE);
            ui.postDelayed(connectTicker, 200);
        }

        video.stopPlayback();
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                hideConn();
                video.start();
                showLiveTag(mp);
                int w = mp.getVideoWidth();
                int h = mp.getVideoHeight();
                if (w > 0 && h > 0) {
                    c.resolution = w + "x" + h;
                    String t2 = c.urls.size() > 1 ? (" · 线路 " + (c.line + 1) + "/" + c.urls.size()) : "";
                    setStatus(c.name + " · " + c.resolution + t2);
                }
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                hideConn();
                if (retryable && prefs.getBoolean(KEY_AUTOLINE, true) && c.line + 1 < c.urls.size()) {
                    int next = c.line + 1;
                    toast("线路 " + (c.line + 1) + " 播不了，自动换线路 " + (next + 1));
                    playLine(c, next);
                } else {
                    setStatus("播放失败（上键换线路 / 左右键换台 / 返回键换源）");
                }
                return true;
            }
        });
        video.setVideoURI(Uri.parse(url));
        prefs.edit().putString(KEY_LAST, url).apply();
    }

    private void stepChannel(int dir) {
        if (rows.isEmpty()) return;
        int i = playingIndex;
        for (int k = 1; k <= rows.size(); k++) {
            int j = (i + dir * k) % rows.size();
            if (j < 0) j += rows.size();
            Object o = rows.get(j);
            if (o instanceof Channel) {
                play((Channel) o);
                return;
            }
        }
    }

    private Channel currentChannel() {
        if (playingIndex >= 0 && playingIndex < rows.size() && rows.get(playingIndex) instanceof Channel) {
            return (Channel) rows.get(playingIndex);
        }
        return null;
    }

    private void showList() {
        hideConn();
        if (liveTag != null) liveTag.setVisibility(View.GONE);
        if (menuBar != null) menuBar.setVisibility(View.VISIBLE);
        listVisible = true;
        col = 0;
        paintMenu();
        list.setVisibility(View.VISIBLE);
        if (playingIndex >= 0) {
            listSel = playingIndex;
        }
        if (listSel >= 0) list.setSelection(listSel);
        list.requestFocus();
        setStatus("OK 播放 · 左键 节目单 · 第一项换源");
        updateHint();
    }

    private void setStatus(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(e);
        }
        int code = e.getKeyCode();

        if (panel != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                closePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS
                || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_PROG_RED) {
            showSettings();
            return true;
        }

        // 播放中
        if (playing && !listVisible) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                showList();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                stepChannel(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                stepChannel(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                Channel cu = currentChannel();
                if (cu != null) {
                    showLines(cu);
                    return true;
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                Channel c = currentChannel();
                if (c != null) {
                    showEpg(c);
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }

        // 列表 / 菜单栏：完全自管，不依赖 View 焦点
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSel(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col == 1) {
                    col = 0;
                    paintMenu();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (menuVisible()) {
                    col = 1;
                    paintMenu();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (col == 1) {
                    performMenu(menuSel);
                } else {
                    playSelected();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (col == 1) {
                    col = 0;
                    paintMenu();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean menuVisible() {
        return menuBar != null && menuBar.getVisibility() == View.VISIBLE && menuButtons.size() > 0;
    }

    private void moveSel(int dir) {
        if (col == 1) {
            if (menuButtons.isEmpty()) return;
            menuSel = (menuSel + dir + menuButtons.size()) % menuButtons.size();
            paintMenu();
            return;
        }
        if (rows.isEmpty()) return;
        int i = listSel;
        if (i < 0) i = dir > 0 ? -1 : 0;
        for (int k = 1; k <= rows.size(); k++) {
            int j = (i + dir * k) % rows.size();
            if (j < 0) j += rows.size();
            if (rows.get(j) instanceof Channel) {
                listSel = j;
                list.setSelection(j);
                adapter.notifyDataSetChanged();
                return;
            }
        }
    }

    private void playSelected() {
        if (listSel >= 0 && listSel < rows.size() && rows.get(listSel) instanceof Channel) {
            play((Channel) rows.get(listSel));
        }
    }

    private void paintMenu() {
        for (int i = 0; i < menuButtons.size(); i++) {
            Button b = menuButtons.get(i);
            b.setBackgroundDrawable(focusBg(
                    Color.rgb(20, 28, 44),
                    i == menuSel && col == 1 ? Color.rgb(21, 101, 192) : Color.rgb(28, 40, 60)));
        }
    }

    private void performMenu(int idx) {
        if (idx < 0 || idx >= menuActions.size()) return;
        switch (menuActions.get(idx)) {
            case 1:
                showQrPanel();
                break;
            case 2:
                showSettings();
                break;
            case 3:
                toggleFavSelected();
                break;
            case 4:
                showHelp();
                break;
            case 5:
                resetAndReload();
                break;
            default:
                break;
        }
    }

    // ---------------- 面板 ----------------

    private void closePanel() {
        if (panel != null) {
            root.removeView(panel);
            panel = null;
        }
        if (menuBar != null && listVisible && !playing) menuBar.setVisibility(View.VISIBLE);
        if (listVisible) list.requestFocus();
        updateHint();
    }

    private FrameLayout newPanel() {
        closePanel();
        FrameLayout p = new FrameLayout(this);
        p.setBackgroundColor(Color.argb(230, 0, 0, 0));
        p.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.setFocusable(true);
        root.addView(p);
        panel = p;
        if (menuBar != null) menuBar.setVisibility(View.GONE);
        updateHint();
        return p;
    }

    private LinearLayout panelCard(FrameLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.rgb(16, 22, 34));
        int pad = dp(28);
        card.setPadding(pad, dp(22), pad, dp(22));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(dp(620), (int) (getResources().getDisplayMetrics().widthPixels * 0.56f)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        int m = dp(36);
        lp.rightMargin = m;
        lp.bottomMargin = m;
        card.setLayoutParams(lp);
        parent.addView(card);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(14));
        card.addView(t);
        return card;
    }

    private Button panelButton(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setBackgroundDrawable(focusBg(Color.rgb(24, 34, 52), Color.rgb(21, 101, 192)));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    // ---------------- 设置 ----------------

    private void showSettings() {
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, "设置");

        TextView tip = new TextView(this);
        tip.setText("当前源：" + prefs.getString(KEY_URL, PRESETS[0]));
        tip.setTextColor(Color.rgb(143, 168, 200));
        tip.setTextSize(13);
        card.addView(tip);

        Button phone = panelButton(card, "手机扫码推送（推荐）");
        phone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showQrPanel();
            }
        });

        Button manual = panelButton(card, "手动输入源地址");
        manual.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                inputDialog("直播源地址", prefs.getString(KEY_URL, PRESETS[0]), new OnText() {
                    public void onText(String s) {
                        if (s.length() == 0) return;
                        prefs.edit().putString(KEY_URL, s).apply();
                        failover = 0;
                        closePanel();
                        resetAndReload();
                    }
                });
            }
        });

        Button epgBtn = panelButton(card, "节目单地址");
        epgBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                inputDialog("节目单地址（XMLTV）", prefs.getString(KEY_EPG, DEFAULT_EPG), new OnText() {
                    public void onText(String s) {
                        prefs.edit().putString(KEY_EPG, s).apply();
                        epgTried = false;
                        epg.clear();
                        loadEpg();
                        toast("已保存，正在重新加载节目单");
                    }
                });
            }
        });

        Button keys = panelButton(card, "按键说明");
        keys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showHelp();
            }
        });

        Button more = panelButton(card, "更多设置");
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showMore();
            }
        });

        Button reload = panelButton(card, "重新加载");
        reload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
                resetAndReload();
            }
        });

        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });

        if (card.getChildCount() > 2) card.getChildAt(2).requestFocus();
    }

    private void showMore() {
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, "更多设置");

        final Button t = panelButton(card, "连接超时：" + connectTimeout() + " 秒");
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int v2 = connectTimeout();
                v2 = v2 == 10 ? 15 : (v2 == 15 ? 8 : 10);
                prefs.edit().putInt(KEY_TIMEOUT, v2).apply();
                t.setText("连接超时：" + v2 + " 秒");
            }
        });

        final Button l = panelButton(card, "只看直播：" + onOff(prefs.getBoolean(KEY_LIVE_ONLY, false)));
        l.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !prefs.getBoolean(KEY_LIVE_ONLY, false);
                prefs.edit().putBoolean(KEY_LIVE_ONLY, nv).apply();
                l.setText("只看直播：" + onOff(nv));
                rebuildRows(null);
            }
        });

        final Button a = panelButton(card, "自动换线路：" + onOff(prefs.getBoolean(KEY_AUTOLINE, true)));
        a.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !prefs.getBoolean(KEY_AUTOLINE, true);
                prefs.edit().putBoolean(KEY_AUTOLINE, nv).apply();
                a.setText("自动换线路：" + onOff(nv));
            }
        });

        final Button ap = panelButton(card, "开机自动播放上次频道：" + onOff(prefs.getBoolean(KEY_AUTO_PLAY, true)));
        ap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !prefs.getBoolean(KEY_AUTO_PLAY, true);
                prefs.edit().putBoolean(KEY_AUTO_PLAY, nv).apply();
                ap.setText("开机自动播放上次频道：" + onOff(nv));
            }
        });

        final Button ep = panelButton(card, "节目单：" + onOff(prefs.getBoolean(KEY_EPG_ON, true)));
        ep.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean nv = !prefs.getBoolean(KEY_EPG_ON, true);
                prefs.edit().putBoolean(KEY_EPG_ON, nv).apply();
                ep.setText("节目单：" + onOff(nv));
                if (nv) {
                    epgTried = false;
                    epg.clear();
                    loadEpg();
                } else {
                    epg.clear();
                    epgTried = true;
                    adapter.notifyDataSetChanged();
                }
            }
        });

        Button clear = panelButton(card, "清除缓存并重载");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File f = cacheFile();
                if (f.exists()) f.delete();
                closePanel();
                resetAndReload();
            }
        });

        Button about = panelButton(card, "关于");
        about.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAbout();
            }
        });

        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        t.requestFocus();
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    private void showAbout() {
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, "关于");
        TextView tv = new TextView(this);
        tv.setTextColor(Color.rgb(210, 222, 238));
        tv.setTextSize(15);
        tv.setLineSpacing(dp(6), 1f);
        String src = prefs.getString(KEY_URL, PRESETS[0]);
        tv.setText("云电视 / Cloud TV\n版本 1.9\n\n"
                + "当前频道：" + channels.size() + " 个\n"
                + "当前源：" + sourceLabel(src) + "\n"
                + "节目单：" + (prefs.getBoolean(KEY_EPG_ON, true)
                ? prefs.getString(KEY_EPG, DEFAULT_EPG) : "已关闭") + "\n"
                + "手机推送：" + (server != null && server.isRunning()
                ? "http://" + ConfigServer.localIp() + ":" + server.getPort() : "未启动"));
        card.addView(tv);
        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        close.requestFocus();
    }

    private static String favKey(Channel c) {
        return c.group + "|" + c.name;
    }

    private void toggleFav(Channel c) {
        java.util.Set<String> set = new java.util.HashSet<String>(
                prefs.getStringSet(KEY_FAV, new java.util.HashSet<String>()));
        String k = favKey(c);
        boolean added;
        if (set.contains(k)) {
            set.remove(k);
            added = false;
        } else {
            set.add(k);
            added = true;
        }
        prefs.edit().putStringSet(KEY_FAV, set).apply();
        for (Channel ch : channels) ch.fav = set.contains(favKey(ch));
        rebuildRows(null);
        toast(added ? ("已收藏：" + c.name) : ("已取消收藏：" + c.name));
    }

    private void toggleFavSelected() {
        int p = list.getSelectedItemPosition();
        if (p >= 0 && p < rows.size() && rows.get(p) instanceof Channel) {
            toggleFav((Channel) rows.get(p));
        } else {
            toast("先在频道栏里选中一个频道");
        }
    }

    private void showHelp() {
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, "按键说明");
        TextView tv = new TextView(this);
        tv.setTextColor(Color.rgb(210, 222, 238));
        tv.setTextSize(15);
        tv.setLineSpacing(dp(6), 1f);
        tv.setText("在频道列表：\n"
                + "　↑ ↓　上下选频道\n"
                + "　OK　播放选中的频道\n"
                + "　←　看这个频道的节目单\n"
                + "　菜单 / 信息 / 红键　打开设置（换源、手机推送）\n"
                + "　返回　退出应用\n\n"
                + "在播放画面：\n"
                + "　↑　线路 / 清晰度（卡顿时在这里换）\n"
                + "　↓　节目单\n"
                + "　← →　上一个 / 下一个频道\n"
                + "　OK 或 返回　回到频道列表\n"
                + "　菜单 / 信息 / 红键　打开设置\n\n"
                + "任何面板：返回键关闭");
        card.addView(tv);
        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        close.requestFocus();
    }

    private interface OnText {
        void onText(String s);
    }

    private void inputDialog(String title, String value, final OnText cb) {
        final EditText et = new EditText(this);
        et.setText(value);
        et.setSingleLine(false);
        et.setTextSize(14);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cb.onText(et.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showQrPanel() {
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, "手机推送");

        String ip = ConfigServer.localIp();
        if (ip == null || server == null || !server.isRunning()) {
            TextView tv = new TextView(this);
            tv.setText("电视没有连上 WiFi，无法使用手机推送。\n请先在电视设置里连接网络。");
            tv.setTextColor(Color.rgb(255, 179, 0));
            tv.setTextSize(15);
            card.addView(tv);
            Button close = panelButton(card, "关闭");
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closePanel();
                }
            });
            close.requestFocus();
            return;
        }

        String url = "http://" + ip + ":" + server.getPort();
        QrView qr = new QrView(this);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(340), dp(340));
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        qr.setLayoutParams(qp);
        try {
            qr.setMatrix(QrCode.encode(url));
        } catch (Exception ignore) {
        }
        card.addView(qr);

        TextView tv = new TextView(this);
        tv.setText(url);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(0, dp(12), 0, dp(6));
        card.addView(tv);

        TextView hint = new TextView(this);
        hint.setText("手机连同一个 WiFi，扫码打开页面 → 粘贴 m3u 地址 → 推送");
        hint.setTextColor(Color.rgb(143, 168, 200));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(hint);

        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        close.requestFocus();
    }

    // ---------------- 节目单 ----------------

    private void loadEpg() {
        if (!prefs.getBoolean(KEY_EPG_ON, true)) return;
        final String url = prefs.getString(KEY_EPG, DEFAULT_EPG);
        if (url.length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String xml = fetch(url, 15);
                    final boolean ok = epg.parse(xml);
                    ui.post(new Runnable() {
                        public void run() {
                            if (ok) {
                                adapter.notifyDataSetChanged();
                                if (panel != null && epgLoadingFor != null) {
                                    Channel c = epgLoadingFor;
                                    closePanel();
                                    showEpg(c);
                                }
                            }
                        }
                    });
                } catch (Exception ignore) {
                }
            }
        }).start();
    }

    private void showEpg(final Channel c) {
        epgLoadingFor = c;
        if (!epg.isReady() && !epgTried) {
            epgTried = true;
            loadEpg();
        }
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, c.name + " · 节目单");

        List<EpgStore.Program> progs = epg.programs(c.id);
        if (progs.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(!prefs.getBoolean(KEY_EPG_ON, true)
                    ? "节目单已关闭。\n可在「更多设置 → 节目单」里打开。"
                    : epg.isReady()
                    ? "这个频道暂时没有节目单数据。"
                    : (epgTried
                    ? "节目单里没有当前日期附近的节目，可能已失效。\n可在设置里更换节目单地址（XMLTV）。"
                    : "节目单正在加载（首次约 3-5 秒），稍后按左键再看。"));
            tv.setTextColor(Color.rgb(200, 210, 225));
            tv.setTextSize(15);
            card.addView(tv);
            Button close = panelButton(card, "关闭");
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closePanel();
                }
            });
            close.requestFocus();
            return;
        }

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(new ColorDrawable(Color.argb(110, 90, 190, 255)));
        lv.setDrawSelectorOnTop(true);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        final List<EpgStore.Program> data = progs;
        final long now = System.currentTimeMillis();
        lv.setAdapter(new BaseAdapter() {
            public int getCount() {
                return data.size();
            }

            public Object getItem(int i) {
                return data.get(i);
            }

            public long getItemId(int i) {
                return i;
            }

            public View getView(int i, View convert, ViewGroup parent) {
                TextView tv = (convert instanceof TextView) ? (TextView) convert : new TextView(MainActivity.this);
                EpgStore.Program pr = data.get(i);
                boolean live = pr.start <= now && now < pr.stop;
                String time = EpgStore.hhmm(pr.start) + "  " + (live ? "▶ " : "") + pr.title;
                tv.setText(time);
                tv.setTextSize(15);
                tv.setTextColor(live ? Color.rgb(255, 196, 0) : Color.WHITE);
                tv.setSingleLine(true);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                return tv;
            }
        });
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                EpgStore.Program pr = data.get(pos);
                if (System.currentTimeMillis() < pr.start) {
                    toast("该节目还没开始");
                    return;
                }
                String cu = catchupUrl(c, pr);
                if (cu != null) {
                    closePanel();
                    playUrl(c, cu);
                } else {
                    toast("该节目已结束，当前源不支持回看");
                }
            }
        });
        card.addView(lv);

        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        lv.requestFocus();
    }

    private String catchupUrl(Channel c, EpgStore.Program pr) {
        if (!c.hasCatchup()) return null;
        long s = pr.start / 1000;
        long e = pr.stop / 1000;
        long now = System.currentTimeMillis() / 1000;
        String u = c.catchupSource;
        u = u.replace("${start}", String.valueOf(s)).replace("{start}", String.valueOf(s));
        u = u.replace("${end}", String.valueOf(e)).replace("{end}", String.valueOf(e));
        u = u.replace("${utc}", String.valueOf(s)).replace("{utc}", String.valueOf(s));
        u = u.replace("${lutc}", String.valueOf(now)).replace("{lutc}", String.valueOf(now));
        if (u.equals(c.catchupSource) && "append".equalsIgnoreCase(c.catchup)) {
            u = c.currentUrl() + String.valueOf(s);
        }
        return u;
    }

    // ---------------- 多线路 / 清晰度 ----------------

    private void showLines(final Channel c) {
        FrameLayout p = newPanel();
        LinearLayout card = panelCard(p, c.name + " · 线路 / 清晰度");
        final Map<Integer, String> info = new HashMap<Integer, String>();
        final ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(new ColorDrawable(Color.argb(110, 90, 190, 255)));
        lv.setDrawSelectorOnTop(true);
        lv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() {
                return c.urls.size();
            }

            public Object getItem(int i) {
                return c.urls.get(i);
            }

            public long getItemId(int i) {
                return i;
            }

            public View getView(int i, View convert, ViewGroup parent) {
                TextView tv = (convert instanceof TextView) ? (TextView) convert : new TextView(MainActivity.this);
                String st = info.get(Integer.valueOf(i));
                if (st == null) st = "检测中…";
                String host = c.urls.get(i);
                try {
                    host = new URL(host).getHost();
                } catch (Exception ignore) {
                }
                String mark = (i == c.line) ? "▶ " : "";
                tv.setText(mark + "线路 " + (i + 1) + " · " + host + "   " + st);
                tv.setTextSize(15);
                tv.setTextColor(i == c.line ? Color.rgb(255, 196, 0) : Color.WHITE);
                tv.setSingleLine(true);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                return tv;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                closePanel();
                playLine(c, pos);
            }
        });
        card.addView(lv);

        Button fav = panelButton(card, c.fav ? "★ 取消收藏此频道" : "★ 收藏此频道");
        fav.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleFav(c);
                closePanel();
            }
        });
        Button again = panelButton(card, "重新检测全部线路");
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startProbe(c, info, ad);
            }
        });
        Button close = panelButton(card, "关闭");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        lv.requestFocus();
        startProbe(c, info, ad);
    }

    private void startProbe(final Channel c, final Map<Integer, String> info, final BaseAdapter ad) {
        info.clear();
        ad.notifyDataSetChanged();
        int n = Math.min(c.urls.size(), 8);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String url = c.urls.get(i);
            new Thread(new Runnable() {
                public void run() {
                    final String out = probe(url);
                    ui.post(new Runnable() {
                        public void run() {
                            info.put(Integer.valueOf(idx), out);
                            ad.notifyDataSetChanged();
                        }
                    });
                }
            }).start();
        }
    }

    /** 拉前 8KB 判断能不能连、顺便读出 HLS 分辨率 */
    private String probe(String url) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0)");
            conn.setRequestProperty("Range", "bytes=0-8191");
            int code = conn.getResponseCode();
            if (code >= 400) return "✗ HTTP " + code;
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            in.close();
            long ms = System.currentTimeMillis() - t0;
            if (n <= 0) return "✗ 空响应";
            String head = new String(buf, 0, n, "UTF-8");
            String r = findResolution(head);
            return "✓ " + (r == null ? "直播流" : r) + " · " + ms + "ms";
        } catch (Exception e) {
            return "✗ 连不上";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String findResolution(String m3u8) {
        String best = null;
        int bestPix = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("RESOLUTION=(\\d+)x(\\d+)").matcher(m3u8);
        while (m.find()) {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            if (w * h > bestPix) {
                bestPix = w * h;
                best = w + "x" + h;
            }
        }
        return best;
    }

    // ---------------- 配置服务回调 ----------------

    private void resetAndReload() {
        playing = false;
        playingIndex = -1;
        video.stopPlayback();
        video.setVisibility(View.INVISIBLE);
        channels.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        showList();
        loadSource(true);
    }

    public String currentSource() {
        return prefs.getString(KEY_URL, PRESETS[0]);
    }

    public String currentEpg() {
        return prefs.getString(KEY_EPG, DEFAULT_EPG);
    }

    public int channelCount() {
        return channels.size();
    }

    public void onSourcePushed(final String url) {
        prefs.edit().putString(KEY_URL, url).apply();
        ui.post(new Runnable() {
            public void run() {
                failover = 0;
                toast("手机已推送新源，正在刷新");
                if (panel != null) closePanel();
                resetAndReload();
            }
        });
    }

    public void onEpgPushed(final String url) {
        prefs.edit().putString(KEY_EPG, url).apply();
        ui.post(new Runnable() {
            public void run() {
                toast("EPG 地址已更新");
                loadEpg();
            }
        });
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ---------------- 缓存 ----------------

    private File cacheFile() {
        return new File(getFilesDir(), "playlist.m3u");
    }

    private String readCache() {
        try {
            File f = cacheFile();
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r <= 0) break;
                off += r;
            }
            in.close();
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String text) {
        try {
            FileOutputStream out = new FileOutputStream(cacheFile());
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignore) {
        }
    }

    // ---------------- 列表适配器 ----------------

    private class RowAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int i) {
            return rows.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public int getViewTypeCount() {
            return 2;
        }

        public int getItemViewType(int i) {
            return rows.get(i) instanceof String ? 0 : 1;
        }

        public boolean areAllItemsEnabled() {
            return false;
        }

        public boolean isEnabled(int i) {
            return rows.get(i) instanceof Channel;
        }

        public View getView(int i, View convert, ViewGroup parent) {
            TextView tv = (convert instanceof TextView) ? (TextView) convert : new TextView(MainActivity.this);
            tv.setTextSize(17);
            Object o = rows.get(i);
            if (o instanceof String) {
                tv.setText(String.valueOf(o));
                tv.setTextSize(15);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextColor(Color.rgb(255, 179, 0));
                tv.setPadding(dp(12), dp(16), dp(8), dp(8));
                tv.setBackgroundColor(Color.argb(255, 12, 12, 12));
            } else {
                Channel c = (Channel) o;
                EpgStore.Program pr = epg.isReady() ? epg.now(c.id) : null;
                String extra = c.urls.size() > 1 ? ("  " + c.urls.size() + "路") : "";
                if (pr != null) {
                    String tail = extra + "   " + (pr.title.length() > 0 ? pr.title : "") + "  " + EpgStore.hhmm(pr.start);
                    SpannableString ss = new SpannableString(c.name + tail);
                    ss.setSpan(new RelativeSizeSpan(0.78f), c.name.length(), ss.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ss.setSpan(new ForegroundColorSpan(Color.rgb(130, 160, 195)),
                            c.name.length(), ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    tv.setText(ss);
                } else {
                    tv.setText(c.name + extra);
                }
                tv.setTypeface(Typeface.DEFAULT);
                tv.setTextColor(Color.WHITE);
                tv.setSingleLine(true);
                tv.setPadding(dp(16), dp(14), dp(8), dp(14));
                tv.setBackgroundColor(i == listSel
                        ? Color.argb(110, 90, 190, 255) : Color.TRANSPARENT);
            }
            return tv;
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onPause() {
        super.onPause();
        if (playing && video.isPlaying()) video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel == null && listVisible) list.requestFocus();
        if (playing && !video.isPlaying() && video.getVisibility() == View.VISIBLE) {
            try {
                video.start();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            video.stopPlayback();
        } catch (Exception ignore) {
        }
        if (server != null) server.stop();
        super.onDestroy();
    }
}
