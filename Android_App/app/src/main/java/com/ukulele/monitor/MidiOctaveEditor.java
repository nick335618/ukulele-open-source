package com.ukulele.monitor;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.ukulele.monitor.ui.AppDialogs;
import java.util.ArrayList;
import java.util.List;

final class MidiOctaveEditor {
    private static final int[] OFFSETS={-24,-12,0,12,24};

    static boolean supports(ScoreRepository.Item item){
        return item.category()==ScoreRepository.Category.MIDI || item.category()==ScoreRepository.Category.RECORDING;
    }

    static String label(int semitones){
        if(semitones==0)return "原调";
        return (semitones>0?"升高":"降低")+Math.abs(semitones/12)+" 个八度";
    }

    static void show(Activity activity,ScoreRepository repo,String id,Runnable saved){
        Handler ui=new Handler(Looper.getMainLooper());
        new Thread(()->{
            try{
                ScoreRepository.Item item=repo.get(id);
                if(item.deleted!=0)throw new IllegalArgumentException("曲谱已在回收站");
                PreviewScore original=PreviewScore.fromMidi(ScoreRepository.readAtomic(repo.source(id)));
                List<AppDialogs.Item> choices=new ArrayList<>();
                for(int offset:OFFSETS){
                    String detail;
                    try{
                        ScorePitch.Result result=MidiGuide.convert(original.shifted(offset),item.midiBalanced);
                        int playable=SongTimeline.parse(result.events).noteCount;
                        detail=playable+" 个可演奏音符 · "+result.warning();
                    }catch(IllegalArgumentException error){detail=error.getMessage();}
                    choices.add(new AppDialogs.Item(label(offset),detail,false,item.midiTranspose==offset?"当前":null));
                }
                ui.post(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    AppDialogs.list(activity,"MIDI 整体升降八度",choices,index->{
                        Toast.makeText(activity,"正在重新映射指法",Toast.LENGTH_SHORT).show();
                        new Thread(()->{
                            try{
                                repo.setMidiTranspose(id,OFFSETS[index]);
                                ui.post(()->{if(!activity.isFinishing()&&!activity.isDestroyed())saved.run();});
                            }catch(Exception error){report(activity,ui,error);}
                        },"midi-octave-save").start();
                    },"保留当前",(dialog,which)->saved.run(),"指法分配",(dialog,which)->chooseFingering(activity,repo,id,saved));
                });
            }catch(Exception error){report(activity,ui,error);}
        },"midi-octave-preview").start();
    }

    private static void chooseFingering(Activity activity,ScoreRepository repo,String id,Runnable saved){
        List<AppDialogs.Item> options=new ArrayList<>();
        options.add(new AppDialogs.Item("低把位指法","优先较低品位，适合初学",false));
        options.add(new AppDialogs.Item("换弦练习","在可演奏弦位中分散使用琴弦，连续同音保留同一指法",false));
        Handler ui=new Handler(Looper.getMainLooper());
        AppDialogs.list(activity,"MIDI 指法分配",options,index->{
            new Thread(()->{
                try{
                    repo.setMidiFingering(id,index==1);
                    ui.post(()->{if(!activity.isFinishing()&&!activity.isDestroyed())saved.run();});
                }catch(Exception error){report(activity,ui,error);}
            },"midi-fingering-save").start();
        },"返回",(dialog,which)->show(activity,repo,id,saved),null,null);
    }

    private static void report(Activity activity,Handler ui,Exception error){
        ui.post(()->{
            if(!activity.isFinishing()&&!activity.isDestroyed())
                Toast.makeText(activity,error.getMessage()==null?"MIDI 移调失败":error.getMessage(),Toast.LENGTH_LONG).show();
        });
    }
}
