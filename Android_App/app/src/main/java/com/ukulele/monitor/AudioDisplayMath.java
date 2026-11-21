package com.ukulele.monitor;

/** Display helpers only: samples and FPGA FFT data are never synthesised. */
public final class AudioDisplayMath {
    private AudioDisplayMath() {}
    // Use a real rising zero crossing near the beginning of this capture.
    // No circular wrap, repeated samples, auto gain, or altered sample interval.
    public static int triggerStart(float[] pcm) {
        for(int i=1;i<=Math.min(64,pcm.length-1);i++)
            if(pcm[i-1]<0 && pcm[i]>=0)return i;
        return 0;
    }
    public static int peakBetween(int[] bins,int first,int end) {
        int peak=0;
        for(int i=first;i<end;i++)peak=Math.max(peak,bins[i]);
        return peak;
    }
    public static float blend(float from,float to,long elapsed,long duration) {
        float t=Math.max(0,Math.min(1,elapsed/(float)Math.max(1,duration)));
        return from+(to-from)*t;
    }
}
