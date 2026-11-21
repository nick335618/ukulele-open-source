package com.ukulele.monitor;

import android.app.Activity;
import android.app.Dialog;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.ukulele.monitor.ui.*;
import java.util.*;

/** Five-round ear/rhythm play using the same consumed-audio-frame player as score audition. */
final class PracticeGamesDialog {
    private final Activity activity;private final boolean rhythm;private final PracticeJourneyStore store;
    private final UiKit kit;private final AppTheme.Colors colors;private final Dialog dialog;
    private final Handler handler=new Handler(Looper.getMainLooper());private final Random random=new Random();
    private final ScorePreviewPlayer player;private final Runnable refresh;
    private TextView headline,status,replay,next,tap;private final List<TextView> answers=new ArrayList<>();
    private int round,total;private boolean closed,heard,judged,acceptTaps;private int questionToken;
    private PracticeGameLogic.Ear ear;private long[] onsets;private final ArrayList<Long> taps=new ArrayList<>();
    private Runnable timeout;
    static PracticeGamesDialog open(Activity a,boolean rhythm,PracticeJourneyStore store,Runnable refresh){return new PracticeGamesDialog(a,rhythm,store,refresh);}
    private PracticeGamesDialog(Activity a,boolean rhythm,PracticeJourneyStore store,Runnable refresh){
        activity=a;this.rhythm=rhythm;this.store=store;this.refresh=refresh;kit=UiKit.create(a);colors=AppTheme.colors();
        dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        player=new ScorePreviewPlayer(a,new ScorePreviewPlayer.Listener(){
            public void state(String value){if(!closed)status.setText(value);}
            public void ended(){if(closed||judged)return;heard=true;acceptTaps=rhythm;status.setText(rhythm?"轮到你，按刚才的节奏点下面的大按钮":"选出这两个音的距离");setAnswers(true);}
        });
        LinearLayout body=kit.column();body.setPadding(kit.dp(18),kit.dp(18),kit.dp(18),kit.dp(18));body.setBackgroundColor(colors.bgRoot);
        body.addView(kit.textBold(rhythm?"节奏模仿":"听音挑战",24,colors.textPrimary));
        body.addView(kit.text(rhythm?"先听示范，再用手指复现节奏":"听两声，找出音程；共5题",13,colors.textSecondary));
        headline=kit.textBold("",18,colors.accent);headline.setPadding(0,kit.dp(16),0,kit.dp(10));body.addView(headline);
        status=kit.text("",14,colors.textSecondary);status.setMinHeight(kit.dp(65));status.setLineSpacing(kit.dp(3),1);body.addView(status);
        ScrollView scroller=new ScrollView(a);LinearLayout choices=kit.column();scroller.addView(choices);body.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        if(rhythm){tap=kit.action("跟着节奏点这里",colors.accent,colors.bgRoot);tap.setGravity(Gravity.CENTER);tap.setMinHeight(kit.dp(130));tap.setOnClickListener(v->tap());choices.addView(tap);}
        else for(int i=0;i<PracticeGameLogic.LABELS.length;i++){final int index=i;TextView b=kit.actionOutline(PracticeGameLogic.LABELS[i]);b.setOnClickListener(v->answer(index));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=kit.dp(10);choices.addView(b,lp);answers.add(b);}
        replay=kit.actionOutline("再听一次");replay.setOnClickListener(v->play());body.addView(replay);
        next=kit.action("下一题",colors.accent,colors.bgRoot);next.setOnClickListener(v->{if(round==5)dialog.dismiss();else newRound();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=kit.dp(10);body.addView(next,lp);
        TextView close=kit.actionOutline("结束小游戏");close.setOnClickListener(v->dialog.dismiss());body.addView(close,lp);
        dialog.setContentView(body);dialog.setOnDismissListener(d->{closed=true;handler.removeCallbacksAndMessages(null);player.close();if(refresh!=null)refresh.run();});dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);newRound();
    }
    private void newRound(){
        round++;questionToken++;heard=false;judged=false;acceptTaps=false;taps.clear();if(timeout!=null)handler.removeCallbacks(timeout);
        ear=new PracticeGameLogic.Ear(random);onsets=PracticeGameLogic.rhythm(random,round-1);
        headline.setText("第"+round+" / 5题 · "+(rhythm?"节奏累计 "+total:"答对 "+total+"题"));next.setEnabled(false);next.setAlpha(.45f);setAnswers(false);play();
    }
    private void play(){
        if(closed||judged)return;heard=false;acceptTaps=false;taps.clear();if(timeout!=null)handler.removeCallbacks(timeout);setAnswers(false);status.setText("听示范…");
        ArrayList<PreviewScore.Note> notes=new ArrayList<>();
        if(rhythm)for(long at:onsets)notes.add(new PreviewScore.Note(.35+at/1000d,.42+at/1000d,84,110));
        else{notes.add(new PreviewScore.Note(.15,.65,ear.base,105));notes.add(new PreviewScore.Note(.9,1.4,ear.target(),105));}
        PreviewScore score=new PreviewScore(notes);player.start(()->score,1,"小游戏示范");
    }
    private void answer(int index){if(!heard||judged||closed)return;boolean correct=index==ear.answer;if(correct)total++;status.setText((correct?"答对了！":"这次是 "+PracticeGameLogic.LABELS[ear.answer])+"\n前音 "+ScorePitch.name(ear.base)+" → 后音 "+ScorePitch.name(ear.target()));finishRound();}
    private void tap(){
        if(!acceptTaps||judged||closed)return;long now=SystemClock.elapsedRealtime();
        if(!taps.isEmpty()&&now-taps.get(taps.size()-1)<80)return;
        taps.add(now);tap.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);status.setText("已跟出 "+taps.size()+" / "+onsets.length+"次");
        if(taps.size()==1){final int token=questionToken;timeout=()->{if(!closed&&!judged&&token==questionToken)finishRhythm();};handler.postDelayed(timeout,onsets[onsets.length-1]*2+2500);}
        if(taps.size()>=onsets.length)finishRhythm();
    }
    private void finishRhythm(){if(judged)return;int value=PracticeGameLogic.rhythmScore(onsets,taps);total+=value;status.setText("这一轮节奏吻合 "+value+"分"+(taps.size()<onsets.length?" · 有漏拍":"")+"\n首拍不计等待时间，比较相邻拍击间隔");finishRound();}
    private void finishRound(){
        judged=true;acceptTaps=false;if(timeout!=null)handler.removeCallbacks(timeout);setAnswers(false);replay.setEnabled(false);next.setEnabled(true);next.setAlpha(1);
        if(round==5){int score=rhythm?Math.round(total/5f):total*20;store.gameBest(rhythm,score);headline.setText(PracticeJourneyStore.stars(score*100)+"  "+score+"分");next.setText("完成");if(refresh!=null)refresh.run();}
    }
    private void setAnswers(boolean enabled){for(TextView b:answers){b.setEnabled(enabled);b.setAlpha(enabled?1f:.45f);}if(tap!=null){tap.setEnabled(enabled);tap.setAlpha(enabled?1f:.45f);}replay.setEnabled(!judged);}
    void close(){if(!closed)dialog.dismiss();}
}
