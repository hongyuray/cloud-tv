package com.dsh.lightlive;

import java.util.Random;

/** 本地“聊八卦”机器人：关键词匹配 + 兜底话术（不联网） */
public final class ChatBot {

    private static final Random RND = new Random();

    private static final String[] FALLBACK = {
            "这个我还真没听说，你再说详细点？",
            "有点意思，然后呢？",
            "我最近在追央视三套的综艺，你要不要一起看？",
            "八卦这事得看缘分，我这儿消息也不太灵通～",
            "换个话题？比如今晚看什么台。",
    };

    public static String reply(String q) {
        if (q == null) return pick(FALLBACK);
        String s = q.trim();
        if (s.length() == 0) return pick(FALLBACK);

        if (contains(s, "明星", "八卦", "绯闻", "恋情")) {
            return "娱乐圈的事我就知道个大概：谁上热搜了、谁又辟谣了。不过我这版本是离线的，"
                    + "只能陪你聊，消息可不准哦。";
        }
        if (contains(s, "新闻", "时事", "政治")) {
            return "新闻我建议直接看 CCTV-13，比我嘴靠谱。";
        }
        if (contains(s, "电影", "电视剧", "剧")) {
            return "点播分组里有不少老片和港剧，周星驰、成龙那几类都在，翻翻看？";
        }
        if (contains(s, "足球", "篮球", "体育", "比赛")) {
            return "体育频道在“央视频道”里，CCTV5 和 5+ 都有，比赛日别错过。";
        }
        if (contains(s, "卡", "卡顿", "慢")) {
            return "卡的话按播放器上的音频图标旁边那个“⛶”试试全屏，或者点“换源”换一个更近的源。";
        }
        if (contains(s, "你好", "在吗", "嗨", "hi", "hello")) {
            return "在的，我是小云，陪你看电视也陪你唠两句。";
        }
        if (contains(s, "春晚")) {
            return "历年春晚都在“点播”分组里，从 1987 年到现在，随便挑。";
        }
        return pick(FALLBACK);
    }

    private static boolean contains(String s, String... keys) {
        String low = s.toLowerCase();
        for (String k : keys) {
            if (low.contains(k.toLowerCase())) return true;
        }
        return false;
    }

    private static String pick(String[] arr) {
        return arr[RND.nextInt(arr.length)];
    }

    private ChatBot() {
    }
}
