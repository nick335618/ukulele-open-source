package com.ukulele.monitor;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;

/** Turns a deliberate horizontal swipe into a page change inside the vertical library. */
final class ScorePageList extends LinearLayout {
    private final IntConsumer turnPage;
    private final int touchSlop;
    private final float pageDistance;
    private float startX, startY;
    private boolean horizontal, vertical;

    ScorePageList(Context context, IntConsumer turnPage) {
        super(context);
        this.turnPage = turnPage;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        pageDistance = Math.max(touchSlop * 3f, 44 * getResources().getDisplayMetrics().density);
        setOrientation(VERTICAL);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return detectDirection(event);
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                break;
            case MotionEvent.ACTION_MOVE:
                detectDirection(event);
                break;
            case MotionEvent.ACTION_UP:
                float distance = event.getX() - startX;
                if (horizontal && Math.abs(distance) >= pageDistance) turnPage.accept(distance < 0 ? 1 : -1);
                horizontal = false;
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                horizontal = false;
                vertical = true;
                break;
            default:
                break;
        }
        return true;
    }

    private void begin(MotionEvent event) {
        startX = event.getX();
        startY = event.getY();
        horizontal = false;
        vertical = false;
    }

    private boolean detectDirection(MotionEvent event) {
        if (vertical) return false;
        if (horizontal) return true;
        float x = Math.abs(event.getX() - startX), y = Math.abs(event.getY() - startY);
        if (x > touchSlop && x > y * 1.35f) {
            horizontal = true;
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (y > touchSlop) {
            vertical = true;
        }
        return horizontal;
    }
}

