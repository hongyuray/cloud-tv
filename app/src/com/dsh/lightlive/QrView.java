package com.dsh.lightlive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** 把 QR 模块矩阵画出来（白色底 + 深色模块，留静区） */
public class QrView extends View {

    private boolean[][] modules;
    private final Paint bg = new Paint();
    private final Paint fg = new Paint();
    private int quiet = 2;

    public QrView(Context c) {
        super(c);
        bg.setColor(Color.WHITE);
        bg.setStyle(Paint.Style.FILL);
        fg.setColor(Color.rgb(8, 18, 32));
        fg.setStyle(Paint.Style.FILL);
        fg.setAntiAlias(false);
    }

    public void setMatrix(boolean[][] m) {
        modules = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (modules == null) return;
        int n = modules.length;
        int w = getWidth(), h = getHeight();
        int size = Math.min(w, h);
        int cell = size / (n + quiet * 2);
        if (cell <= 0) return;
        int total = cell * (n + quiet * 2);
        int left = (w - total) / 2;
        int top = (h - total) / 2;

        canvas.drawRect(left, top, left + total, top + total, bg);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (modules[r][c]) {
                    int x = left + (c + quiet) * cell;
                    int y = top + (r + quiet) * cell;
                    canvas.drawRect(x, y, x + cell, y + cell, fg);
                }
            }
        }
    }
}
