package com.ukulele.monitor.ui;

/** Same integer PWM renderer as modal60/led_matrix.v. Visual rotation is separate. */
public final class LedEffectMath {
    public static final int FRAME_MS=20, EVENT_HOLD_MS=400;
    private LedEffectMath() {}
    public static int phaseStep(int speed){int s=Math.max(0,Math.min(255,speed));return s==0?0:256+(s<<1);}
    public static int triangle(int x){x&=255;return (x&128)==0?(x&127)<<1:((~x)&127)<<1;}
    /** Physical LED row is the upper boundary of fret row+1. */
    public static int guidePixel(int fret,int row,int level){
        return row>=0&&row<18&&(fret==0||(fret>=1&&fret<=18&&(row==fret-1||row==fret)))?level:0;
    }
    private static int multiply(int a,int b){return (a*b)>>8;}
    public static int pixel(int mode,int axis,int level,int phase,int col,int row,
                            int events,int flags,int guideCols,int guideRow,int guideLevel){
        int p=(phase>>>8)&255,w,d,head,travel;
        switch(mode){
            case 0:return 0;
            case 1:return level;
            case 2:return multiply(level,triangle(p));
            case 3:return multiply(level,triangle(p+(axis==1?(col<<5):(row<<3))));
            case 4:
                travel=axis==1?(phase>>>13)&7:(phase>>>10)&63;
                if(axis==1){head=travel>3?7-travel:travel;d=Math.abs(col-head);}
                else {head=travel>32?63-travel:travel;head=(head*17)>>5;d=Math.abs(row-head);}
                return d<=3?level>>d:0;
            case 5:
                w=triangle(p+(axis==1?(col<<5):(row<<3)));
                return w>190?level:w>120?level>>1:w>55?level>>3:0;
            case 6:
                int adc=col==1?2:col==2?1:col;
                return (events&(1<<adc))!=0?level:(flags&1)!=0?level>>4:0;
            case 7:return (guideCols&(1<<col))!=0?guidePixel(guideRow,row,guideLevel):0;
            default:return 0;
        }
    }
}
