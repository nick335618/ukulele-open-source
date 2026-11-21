package com.ukulele.monitor;

/** Monotonic clock; generation rejects callbacks from replaced/cancelled starts. */
final class PracticeCountdown {
    private long deadline;
    private int generation;
    private boolean active;
    int start(long now){deadline=now+3000;active=true;return ++generation;}
    boolean active(){return active;}
    long remainingMillis(long now){return !active?0:Math.max(0,deadline-now);}
    int remaining(long now){return !active?0:(int)Math.max(0,(deadline-now+999)/1000);}
    boolean complete(int token,long now){if(!active||token!=generation||now<deadline)return false;active=false;return true;}
    void cancel(){active=false;++generation;}
}
