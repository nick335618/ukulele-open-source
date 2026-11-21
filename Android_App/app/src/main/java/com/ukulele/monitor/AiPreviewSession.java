package com.ukulele.monitor;

/** A preparation/playback attempt owns its deadline; old completions cannot stop a new preview. */
final class AiPreviewSession {
    private long generation;
    private boolean active;
    long begin(){active=true;return ++generation;}
    boolean isActive(){return active;}
    boolean isCurrent(long token){return active&&generation==token;}
    boolean finish(long token){if(!isCurrent(token))return false;active=false;return true;}
    void stop(){active=false;generation++;}
}
