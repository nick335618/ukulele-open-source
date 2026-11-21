package com.ukulele.monitor.ai;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Activity-owned permission result; no timer can mistake a slow user decision for denial. */
@SuppressWarnings("deprecation")
public final class MicrophonePermission extends Fragment {
    public interface Result { void complete(boolean granted); }
    private Result result;
    private boolean requested;
    public static void request(Activity activity, Result result) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            result.complete(true);
            return;
        }
        try {
            MicrophonePermission bridge = new MicrophonePermission();
            bridge.result = result;
            activity.getFragmentManager().beginTransaction().add(bridge, "ukulele-microphone-permission").commitNow();
        } catch (RuntimeException e) {
            result.complete(false);
        }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!requested) {
            requested = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        if (requestCode != 1) return;
        Result callback = result;
        result = null;
        if (callback != null) callback.complete(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED);
        if (isAdded()) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }
    @Override public void onDestroy() { result = null; super.onDestroy(); }
}
