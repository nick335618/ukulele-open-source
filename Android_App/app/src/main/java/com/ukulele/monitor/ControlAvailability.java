package com.ukulele.monitor;

/** Transport readiness is separate from the arrival of the settings snapshot. */
final class ControlAvailability {
    static boolean canSend(boolean bleReady, int knownVersion, String command) {
        if (!bleReady) return false;
        // Known old firmware cannot implement mode switching. An unknown peer
        // may be probed, but the UI must await real confirmation.
        return !command.startsWith("PLAYMODE:") || knownVersion != 1;
    }
}
