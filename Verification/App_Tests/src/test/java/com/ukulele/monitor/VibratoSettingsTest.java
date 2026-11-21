package com.ukulele.monitor;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.SeekBar;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class VibratoSettingsTest {
    private static void put16(byte[] data,int at,int value) {
        data[at]=(byte)value;
        data[at+1]=(byte)(value>>8);
    }
    private static byte[] finish(byte[] data) {
        for(int i=0;i<data.length-1;i++)data[data.length-1]^=data[i];
        return data;
    }
    private static TouchState touch(int threshold,int fast,int grace,int ticket,int revision,int result) {
        byte[] d={(byte)0xa5,1,0,0,(byte)fast,(byte)grace,0,0,(byte)fast,(byte)grace,7,(byte)result,0,0,0,0,1,7,0,0};
        put16(d,2,threshold);put16(d,6,threshold);put16(d,12,ticket);put16(d,14,revision);
        return TouchState.parse(finish(d));
    }
    private static GestureState gesture(int mask,int ticket,int revision) {
        byte[] d={(byte)0xa4,2,(byte)mask,(byte)mask,2,7,0,0,0,0,7,0,0};
        put16(d,6,ticket);put16(d,8,revision);
        return GestureState.parse(finish(d));
    }
    private static void touchState(PracticeFlowTest h,int threshold,int fast,int grace,int ticket,int revision,int result) throws Exception {
        h.call("applyTouchState",new Class[]{TouchState.class},touch(threshold,fast,grace,ticket,revision,result));
    }
    private static void gestureState(PracticeFlowTest h,int mask,int ticket,int revision) throws Exception {
        h.call("applyGestureState",new Class[]{GestureState.class},gesture(mask,ticket,revision));
    }
    private static void raiseThreshold(SeekBar bar) {
        bar.setKeyProgressIncrement(1);
        assertTrue(bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)));
    }

    @Test public void thresholdAndTimingSaveOnlyTheirOwnDrafts() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try {
            AppRefinementTest.loadPackagedResources(h.host);
            h.call("buildSettingsPage");
            SeekBar bar=h.field("touchThresholdBar");
            TextView save=h.field("touchThresholdApply");
            TextView saveTiming=h.field("touchApply");
            TextView source=h.field("vibratoSourceButton");
            ViewParent card=bar.getParent();
            assertSame(card,source.getParent().getParent());
            assertSame(card,save.getParent().getParent());
            assertFalse(bar.isEnabled());
            h.fakeDevice();
            touchState(h,360,0,0,0,1,2);
            gestureState(h,7,0,1);
            assertTrue(bar.isEnabled());
            assertEquals("颤音判定 · 辅助电容揉弦 ▾",source.getText().toString());
            ((View)h.field("touchQuick")).performClick();
            raiseThreshold(bar);
            assertEquals(380,(int)h.field("touchThresholdDraft"));
            assertTrue(save.performClick());
            ControlCommandQueue queue=h.field("commandQueue");
            assertEquals("TC:380,0,0,1",queue.poll());
            assertFalse(saveTiming.isEnabled());
            touchState(h,380,0,0,1,2,2);
            assertFalse(save.isEnabled());
            assertTrue(saveTiming.isEnabled());
            raiseThreshold(bar);
            assertTrue(saveTiming.performClick());
            assertEquals("TC:380,1,6,2",queue.poll());
            touchState(h,380,1,6,2,3,2);
            assertEquals(400,(int)h.field("touchThresholdDraft"));
            assertTrue(save.isEnabled());
            assertFalse(saveTiming.isEnabled());
            assertTrue(((TextView)h.field("touchThresholdStatus")).getText().toString().contains("当前生效 380"));
        } finally {h.cleanup();}
    }

    @Test public void switchingSourceWaitsForReadbackAndKeepsThresholdDraft() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try {
            AppRefinementTest.loadPackagedResources(h.host);
            h.call("buildSettingsPage");h.fakeDevice();
            touchState(h,360,0,0,0,1,2);
            gestureState(h,7,0,1);
            TextView source=h.field("vibratoSourceButton");
            raiseThreshold((SeekBar)h.field("touchThresholdBar"));
            assertTrue(source.performClick());
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            View legacy=h.find(dialog.getWindow().getDecorView(),"原品格判定");
            assertNotNull(legacy);
            assertTrue(((View)legacy.getParent().getParent()).performClick());
            ControlCommandQueue queue=h.field("commandQueue");
            assertEquals("GESTURE:3,1",queue.poll());
            assertTrue(source.getText().toString().contains("辅助电容揉弦"));
            gestureState(h,3,1,2);
            assertEquals("颤音判定 · 原品格判定 ▾",source.getText().toString());
            assertEquals(380,(int)h.field("touchThresholdDraft"));
            assertTrue(((View)h.field("touchThresholdApply")).isEnabled());
            View card=(View)((SeekBar)h.field("touchThresholdBar")).getParent();
            h.layout(card,360,525);h.png(card,"vibrato-settings");
        } finally {h.cleanup();}
    }
}
