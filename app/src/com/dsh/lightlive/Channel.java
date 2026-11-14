package com.dsh.lightlive;

import java.util.ArrayList;
import java.util.List;

/** 一个直播频道（可含多条线路） */
public class Channel {
    public final String name;
    public final String group;
    public final String id;
    public final String catchup;
    public final String catchupSource;
    public final int catchupDays;
    public final List<String> urls = new ArrayList<String>();

    public int line = 0;
    public boolean fav = false;
    public String resolution = "";

    public Channel(String name, String group, String id,
                   String catchup, String catchupSource, int catchupDays) {
        this.name = name;
        this.group = group;
        this.id = id;
        this.catchup = catchup;
        this.catchupSource = catchupSource;
        this.catchupDays = catchupDays;
    }

    public boolean hasCatchup() {
        return catchupSource != null && catchupSource.length() > 0;
    }

    public String currentUrl() {
        if (urls.isEmpty()) return "";
        if (line < 0 || line >= urls.size()) line = 0;
        return urls.get(line);
    }
}
