package com.ukulele.monitor;

/** Separate 20-byte A2 power status; never reuses A0/A1 fields. */
final class PowerState {
    final int state,desired,result,origin,request,revision,countdown,caps,done,fault,epoch,reason,minutes;
    private PowerState(byte[] p) {
        state=p[2]&255;desired=p[3]&255;result=p[4]&255;origin=p[5]&255;
        request=word(p,6);revision=word(p,8);countdown=p[10]&255;caps=p[11]&255;
        done=word(p,12);fault=word(p,14);epoch=word(p,16);reason=p[18]&255;minutes=p[19]&255;
    }
    private static int word(byte[] p,int i){return (p[i]&255)|((p[i+1]&255)<<8);}
    static PowerState parse(byte[] p) {
        if(p==null || p.length!=20 || (p[0]&255)!=0xA2 || p[1]!=1 ||
           (p[2]&255)>6 || !((p[3]&255)==0 || (p[3]&255)==4) ||
           (p[4]&255)>6 || (p[5]&255)>3 || (p[10]&255)>10 ||
           ((p[11]&255)&~15)!=0 || (word(p,12)&~31)!=0 || (word(p,14)&~7)!=0 || (p[18]&255)>3)return null;
        int m=p[19]&255;if(m!=0 && m!=3 && m!=5 && m!=10 && m!=15 && m!=30)return null;
        return new PowerState(p);
    }
    boolean newerThan(PowerState old){
        if(old==null || epoch!=old.epoch)return true;
        int n=(revision-old.revision)&65535;return n>0 && n<32768;
    }
    boolean supported(){return (caps&3)==3;}
    boolean playable(){return !supported() || state<=2;}
    String label(){
        if(!supported())return "休眠功能等待匹配的 FPGA/ESP32 回读";
        if(result==5)return "FPGA离线 · 等待重新连接";
        switch(state){
            case 1:return "正常工作 · 屏幕已降低亮度";
            case 2:return countdown+" 秒后自动休眠";
            case 3:return "正在休眠 · 等待 FPGA 和屏幕确认";
            case 4:return "设备已休眠 · 屏幕已熄灭";
            case 5:return "正在唤醒 · 点亮屏幕并恢复传感器";
            case 6:return (fault&3)!=0?"屏幕已亮 · 光电/触摸未就绪":"恢复未完成 · 屏幕或 FPGA 尚未确认";
            default:return (fault&3)!=0?"系统运行 · 传感器未就绪":result==6?"正常工作 · 设备忙，未进入休眠":"屏幕已亮 · 正常工作";
        }
    }
}
