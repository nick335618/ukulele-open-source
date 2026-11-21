package com.ukulele.monitor;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Retain commands in flight; replace obsolete unsent values for each control. */
final class ControlCommandQueue {
    private static final String[] CONNECTION_QUERIES = {
        "STATE", "POWER:QUERY", "GESTURE_GET", "GESTURE_GET2", "SONG_INFO", "TOUCH_GET",
        "FX_GET", "CAPO_GET", "MODEL_GET", "XC_GET", "GUIDE_FADE_GET", "LM_GET"
    };
    private final ArrayDeque<String> commands = new ArrayDeque<>();

    void offerConnectionQueries() {
        // One fixed batch after notification setup. The GATT path still sends
        // one operation at a time, and ordinary writes retain their queue limit.
        for (String query : CONNECTION_QUERIES) {
            if (!commands.contains(query)) commands.add(query);
        }
    }

    boolean offer(String command, boolean protectHead) {
        String family = command.startsWith("VOLUME:") ? "VOLUME:" :
                        command.startsWith("TIMBRE:") ? "TIMBRE:" :
                        command.startsWith("LM1:") ? "LM1:" :
                        command.startsWith("LED:") ? "LED:" :
                        command.startsWith("PLAYMODE:") ? "PLAYMODE:" :
                        command.startsWith("ANALYSIS2:") || command.startsWith("ANALYSIS3:") ? "ANALYSIS" :
                        command.startsWith("XC:") ? "XC:" :
                        command.startsWith("MODEL:") ? "MODEL:" :
                        command.startsWith("ENV:") ? "ENV:" :
                        command.startsWith("GUIDE_FADE:") ? "GUIDE_FADE:" :
                        command.startsWith("CAPO:") ? "CAPO:" :
                        command.startsWith("FX3:") ? "FX3:" :
                        command.startsWith("TC:") ? "TC:" :
                        command.startsWith("GESTURE:") ? "GESTURE:" :
                        command.startsWith("SP1:1,") ? "SP1:1," : null;
        if (family != null) {
            boolean head = true;
            for (Iterator<String> i = commands.iterator(); i.hasNext();) {
                String old = i.next();
                if (!(head && protectHead) && old.startsWith(family)) i.remove();
                head = false;
            }
        }
        if (commands.size() >= 8) return false;
        commands.add(command);
        return true;
    }

    int cancelPending(String command, boolean preserveInFlightHead) {
        int removed = 0;
        boolean head = true;
        for (Iterator<String> iterator = commands.iterator(); iterator.hasNext();) {
            String queued = iterator.next();
            if (!(head && preserveInFlightHead) && queued.equals(command)) { iterator.remove(); removed++; }
            head = false;
        }
        return removed;
    }

    String peek() { return commands.peek(); }
    int cancelSongControls(boolean protectHead){
        int removed=0;boolean head=true;
        for(Iterator<String> it=commands.iterator();it.hasNext();){String c=it.next();
            if(!(head&&protectHead)&&(c.equals("SONG_PLAY")||(c.startsWith("SP1:")&&!c.startsWith("SP1:4,")))){it.remove();removed++;}
            head=false;
        }return removed;
    }
    boolean offerPower(String command, boolean protectHead) {
        String inFlight=protectHead?commands.peek():null;
        commands.clear();
        if(inFlight!=null)commands.add(inFlight);
        commands.add(command);
        return true;
    }
    String poll() { return commands.poll(); }
    boolean isEmpty() { return commands.isEmpty(); }
    int size() { return commands.size(); }
    void clear() { commands.clear(); }
}
