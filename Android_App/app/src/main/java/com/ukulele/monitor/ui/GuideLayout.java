package com.ukulele.monitor.ui;
/** Presentation-only string order. Physical guide columns always remain G/C/E/A. */
public final class GuideLayout {
    private GuideLayout(){}
    public static int laneForColumn(int column,boolean reversed){return reversed?column:3-column;}
    public static float headerHeight(float height,float normal,float minimum){return Math.min(normal,Math.max(minimum,height*.22f));}
    public static int numberAtLane(int lane,boolean reversed){return reversed?4-lane:lane+1;}
}
