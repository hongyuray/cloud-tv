package com.dsh.lightlive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import java.util.List;

/** 手机（触摸屏）版播放界面 */
public class PhoneActivity extends Activity {

    private static final String PREFS = "lightlive";
    private static final String KEY_URL = "source_url";
    private static final String KEY_EPG = "epg_url";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Channel> all = new ArrayList<Channel>();
    private final List<Channel> shown = new ArrayList<Channel>();

    private VideoView video;
    private DanmakuView danmaku;
    private LinearLayout controlBar;
    private LinearLayout listBox;
    private EditText search;
    private ListView list;
    private TextView status;
    private Channel current;
    private boolean fullscreen = false;
    private boolean muted = false;
    private int savedVolume = -1;

    private AudioManager audio;

    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        buildUi();
        loadSource();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 14, 22));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // —— 播放区 ——
        FrameLayout videoBox = new FrameLayout(this);
        int w = getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (w * 9f / 16f));
        col.addView(videoBox, vp);

        video = new VideoView(this);
        video.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoBox.addView(video);

        danmaku = new DanmakuView(this);
        danmaku.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        danmaku.setClickable(false);
        videoBox.addView(danmaku);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setBackgroundColor(Color.argb(140, 0, 0, 0));
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.TOP | Gravity.START;
        sp.leftMargin = dp(8);
        sp.topMargin = dp(8);
        videoBox.addView(status, sp);

        controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.HORIZONTAL);
        controlBar.setBackgroundColor(Color.argb(170, 0, 0, 0));
        controlBar.setPadding(dp(4), dp(2), dp(4), dp(2));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.BOTTOM;
        videoBox.addView(controlBar, cp);

        controlBar.addView(smallButton("上一台", new View.OnClickListener() {
            public void onClick(View v) {
                step(-1);
            }
        }), weight(1));
        controlBar.addView(smallButton("下一台", new View.OnClickListener() {
            public void onClick(View v) {
                step(1);
            }
        }), weight(1));
        final Button muteBtn = smallButton("静音", null);
        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleMute();
                muteBtn.setText(muted ? "已静音" : "静音");
            }
        });
        controlBar.addView(muteBtn, weight(1));
        controlBar.addView(smallButton("音量-", new View.OnClickListener() {
            public void onClick(View v) {
                volume(-1);
            }
        }), weight(1));
        controlBar.addView(smallButton("音量+", new View.OnClickListener() {
            public void onClick(View v) {
                volume(1);
            }
        }), weight(1));
        controlBar.addView(smallButton("弹幕", new View.OnClickListener() {
            public void onClick(View v) {
                sendDanmaku();
            }
        }), weight(1));
        controlBar.addView(smallButton("全屏", new View.OnClickListener() {
            public void onClick(View v) {
                toggleFullscreen();
            }
        }), weight(1));

        // 视频点击：显示/隐藏控制条
        video.setClickable(true);
        video.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                controlBar.setVisibility(controlBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        // —— 搜索行 ——
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(8), dp(8), dp(8), dp(8));
        col.addView(searchRow);

        search = new EditText(this);
        search.setHint("搜索频道…");
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        searchRow.addView(smallButton("语音", new View.OnClickListener() {
            public void onClick(View v) {
                voiceSearch();
            }
        }), new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                filter(e.toString());
            }
        });

        // —— 频道列表 ——
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackgroundColor(Color.rgb(14, 20, 30));
        col.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.argb(120, 90, 190, 255)));
        listBox.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter = new BaseAdapter() {
            public int getCount() {
                return shown.size();
            }

            public Object getItem(int i) {
                return shown.get(i);
            }

            public long getItemId(int i) {
                return i;
            }

            public View getView(int i, View convert, ViewGroup parent) {
                TextView tv = (convert instanceof TextView) ? (TextView) convert : new TextView(PhoneActivity.this);
                Channel c = shown.get(i);
                String tag = c.urls.size() > 1 ? ("  " + c.urls.size() + "路") : "";
                tv.setText(c.name + tag);
                tv.setTextSize(17);
                tv.setTextColor(Color.WHITE);
                tv.setSingleLine(true);
                tv.setPadding(dp(16), dp(14), dp(12), dp(14));
                tv.setBackgroundColor(Color.TRANSPARENT);
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                play(shown.get(pos));
            }
        });

        // —— 底部：聊八卦 ——
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(8), dp(6), dp(8), dp(10));
        col.addView(bottom);
        bottom.addView(smallButton("聊八卦", new View.OnClickListener() {
            public void onClick(View v) {
                openChat();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(smallButton("换源", new View.OnClickListener() {
            public void onClick(View v) {
                chooseSource();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        setContentView(root);
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(60, 255, 255, 255));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(2), dp(4), dp(2), dp(4));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    // ---------------- 数据 ----------------

    private void loadSource() {
        setStatus("正在加载频道…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String url = p.getString(KEY_URL, "builtin");
                    String text;
                    if ("builtin".equals(url)) {
                        text = readAsset("builtin.m3u");
                    } else {
                        text = fetch(url);
                    }
                    if (text == null || !text.contains("#EXTINF")) throw new Exception("列表为空");
                    final List<Channel> parsed = M3UParser.parse(text);
                    ui.post(new Runnable() {
                        public void run() {
                            all.clear();
                            all.addAll(parsed);
                            filter(search.getText().toString());
                            setStatus(all.size() + " 个频道");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            setStatus("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private String fetch(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void filter(String q) {
        String s = q == null ? "" : q.trim().toLowerCase();
        shown.clear();
        for (Channel c : all) {
            if (s.length() == 0 || c.name.toLowerCase().contains(s)) shown.add(c);
        }
        adapter.notifyDataSetChanged();
    }

    private void setStatus(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    // ---------------- 播放 ----------------

    private void play(final Channel c) {
        current = c;
        if (c.urls.isEmpty()) return;
        playUrl(c, c.currentUrl(), 0);
    }

    private void playUrl(final Channel c, String url, final int attempt) {
        setStatus("连接中：" + c.name);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                video.start();
                setStatus(c.name + " · " + mp.getVideoWidth() + "x" + mp.getVideoHeight());
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int w, int e) {
                if (c.line + 1 < c.urls.size()) {
                    c.line++;
                    toast("线路 " + c.line + " 失败，换下一条");
                    playUrl(c, c.currentUrl(), attempt + 1);
                } else {
                    setStatus("播放失败：" + c.name);
                }
                return true;
            }
        });
        video.setVideoURI(Uri.parse(url));
    }

    private void step(int dir) {
        if (shown.isEmpty()) return;
        int idx = shown.indexOf(current);
        if (idx < 0) idx = 0;
        int n = (idx + dir + shown.size()) % shown.size();
        play(shown.get(n));
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        listBox.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        Toast.makeText(this, fullscreen ? "全屏" : "退出全屏", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 音量 ----------------

    private void volume(int dir) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
    }

    private void toggleMute() {
        muted = !muted;
        if (muted) {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            toast("已静音");
        } else {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    savedVolume > 0 ? savedVolume : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0);
            toast("取消静音");
        }
    }

    // ---------------- 语音 ----------------

    private void voiceSearch() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "说频道名，比如“中央一台”");
            startActivityForResult(i, 1001);
        } catch (Exception e) {
            toast("这个手机没有语音识别服务");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001 && res == RESULT_OK && data != null) {
            ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (list != null && !list.isEmpty()) {
                String q = list.get(0);
                search.setText(q);
                toast("搜索：" + q);
            }
        }
    }

    // ---------------- 弹幕 ----------------

    private void sendDanmaku() {
        final EditText et = new EditText(this);
        et.setHint("发条弹幕…");
        new AlertDialog.Builder(this)
                .setTitle("弹幕")
                .setView(et)
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        danmaku.push(et.getText().toString(), true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 聊八卦 ----------------

    private void openChat() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        final TextView log = new TextView(this);
        log.setTextSize(15);
        log.setTextColor(Color.WHITE);
        log.setText("我：今天有什么好看的？\n小云：晚上八点央视三套有《越战越勇》，要不要看？\n");
        ScrollView sv = new ScrollView(this);
        sv.addView(log);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));

        final EditText input = new EditText(this);
        input.setHint("随便聊点什么…");
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("聊八卦（本地模式）")
                .setView(box)
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String q = input.getText().toString().trim();
                        if (q.length() == 0) return;
                        log.append("\n我：" + q + "\n小云：" + ChatBot.reply(q) + "\n");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------- 换源 ----------------

    private void chooseSource() {
        final String[] names = {"内置精选（离线）", "综合大源", "国内直连", "手动输入…"};
        new AlertDialog.Builder(this)
                .setTitle("选择播放源")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                        if (which == 0) {
                            p.edit().putString(KEY_URL, "builtin").apply();
                            loadSource();
                        } else if (which == 1) {
                            p.edit().putString(KEY_URL,
                                    "https://jsd.onmicrosoft.cn/gh/Guovin/iptv-api@gd/output/result.m3u").apply();
                            loadSource();
                        } else if (which == 2) {
                            p.edit().putString(KEY_URL, "https://live.zbds.top/tv/iptv4.m3u").apply();
                            loadSource();
                        } else {
                            final EditText et = new EditText(PhoneActivity.this);
                            new AlertDialog.Builder(PhoneActivity.this)
                                    .setTitle("m3u 地址")
                                    .setView(et)
                                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dd, int ww) {
                                            p.edit().putString(KEY_URL, et.getText().toString().trim()).apply();
                                            loadSource();
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    }
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            toggleFullscreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            video.stopPlayback();
        } catch (Exception ignore) {
        }
        super.onDestroy();
    }
}
