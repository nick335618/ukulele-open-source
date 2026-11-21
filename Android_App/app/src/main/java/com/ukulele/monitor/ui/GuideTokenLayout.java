package com.ukulele.monitor.ui;

/** Visual bounds only; never changes a note's onset, duration or scoring window. */
public final class GuideTokenLayout {
    private GuideTokenLayout() {}
    public static float halfWidth(float preferred, float neighborDistance, float gap) {
        if (!Float.isFinite(neighborDistance)) return preferred;
        float distance=Math.max(0,neighborDistance);
        float visibleGap=Math.min(Math.max(0,gap),distance*.20f);
        return Math.max(0,Math.min(preferred,(distance-visibleGap)*.5f));
    }
}
