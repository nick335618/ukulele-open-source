package com.ukulele.monitor.ui;

/** Played-note flash; no synthetic notes and no row-dependent brightness. */
public final class StrumFlashState {
    private static final int[] OPEN={67,60,64,69}, ADC={0,2,1,3};
    private final int[] frets={31,31,31,31};
    private final long[] until=new long[4], reveal=new long[4];
    public void clear(){for(int c=0;c<4;c++){frets[c]=31;until[c]=reveal[c]=0;}}
    public void accept(int mask,int[] midi,long now){accept(mask,midi,now,0);}
    public void accept(int mask,int[] midi,long now,int capo){
        if(midi==null||midi.length!=4)return;
        for(int c=0;c<4;c++)if((mask&(1<<ADC[c]))!=0){
            int f=midi[ADC[c]]-OPEN[c];
            if(f<capo||f>18)continue;
            boolean repeat=until[c]>now;
            frets[c]=f==capo?0:f;reveal[c]=now+(repeat?40:0);until[c]=reveal[c]+LedEffectMath.EVENT_HOLD_MS;
        }
    }
    public int pixel(int col,int row,int level,long now){
        return col>=0&&col<4&&now>=reveal[col]&&now<until[col]?LedEffectMath.guidePixel(frets[col],row,level):0;
    }
    public int pixel(int col,int row,int level,long now,int capo){
        if(col<0||col>=4||now<reveal[col]||now>=until[col])return 0;
        if(frets[col]==0)return row>=capo?level:0;
        return LedEffectMath.guidePixel(frets[col],row,level);
    }
}
