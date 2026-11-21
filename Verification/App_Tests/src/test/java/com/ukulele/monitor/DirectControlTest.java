package com.ukulele.monitor;

import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,qualifiers="w393dp-h850dp-mdpi",shadows={PracticeFlowTest.PreviewShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DirectControlTest {
    private PracticeFlowTest h;
    @Before public void setup() throws Exception {h=new PracticeFlowTest();h.setup();}
    @After public void cleanup() throws Exception {h.cleanup();}

    private void model(int actual,int revision,int ticket,int result) throws Exception {
        boolean fresh=actual!=255;
        byte[] b={(byte)0xad,1,(byte)(fresh?actual:0),(byte)actual,(byte)result,(byte)(fresh?7:0),
            (byte)ticket,(byte)(ticket>>8),(byte)revision,(byte)(revision>>8),(byte)(fresh?3:0),0,0};
        for(int i=0;i<12;i++)b[12]^=b[i];
        ModelState state=ModelState.parse(b);assertNotNull(state);
        h.call("receiveModel",new Class[]{ModelState.class},state);
    }

    private void chain(int mask,int revision,int ticket) throws Exception {
        byte[] b={(byte)0xae,1,1,(byte)mask,64,48,48,64,0,(byte)mask,23,2,
            (byte)ticket,(byte)(ticket>>8),(byte)revision,(byte)(revision>>8),0,127,0,0};
        for(int i=0;i<19;i++)b[19]^=b[i];
        ChainState state=ChainState.parse(b);assertNotNull(state);
        h.call("receiveChain",new Class[]{ChainState.class},state);
    }

    @Test public void offlineAndEspOnlyDashboardCanRenderAndRecoverWhenFpgaComesOnline() throws Exception {
        View offline=(View)h.call("buildDashboardPage");assertNotNull(offline);
        List<TextView> tones=h.field("modelChoices");assertFalse(tones.get(0).isEnabled());
        h.fakeDevice();model(255,1,0,5);
        assertNotNull(h.call("buildDashboardPage"));
        assertFalse(tones.get(0).isEnabled());
        assertTrue(((TextView)h.field("modelStatus")).getText().toString().contains("等待 FPGA"));
        model(2,2,0,2);assertTrue(tones.get(2).isEnabled());
        assertTrue(((TextView)h.field("modelStatus")).getText().toString().contains("金属拨片"));
        model(255,3,0,5);assertFalse(tones.get(2).isEnabled());
    }

    @Test public void fourTonesSwitchOnTapAndStaleAcknowledgementsCannotUndoLatestChoice() throws Exception {
        h.fakeDevice();h.call("buildDashboardPage");model(0,1,0,2);
        List<TextView> tones=h.field("modelChoices");ControlCommandQueue q=h.field("commandQueue");
        tones.get(1).performClick();int old=h.field("modelTicket");assertEquals("MODEL:1,"+old,q.peek());
        tones.get(2).performClick();int current=h.field("modelTicket");assertEquals("MODEL:2,"+current,q.peek());
        model(1,2,old,2);assertTrue((boolean)h.field("modelPending"));assertEquals(2,(int)h.field("pendingModel"));
        model(2,3,current,2);assertFalse((boolean)h.field("modelPending"));
        tones.get(3).performClick();current=h.field("modelTicket");model(3,4,current,2);
        tones.get(0).performClick();current=h.field("modelTicket");model(0,5,current,2);
        assertFalse((boolean)h.field("modelPending"));assertTrue(((TextView)h.field("modelStatus")).getText().toString().contains("尤克里里"));
    }

    @Test public void effectsSendImmediatelyAndKeepCombinedIntentAcrossOldReadbacks() throws Exception {
        h.fakeDevice();LinearLayout panel=new LinearLayout(h.host);panel.setOrientation(1);
        h.call("addChainControls",new Class[]{LinearLayout.class},panel);chain(0,1,0);
        CheckBox[] effects=h.field("chainChecks");ControlCommandQueue q=h.field("commandQueue");
        effects[0].performClick();int old=h.field("chainTicket");assertTrue(q.peek().startsWith("XC:01"));
        effects[3].performClick();int current=h.field("chainTicket");assertTrue(q.peek().startsWith("XC:09"));
        chain(1,2,old);assertTrue(effects[0].isChecked());assertTrue(effects[3].isChecked());
        chain(9,3,current);assertFalse((boolean)h.field("chainPending"));
        ((View)h.field("chainClear")).performClick();assertTrue(q.peek().startsWith("XC:00"));
    }
}
