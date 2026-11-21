package com.ukulele.monitor;

/** The inner rectangle reserves room for the complete curve stroke. */
public final class AudioPlotGeometry {
    public final float left,top,right,bottom;
    public AudioPlotGeometry(float l,float t,float r,float b,float stroke) {
        float inset=Math.max(1,stroke/2+1);
        left=l+inset;top=t+inset;right=Math.max(left,r-inset);bottom=Math.max(top,b-inset);
    }
    public static float unit(float n) {return Float.isNaN(n)?0:Math.max(0,Math.min(1,n));}
    public float x(float fraction) {return left+(right-left)*unit(fraction);}
    public float y(float fraction) {return bottom-(bottom-top)*unit(fraction);}
    public float amplitudeY(float value) {return y((value+1)/2);}
    public static float binDbfs(float bin) {return bin<=0 ? -96 : bin/2-96;}
    public static float spectrumHeight(float bin) {return unit((binDbfs(bin)+72)/72);}
}
