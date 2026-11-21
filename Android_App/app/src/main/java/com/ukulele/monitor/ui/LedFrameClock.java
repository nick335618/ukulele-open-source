package com.ukulele.monitor.ui;

/** A pauseable 50-Hz phase clock; redraw rate cannot change animation speed. */
public final class LedFrameClock {
    private long lastMs,remainderMs;private int phase;private boolean initialized;
    public int advance(long now,int speed,boolean running){
        if(!initialized){initialized=true;lastMs=now;return phase;}
        long elapsed=Math.max(0,now-lastMs);lastMs=now;
        if(running){long total=elapsed+remainderMs,frames=total/LedEffectMath.FRAME_MS;
            remainderMs=total%LedEffectMath.FRAME_MS;
            phase=(int)((phase+frames*LedEffectMath.phaseStep(speed))&65535L);}
        return phase;
    }
}
