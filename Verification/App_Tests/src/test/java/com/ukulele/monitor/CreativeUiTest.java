package com.ukulele.monitor;

import android.app.Dialog;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.SeekBar;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.SettingsStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CreativeUiTest {
    @Test public void paletteChoicesPersistAndRenderActualDashboard() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();
        h.setup();
        try {
            SettingsStore settings=h.field("settings");
            for(AppTheme.Style style:AppTheme.Style.values()){
                if(style.ordinal()<3)continue;
                settings.setThemeMode(style.id);
                assertEquals(style.id,SettingsStore.store(h.host).themeMode());
                h.call("resolveTheme");
                assertSame(style.preview,AppTheme.colors());
                View dashboard=(View)h.call("buildDashboardPage");
                h.layout(dashboard,393,850);
                h.png(dashboard,"theme-"+style.id);
            }
        } finally {h.cleanup();}
    }

    @Test public void bothGuideStylesRetainTimeAndImportedChords() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try {
            SongGuideView guide=h.field("songGuideView");
            String chart="0,1,0,200\n450,2,2,200\n0,4,3,200\n0,8,2,200\n700,0,0,0\n1050,1,7,200\n1350,2,4,200\n1700,4,0,200\n2000,8,3,200\n2450,1,5,200\n2750,2,7,200\n3200,4,2,200\n3500,0,0,0\n";
            SongTimeline song=SongTimeline.parse(GuideEventText.legacy(chart));
            guide.setTimeline(song);guide.setElapsed(380);guide.setCapo(0);
            h.call("openFullScreen",new Class[]{View.class,boolean.class},guide,true);
            Dialog dialog=h.field("fullScreenDialog");
            View root=dialog.getWindow().getDecorView();h.layout(root,864,440);
            h.png(root,"classic-guide");
            h.call("setGuideScene",new Class[]{GuideScene.class},GuideScene.STARS);h.layout(root,864,440);h.png(root,"star-river");
            assertEquals(380L,(long)org.robolectric.util.ReflectionHelpers.getField(guide,"elapsedMs"));
            assertSame(song,org.robolectric.util.ReflectionHelpers.getField(guide,"timeline"));
            assertNotNull(h.find(root,"星河跟弹 ▾"));
            h.call("toggleGuideDirection");h.call("toggleGuideStrings");h.png(root,"star-river-reverse");
            h.call("setGuideScene",new Class[]{GuideScene.class},GuideScene.ENSEMBLE);h.layout(root,864,440);h.png(root,"elf-ensemble");
            h.call("setGuideScene",new Class[]{GuideScene.class},GuideScene.TIDE);h.layout(root,864,440);h.png(root,"moon-tide");
            assertEquals(380L,(long)org.robolectric.util.ReflectionHelpers.getField(guide,"elapsedMs"));
            dialog.dismiss();h.advance(0);
        } finally {h.cleanup();}
    }

    @Test public void musicPaintingBloomsAndCanPauseAndClear() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try {
            h.call("openJamCanvas");
            JamCanvasView canvas=h.field("jamCanvas");
            Dialog dialog=h.field("jamDialog");View root=dialog.getWindow().getDecorView();
            h.layout(root,393,850);
            h.call("startPaintingDemo",new Class[]{JamCanvasView.class},canvas);
            h.advance(7200);
            assertTrue(canvas.hasArt());assertNotNull(canvas.snapshot());
            h.png(root,"jam-paint");
            canvas.setFrozen(true);assertTrue(canvas.isFrozen());canvas.clear();assertFalse(canvas.hasArt());h.png(root,"jam-empty");
            dialog.dismiss();h.advance(0);assertNull(h.field("jamCanvas"));
        } finally {h.cleanup();}
    }

    @Test public void envelopeFitsSmallScreensAndKeepsTimbresIndependent() throws Exception {
        EnvelopeState confirmed=envPacket(0,60,235,1,2,1<<4);
        assertNotNull(confirmed);
        assertNull(envPacket(0,60,235,1,2,1));
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            try(java.io.InputStream in=new java.io.FileInputStream(
               "../Inputs/original_envelope_model.bin")){
                EnvelopeModel model=new EnvelopeModel(in);
                h.field("envelopeModel",model);
                assertFalse(java.util.Arrays.equals(model.preview(60,0,0,235,600),model.preview(64,0,0,235,600)));
                assertFalse(java.util.Arrays.equals(model.preview(60,0,0,235,600),model.preview(60,1,0,235,600)));
                float[] reduced=model.preview(60,0,0,11,600);
                float peak=0;
                for(float v:reduced)peak=Math.max(peak,v);
                assertEquals(.25f,peak,.01f);
            }
            h.call("openEnvelope");
            Dialog dialog=h.field("envelopeDialog");View root=dialog.getWindow().getDecorView();
            h.layout(root,393,670);
            SeekBar[] bars=h.field("envelopeBars");
            assertEquals(3,bars.length);
            assertEquals(7,bars[0].getMax());
            assertEquals(7,bars[2].getMax());
            assertTrue(bars[0].isEnabled());
            h.call("queueEnvelope",new Class[]{int.class,int.class,int.class,boolean.class},1,60,11,true);
            assertEquals(0,bars[2].getProgress());
            h.call("selectEnvelopeNote",new Class[]{int.class,int.class,int.class},60,1,0);
            assertEquals(7,bars[2].getProgress());
            h.call("selectEnvelopeNote",new Class[]{int.class,int.class,int.class},60,0,0);
            assertEquals(0,bars[2].getProgress());

            h.fakeDevice();
            h.call("receiveEnvelope",new Class[]{EnvelopeState.class},confirmed);
            assertEquals(7,bars[2].getProgress());
            assertTrue(((android.widget.TextView)h.field("envelopeModified")).getText().toString().contains("E4"));
            h.call("queueEnvelope",new Class[]{int.class,int.class,int.class,boolean.class},1,60,239,true);
            ControlCommandQueue queue=h.field("commandQueue");
            assertTrue(queue.peek().startsWith("ENV:1,0,60,239,"));
            int first=h.field("envelopeTicket");queue.poll();
            h.call("receiveEnvelope",new Class[]{EnvelopeState.class},envPacket(0,60,239,first,2,17));
            h.call("selectEnvelopeNote",new Class[]{int.class,int.class,int.class},60,1,0);
            assertTrue(queue.peek().startsWith("ENV:0,1,60,235,"));
            int second=h.field("envelopeTicket");queue.poll();
            h.call("receiveEnvelope",new Class[]{EnvelopeState.class},envPacket(1,60,235,second,2,0));
            assertEquals(3,bars[0].getProgress());
            h.call("selectEnvelopeNote",new Class[]{int.class,int.class,int.class},60,0,0);
            assertEquals(7,bars[0].getProgress());

            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,dialog.getWindow().getAttributes().width);
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,dialog.getWindow().getAttributes().height);
            ViewGroup windowContent=dialog.findViewById(android.R.id.content);
            ViewGroup page=(ViewGroup)windowContent.getChildAt(0);
            View finish=h.find(page,"完成");
            android.widget.ScrollView form=(android.widget.ScrollView)page.getChildAt(1);
            h.layout(root,360,440);
            assertTrue(form.canScrollVertically(1));
            int[] before=new int[2],after=new int[2];
            finish.getLocationInWindow(before);
            form.setSmoothScrollingEnabled(false);
            form.fullScroll(View.FOCUS_DOWN);
            assertTrue(form.getScrollY()>0);
            finish.getLocationInWindow(after);
            assertArrayEquals(before,after);
            form.scrollTo(0,0);
            h.layout(root,393,870);h.png(root,"envelope-expanded");
            ((View)h.field("envelopeReset")).performClick();
            assertTrue(queue.peek().startsWith("ENV:1,0,60,235,"));
            int last=h.field("envelopeTicket");queue.poll();
            h.call("receiveEnvelope",new Class[]{EnvelopeState.class},envPacket(0,60,235,last,2,16));
            ((View)h.field("envelopeResetAll")).performClick();h.clickDialog("恢复全部");
            assertTrue(queue.peek().startsWith("ENV:3,0,60,235,"));
            finish.performClick();h.advance(0);
            assertFalse(dialog.isShowing());
        }finally{h.cleanup();}
    }

    private EnvelopeState envPacket(int bank,int midi,int value,int ticket,int result,int mask){
        byte[] b={(byte)0xb5,3,1,(byte)bank,(byte)(midi-60),(byte)value,(byte)ticket,(byte)(ticket>>8),(byte)result,
            (byte)mask,(byte)(mask>>8),(byte)(mask>>16),(byte)(mask>>24),0};
        for(int i=0;i<13;i++)b[13]^=b[i];
        return EnvelopeState.parse(b);
    }
    @Test public void careerPersistsThreeAttemptsAndUnlocksTheNextStage() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try{
            PracticeScorer scorer=new PracticeScorer(SongTimeline.parse(h.song),1);
            android.content.SharedPreferences prefs=(android.content.SharedPreferences)h.call("careerProgress");
            for(int attempt=1;attempt<=3;attempt++){
                h.field("careerStage",0);
                h.call("finishCareer",new Class[]{PracticeScorer.Snapshot.class},scorer.snapshot());
                assertEquals(attempt,prefs.getInt("fail_0",0));
                assertEquals(0,prefs.getInt("unlocked",0));
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();h.advance(0);
            }
            org.robolectric.util.ReflectionHelpers.setField(scorer,"correct",scorer.snapshot().total);
            h.field("careerStage",0);
            h.call("finishCareer",new Class[]{PracticeScorer.Snapshot.class},scorer.snapshot());
            assertEquals(0,prefs.getInt("fail_0",-1));
            assertEquals(1,prefs.getInt("unlocked",0));
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
        }finally{h.cleanup();}
    }

    @Test public void batteryAtTopUsesDeviceReportAndClearsWhenStale() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();
        h.setup();
        try {
            View dashboard=(View)h.call("buildDashboardPage");
            h.layout(dashboard,393,300);
            View title=h.find(dashboard,"乐器电量");
            assertTrue(h.rect(title,dashboard).top<h.rect(h.find(dashboard,"设备工作状态"),dashboard).top);
            android.widget.TextView percent=h.field("batteryPercentDash");
            android.widget.TextView status=h.field("batteryStatusDash");
            android.widget.ProgressBar bar=h.field("batteryBarDash");
            BatteryGaugeView gauge=h.field("batteryGaugeDash");
            assertEquals("—",percent.getText().toString());
            assertEquals("设备未连接",status.getText().toString());
            h.fakeDevice();
            for(int charge : new int[]{1,2,3,4,0}) {
                h.call("parseNotify",new Class[]{java.util.UUID.class,byte[].class},
                    java.util.UUID.fromString("6b1e0002-8f3a-4c2b-9d5e-1a2b3c4d5e6f"),batteryPacket(7850,78,charge));
                h.advance(0);
                assertEquals("4 格电量",percent.getText().toString());
                assertEquals(4,bar.getProgress());
                assertEquals(charge==2||charge==3,(boolean)org.robolectric.util.ReflectionHelpers.getField(gauge,"charging"));
                if(charge==1||charge==2){h.layout(dashboard,393,300);h.png(dashboard,charge==2?"battery-charging":"battery-idle");}
            }
            h.advance(6500);
            assertEquals("—",percent.getText().toString());
            assertEquals("等待电量回读",status.getText().toString());
            assertEquals(0,bar.getProgress());
            assertFalse((boolean)org.robolectric.util.ReflectionHelpers.getField(gauge,"charging"));
        } finally {h.cleanup();}
    }

    private static byte[] batteryPacket(int mv,int pct,int charge){
        byte[] p={(byte)0xb4,1,(byte)mv,(byte)(mv>>8),(byte)pct,(byte)charge,0,0};
        for(int i=0;i<7;i++)p[7]^=p[i];
        return p;
    }
}
