package com.ukulele.monitor;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.SplashOverlay;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppRefinementTest {
    private final File previews = new File("../Screenshots");

    static void loadPackagedResources(android.content.Context context) throws Exception {
        android.content.res.AssetManager assets = context.getResources().getAssets();
        Object cookie = assets.getClass().getMethod("addAssetPath", String.class).invoke(assets,
            "../../Firmware/Android/UkuleleStudio-v3.19.12-debug.apk");
        assertTrue(((Integer) cookie) != 0);
    }

    private void capture(View view, int width, int height, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        previews.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(previews, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }

    private void motion(View view, long down, long at, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private void doubleTap(PracticeFlowTest h, View view) {
        long now = SystemClock.uptimeMillis();
        motion(view, now, now, MotionEvent.ACTION_DOWN, 180, 150);
        motion(view, now, now + 20, MotionEvent.ACTION_UP, 180, 150);
        h.advance(90);
        long second = SystemClock.uptimeMillis();
        motion(view, second, second, MotionEvent.ACTION_DOWN, 180, 150);
        motion(view, second, second + 20, MotionEvent.ACTION_UP, 180, 150);
        h.advance(340);
    }

    private View icon(View root, String label) {
        if (label.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = icon(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private EditText search(View root) {
        if (root instanceof EditText) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText match = search(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    @Test public void classicLaunchDrawsFromFirstFrameAndFinishesOnce() throws Exception {
        PracticeFlowTest h = new PracticeFlowTest(); h.setup();
        try {
            loadPackagedResources(h.host);
            assertNotNull(h.host.getDrawable(R.drawable.ukulele_icon_transparent));
            android.app.Activity stage = Robolectric.buildActivity(android.app.Activity.class).setup().get();
            SplashOverlay overlay = new SplashOverlay(stage);
            stage.setContentView(overlay);
            AtomicInteger completions = new AtomicInteger();
            overlay.start("Ukulele Studio","v3.19.8",null,completions::incrementAndGet);
            assertTrue(overlay.isRunning());
            capture(overlay,393,850,"splash-first-frame");
            long early=SystemClock.uptimeMillis();
            motion(overlay,early,early,MotionEvent.ACTION_DOWN,180,150);
            motion(overlay,early,early+20,MotionEvent.ACTION_UP,180,150);
            h.advance(550);
            assertEquals(0,completions.get());
            capture(overlay,393,850,"splash-classic");
            long now=SystemClock.uptimeMillis();
            motion(overlay,now,now,MotionEvent.ACTION_DOWN,180,150);
            motion(overlay,now,now+20,MotionEvent.ACTION_UP,180,150);
            h.advance(299);
            assertEquals(0,completions.get());
            h.advance(1);
            assertEquals(1,completions.get());
            assertFalse(overlay.isRunning());
            h.advance(2500);
            assertEquals(1,completions.get());
            overlay.start("Ukulele Studio","v3.19.8",null,completions::incrementAndGet);
            h.advance(2100);
            assertEquals(2,completions.get());
            overlay.start("Ukulele Studio","v3.19.8",null,completions::incrementAndGet);
            overlay.cancel();
            h.advance(2200);
            assertEquals(2,completions.get());
        } finally { h.cleanup(); }
    }

    @Test public void separatesLibraryTypesAndKeepsRecordedOriginOnRename() throws Exception {
        PracticeFlowTest h = new PracticeFlowTest(); h.setup();
        try {
            ScoreRepository repo = new ScoreRepository(RuntimeEnvironment.getApplication());
            byte[] midi = new byte[]{'M','T','h','d',0};
            ScoreRepository.Item imported = repo.importFile("测试外部 MIDI", "application/octet-stream", midi);
            repo.importFile("测试 PDF", "application/pdf", new byte[]{1});
            repo.importFile("测试照片", "image/jpeg", new byte[]{1});
            ScoreRepository.Item take = repo.saveRecording("测试录奏", midi);
            repo.rename(take.id, "改名后的录奏");
            ScoreRepository.Item legacy = repo.importFile("演奏录音 11-07 09-15-21.mid","audio/midi",midi);
            JSONObject meta = legacy.json();meta.remove("recording");
            ScoreRepository.atomic(new File(repo.folder(legacy.id), "metadata.json"),meta.toString().getBytes("UTF-8"));
            assertEquals(ScoreRepository.Category.RECORDING,repo.get(legacy.id).category());
            assertEquals(ScoreRepository.Category.MIDI,repo.get(imported.id).category());
            assertEquals(ScoreRepository.Category.RECORDING,repo.get(take.id).category());
            ScoreLibraryActivity library = Robolectric.buildActivity(ScoreLibraryActivity.class).setup().get();
            View root = library.findViewById(android.R.id.content);
            h.find(root, "MIDI").performClick();
            assertNotNull(h.find(root,"测试外部 MIDI"));assertNull(h.find(root,"测试 PDF"));assertNull(h.find(root,"改名后的录奏"));
            h.find(root,"PDF／照片").performClick();
            assertNotNull(h.find(root,"测试照片"));assertNotNull(h.find(root,"测试 PDF"));assertNull(h.find(root,"测试外部 MIDI"));
            h.find(root,"录奏").performClick();
            assertNotNull(h.find(root,"改名后的录奏"));assertNotNull(h.find(root,"演奏录音 11-07 09-15-21.mid"));
            capture(root, 393, 850, "my-library");
            search(root).setText("改名");
            assertNull(h.find(root,"演奏录音 11-07 09-15-21.mid"));assertNotNull(h.find(root,"改名后的录奏"));
            library.finish();
        } finally { h.cleanup(); }
    }

    @Test public void practicePagesHaveFixedSizeAndHorizontalSwipe() throws Exception {
        PracticeFlowTest h = new PracticeFlowTest(); h.setup();
        try {
            ArrayList<SongLibrary.Song> items = new ArrayList<>();
            for (int i = 0; i < 11; i++) items.add(new SongLibrary.Song(new JSONObject()
                 .put("id","test"+i).put("title",i==10?"茉莉":("分页曲目 "+i)).put("asset","songs/demo.uks")
                 .put("difficulty",i==10?"挑战":"入门").put("bpm",90+i).put("durationMs",1000).put("noteCount",5)));
            org.robolectric.util.ReflectionHelpers.setStaticField(SongLibrary.class,"cached",items);
            View page=(View)h.call("buildSongPage");
            h.host.setContentView(page);
            h.layout(page,393,850);
            ScorePageList rows=h.field("practicePageList");
            TextView previous=h.field("libraryPrevious"), next=h.field("libraryNext"), number=h.field("libraryPageLabel");
            assertEquals(5,rows.getChildCount());assertFalse(previous.isEnabled());assertTrue(next.isEnabled());assertEquals("1 / 3",number.getText());
            long now=SystemClock.uptimeMillis();
            motion(rows,now,now,MotionEvent.ACTION_DOWN,245,34);
            motion(rows,now,now+30,MotionEvent.ACTION_MOVE,119,35);
            motion(rows,now,now+60,MotionEvent.ACTION_UP,66,35);
            assertEquals("2 / 3",number.getText());assertEquals(5,rows.getChildCount());assertNotNull(h.find(rows,"分页曲目 5"));
            next.performClick();assertEquals(1,rows.getChildCount());assertFalse(next.isEnabled());assertEquals("3 / 3",number.getText());
            previous.performClick();assertEquals("2 / 3",number.getText());
            capture((View)rows.getParent(),353,480,"practice-pagination");
            search(page).setText("茉莉");h.advance(200);
            assertEquals("1 / 1",number.getText());assertEquals(1,rows.getChildCount());
        } finally { h.cleanup(); }
    }

    @Test public void recorderIsBelowBothMetrics() throws Exception {
        PracticeFlowTest h = new PracticeFlowTest(); h.setup();
        try {
            View root=(View)h.call("buildDashboardPage");
            h.layout(root,393,850);
            View record=h.find(root,"演奏录音机");
            assertTrue(h.rect(record,root).top > h.rect(h.find(root,"峰值频率"),root).bottom);
            assertTrue(h.rect(record,root).top > h.rect(h.find(root,"当前音符"),root).bottom);
        } finally { h.cleanup(); }
    }

    @Test public void immersiveGearCollapsesOnlyOnStartAndDoubleTapControlsLocal() throws Exception {
        PracticeFlowTest h = new PracticeFlowTest(); h.setup();
        try {
            loadPackagedResources(h.host);
            SongGuideView guide=h.field("songGuideView");
            h.call("openFullScreen",new Class[]{View.class,boolean.class},guide,true);
            View stage=(View)guide.getParent();
            View menu=h.field("fullPracticeOptions");
            h.layout(stage, 780, 360);
            assertEquals(780,guide.getWidth());assertEquals(360,guide.getHeight());assertEquals(View.GONE,menu.getVisibility());
            capture(stage,780,360,"fullscreen-guide");
            icon(stage,"跟弹设置").performClick();assertEquals(View.VISIBLE,menu.getVisibility());
            h.find(menu,"弦序 1→4").performClick();assertNotNull(h.find(menu,"弦序 4→1"));
            h.find(menu,"左侧基准").performClick();assertNotNull(h.find(menu,"右侧基准"));
            assertEquals(View.VISIBLE,menu.getVisibility());
            capture(stage,780,360,"fullscreen-settings");
            h.find(menu,"开始无灯光指引").performClick();assertEquals(View.GONE,menu.getVisibility());
            h.advance(3001);assertTrue((boolean)h.field("guideRunning"));
            doubleTap(h,guide);assertTrue((boolean)h.field("localGuidePaused"));assertFalse((boolean)h.field("guideRunning"));
            doubleTap(h,guide);assertTrue((boolean)h.field("guideRunning"));
            icon(stage,"重新开始").performClick();assertTrue(((PracticeCountdown)h.field("fullCountdown")).active());
            Dialog d=h.field("fullScreenDialog");d.dismiss();h.advance(0);
            assertNull(h.field("fullScreenDialog"));
        } finally { h.cleanup(); }
    }

    @Test public void immersiveLightModeUsesTheSameGestureForDevicePauseAndResume() throws Exception {
        PracticeFlowTest h = new PracticeFlowTest(); h.setup();
        try {
            loadPackagedResources(h.host);
            h.fakeDevice();
            SongGuideView guide=h.field("songGuideView");
            h.call("openFullScreen",new Class[]{View.class,boolean.class},guide,true);
            View stage=(View)guide.getParent();h.layout(stage,780,360);
            View menu=h.field("fullPracticeOptions");
            icon(stage,"跟弹设置").performClick();
            h.find(menu,"开始灯光指引").performClick();assertEquals(View.GONE,menu.getVisibility());
            assertTrue(((PracticeCountdown)h.field("fullCountdown")).active());assertTrue((boolean)h.field("fullCountdownDevice"));
            h.advance(3000);
            int token=h.field("pendingSongControlToken");assertTrue(token>0);
            h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},h.playback(0,token,1,0,1));
            assertTrue((boolean)h.field("guideDeviceReference"));
            doubleTap(h,guide);assertEquals(2,(int)h.field("pendingSongControlOp"));
            token=h.field("pendingSongControlToken");
            h.call("applyDeviceSongState",new Class[]{SongPlaybackState.class},h.playback(2,token,2,120,2));
            doubleTap(h,guide);assertEquals(3,(int)h.field("pendingSongControlOp"));
            ((Dialog)h.field("fullScreenDialog")).dismiss();h.advance(0);
        } finally { h.cleanup(); }
    }

    @Test public void myLibraryPagesAndRecordingButtonRoutesToRecorder() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest(); h.setup();
        try {
            ScoreRepository repo=new ScoreRepository(RuntimeEnvironment.getApplication());
            for(int i=0;i<11;i++)repo.saveRecording("录奏分段"+i,new byte[]{'M','T','h','d',0});
            ScoreLibraryActivity library=Robolectric.buildActivity(ScoreLibraryActivity.class).setup().get();
            View root=library.findViewById(android.R.id.content);
            h.find(root,"录奏").performClick();
            h.layout(root,393,850);
            ScorePageList rows=org.robolectric.util.ReflectionHelpers.getField(library,"listContainer");
            assertEquals(5,rows.getChildCount());assertNotNull(h.find(root,"1 / 3"));assertNull(h.find(root,"导入曲谱"));
            long now=SystemClock.uptimeMillis();
            motion(rows,now,now,MotionEvent.ACTION_DOWN,228,31);
            motion(rows,now,now+30,MotionEvent.ACTION_MOVE,120,32);
            motion(rows,now,now+60,MotionEvent.ACTION_UP,60,33);
            assertEquals(5,rows.getChildCount());assertNotNull(h.find(root,"2 / 3"));
            h.find(root,"下一页 ›").performClick();
            assertEquals(1,rows.getChildCount());assertNotNull(h.find(root,"3 / 3"));
            capture(root,393,850,"my-library-paged");
            h.find(root,"去录奏").performClick();
            assertEquals(ScoreLibraryActivity.RESULT_RECORD,org.robolectric.Shadows.shadowOf(library).getResultCode());
            View dashboard=(View)h.call("buildDashboardPage");
            ((View[])h.field("pages"))[0]=dashboard;
            h.call("onActivityResult",new Class[]{int.class,int.class,android.content.Intent.class},
                    73,ScoreLibraryActivity.RESULT_RECORD,null);
            h.layout(h.field("pageHost"),393,850);
            h.advance(420);
            assertEquals(0,(int)h.field("currentPage"));
            assertTrue("scroll to recorder",dashboard.getScrollY()>0);
        } finally {h.cleanup();}
    }

    @Test public void countdownLeavesLanesUnchangedAndCandyBitesOnTheBeat() throws Exception {
        PracticeFlowTest h=new PracticeFlowTest();h.setup();
        try {
            SongGuideView guide=new SongGuideView(h.host);guide.setControlInset(106);
            SongTimeline chart=SongTimeline.parse(GuideEventText.legacy(
                "500,1,2,200\n950,2,0,200\n1250,4,5,200\n1600,8,3,200\n2000,0,0,0\n"));
            guide.setTimeline(chart);
            guide.setScene(GuideScene.TIDE);
            h.layout(guide,780,360);
            Bitmap before=Bitmap.createBitmap(780,360,Bitmap.Config.ARGB_8888);
            guide.draw(new Canvas(before));
            guide.setCountdown(2);
            Bitmap after=Bitmap.createBitmap(780,360,Bitmap.Config.ARGB_8888);
            guide.draw(new Canvas(after));
            assertNotEquals(before.getPixel(349,18),after.getPixel(349,18));
            for(int x=30;x<750;x+=13)for(int y=54;y<346;y+=11)assertEquals(before.getPixel(x,y),after.getPixel(x,y));
            capture(guide,780,360,"countdown-clear-lanes");
            before.recycle();after.recycle();
            guide.setCountdown(0);
            assertSame(GuideScene.CANDY,GuideScene.byId("candy"));
            guide.setScene(GuideScene.CANDY);
            SongTimeline.Span first=chart.spans.get(0);
            guide.setElapsed(first.startMs-100);
            capture(guide,780,360,"candy-approach");
            long[] soon=org.robolectric.util.ReflectionHelpers.getField(guide,"candyNext");
            int lane=com.ukulele.monitor.ui.GuideLayout.laneForColumn(first.column,false);
            assertEquals(100,soon[lane]);
            guide.setElapsed(first.startMs+35);
            capture(guide,780,360,"candy-bite");
            long[] eaten=org.robolectric.util.ReflectionHelpers.getField(guide,"candyBite");
            assertEquals(35,eaten[lane]);
            guide.setLandingRight(true);guide.setStringsReversed(true);
            capture(guide,780,360,"candy-mirrored");
            assertEquals(35,eaten[3-lane]);
        } finally {h.cleanup();}
    }

}
