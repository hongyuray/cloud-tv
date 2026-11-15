package com.dsh.lightlive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** 简易弹幕层：文字从右往左飘 */
public class DanmakuView extends View {

    private static class Item {
        String text;
        float x, y, speed;
        boolean mine;
        float width;
    }

    private final List<Item> items = new ArrayList<Item>();
    private final TextPaint paint = new TextPaint();
    private final Random rnd = new Random();
    private long lastFrame = 0;
    private boolean running = false;

    public DanmakuView(Context c) {
        super(c);
        init();
    }

    public DanmakuView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        paint.setAntiAlias(true);
        paint.setTextSize(38f);
        paint.setShadowLayer(4f, 0f, 2f, Color.BLACK);
    }

    public void push(String text, boolean mine) {
        if (text == null) return;
        String t = text.trim();
        if (t.length() == 0) return;
        Item it = new Item();
        it.text = t;
        it.mine = mine;
        it.width = paint.measureText(t);
        it.x = getWidth() > 0 ? getWidth() : 720;
        it.y = 60 + rnd.nextInt(Math.max(1, (getHeight() > 0 ? getHeight() : 400) - 140));
        it.speed = 3.2f + rnd.nextFloat() * 1.8f;
        synchronized (items) {
            items.add(it);
            if (items.size() > 60) items.remove(0);
        }
        start();
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrame = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        synchronized (items) {
            Iterator<Item> it = items.iterator();
            while (it.hasNext()) {
                Item m = it.next();
                m.x -= m.speed * dt * 60f;
                if (m.x + m.width < 0) {
                    it.remove();
                    continue;
                }
                paint.setColor(m.mine ? Color.rgb(255, 220, 90) : Color.WHITE);
                canvas.drawText(m.text, m.x, m.y, paint);
            }
            if (items.isEmpty()) {
                running = false;
            }
        }
        if (running) postInvalidateOnAnimation();
    }
}
