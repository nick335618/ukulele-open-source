package com.ukulele.monitor;
import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;

/** Phone-only audition. Audio output is the guide clock; no BLE or FPGA note commands. */
final class ScorePreviewPlayer {
    interface Listener {
        void state(String value);
        default void ready(long durationMs){}
        default void progress(long positionMs,long durationMs,boolean paused,double speed){}
        default void ended(){}
    }
    interface Source {PreviewScore load()throws Exception;}
    private static final class Session {
        volatile AudioTrack track;volatile boolean paused;volatile double speed;
        PreviewPlaybackClock clock;long duration;volatile long position;
        Session(double rate){speed=rate;}
    }
    private final AudioManager manager;private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Listener listener;private AudioFocusRequest focus;private boolean closed;
    private volatile Session current;
    /** 应用内试听音量 0..2（1.0 = 合成器默认响度，>1 在软限幅前继续增益）；由滑杆实时写入。 */
    private volatile float volume=1f;
    private int transpose=0;void setTranspose(int semitones){transpose=Math.max(0,Math.min(12,semitones));}
    ScorePreviewPlayer(Context c,Listener l){manager=(AudioManager)c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);listener=l;}
    private void report(Session s,String value){ui.post(()->{if(current==s&&!closed)listener.state(value);});}
    boolean isActive(){return current!=null;}
    boolean isPaused(){Session s=current;return s!=null&&s.paused;}
    long positionMs(){Session s=current;return s==null?0:s.position;}
    /**
     * 应用内音量（0..2）。0..1 衰减 AudioTrack；1..2 交给合成器在软限幅前加增益
     * （峰值仍受软限幅约束，不会硬削波）。播放中调整立即生效，下一次 start 也继续沿用。
     */
    void setVolume(float value){
        if(!Float.isFinite(value))return;
        float v=Math.max(0f,Math.min(2f,value));
        volume=v;
        Session s=current;AudioTrack t=s==null?null:s.track;
        if(t!=null)try{t.setVolume(Math.min(1f,v));}catch(Throwable ignored){}
    }
    /** 合成器侧增益：只在超过 1.0 时启用，避免把正常音量重复放大。 */
    private float pcmGain(){return Math.max(1f,Math.min(2f,volume));}
    void setSpeed(double rate){if(!Double.isFinite(rate)||rate<.25||rate>2)return;Session s=current;if(s!=null)s.speed=rate;}
    void togglePause(){
        Session s=current;if(s==null)return;s.paused=!s.paused;
        AudioTrack t=s.track;if(t!=null)try{if(s.paused)t.pause();else t.play();}catch(IllegalStateException ignored){}
        report(s,s.paused?"试听已暂停":"正在试听");
    }
    private void progress(Session s){
        if(current!=s||closed)return;
        AudioTrack t=s.track;
        if(t!=null)try{s.position=(long)s.clock.consumed(t.getPlaybackHeadPosition());listener.progress(s.position,s.duration,s.paused,s.clock.speed());}catch(IllegalStateException ignored){}
        ui.postDelayed(()->progress(s),32);
    }
    void start(Source source,double speed,String name){
        stop();if(closed)return;final Session session=new Session(speed);final int shift=transpose;current=session;
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change<0&&current==session)stop();},ui).build();
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){finish(session,"暂时无法播放，请稍后再试");return;}
        report(session,"准备试听 · "+name);
        worker.execute(()->{AudioTrack track=null;try{
            PreviewScore score=source.load();if(current!=session)return;
            if(shift!=0){java.util.List<PreviewScore.Note> shifted=new java.util.ArrayList<>();for(PreviewScore.Note n:score.notes)shifted.add(new PreviewScore.Note(n.start,n.end,Math.min(127,n.pitch+shift),n.velocity));score=new PreviewScore(shifted,score.musicalDuration);}PreviewPcm pcm=new PreviewPcm(score,session.speed);pcm.setOutputGain(pcmGain());
            session.duration=(long)Math.ceil(score.duration*1000);session.clock=new PreviewPlaybackClock(score.duration*1000,session.speed);
            int minimum=AudioTrack.getMinBufferSize(PreviewPcm.RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(minimum<0)throw new IllegalStateException("手机音频格式不可用");
            track=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(new AudioFormat.Builder().setSampleRate(PreviewPcm.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(2048,minimum)).build();
            if(current!=session)return;session.track=track;
            // 再读一次 volatile volume：主线程可能在 track 创建窗口内改过音量，避免这次改动丢失。
            track.setVolume(Math.min(1f,volume));
            if(!session.paused)track.play();
            ui.post(()->{if(current==session&&!closed){listener.ready(session.duration);progress(session);}});
            report(session,"正在试听 · "+name);short[] block=new short[256];
            while(current==session&&!pcm.done()){
                while(current==session&&session.paused)Thread.sleep(10);if(current!=session)break;
                if(pcm.speed()!=session.speed){pcm.setSpeed(session.speed);session.clock.segment(pcm.frames(),pcm.positionMs(),pcm.speed());}
                // 每块都同步一次合成器增益（volatile 读，很便宜）：100% 以上滑杆实时生效。
                pcm.setOutputGain(pcmGain());
                int n=pcm.read(block),off=0;
                while(off<n&&current==session){int k=track.write(block,off,n-off,AudioTrack.WRITE_BLOCKING);if(k<=0){if(current==session)throw new IllegalStateException("手机音频输出中断");break;}off+=k;}
            }
            long deadline=System.nanoTime()+2000000000L;
            while(current==session&&(track.getPlaybackHeadPosition()&0xffffffffL)<pcm.frames()){
                if(session.paused)deadline=System.nanoTime()+2000000000L;
                else if(System.nanoTime()>deadline)throw new IllegalStateException("音频输出超时");Thread.sleep(10);
            }
            if(current==session)ui.post(()->finish(session,"试听结束"));
        }catch(Exception e){if(current==session)ui.post(()->finish(session,"无法试听："+(e.getMessage()==null?"音频输出异常":e.getMessage())));}
        finally{session.track=null;if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}track.release();}}});
    }
    private void finish(Session s,String message){if(current!=s||closed)return;if("试听结束".equals(message))listener.progress(s.duration,s.duration,false,s.speed);current=null;releaseFocus();listener.state(message);listener.ended();}
    private void releaseFocus(){if(focus!=null){manager.abandonAudioFocusRequest(focus);focus=null;}}
    void stop(){
        Session s=current;current=null;
        if(s!=null){AudioTrack t=s.track;if(t!=null)try{t.pause();t.flush();t.stop();}catch(IllegalStateException ignored){}}
        releaseFocus();if(!closed){listener.state("试听已停止");if(s!=null)listener.ended();}
    }
    void close(){stop();closed=true;worker.shutdownNow();}
}
