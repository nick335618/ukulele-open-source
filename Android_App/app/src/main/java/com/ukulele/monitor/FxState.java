package com.ukulele.monitor;

/** Dedicated, versioned 16-byte notification; fits the default BLE MTU. */
final class FxState {
    final int desired,strength,actual,gain,flags,result,ticket,revision,coreFlags,caps;
    private FxState(byte[] d){desired=d[2]&255;strength=d[3]&255;actual=d[4]&255;gain=d[5]&255;
        flags=d[6]&255;result=d[7]&255;ticket=(d[8]&255)|((d[9]&255)<<8);
        revision=(d[10]&255)|((d[11]&255)<<8);coreFlags=d[12]&255;caps=d[13]&255;}
    static FxState parse(byte[] d){
        if(d==null||d.length!=16||(d[0]&255)!=0xA6||d[1]!=3||d[14]!=0)return null;
        int crc=0;for(int i=0;i<15;i++)crc^=d[i]&255;if(crc!=(d[15]&255))return null;
        int max=4;
        if((d[2]&255)>max||(d[3]&255)>maxAmount(d[2]&255)||((d[4]&255)>max&&(d[4]&255)!=255)||(d[5]&255)>maxAmount(d[4]&255)||
           (d[6]&255)>15||(d[7]&255)>6||(d[12]&255)>15||((d[13]&255)!=0&&d[13]!=3&&d[13]!=15))return null;
        if((d[6]&1)!=0&&((d[4]&255)>max||(d[13]!=3&&d[13]!=15)||!supported(d[4]&255,d[13]&255)))return null;
        if((d[6]&2)!=0&&((d[6]&1)==0||d[2]!=d[4]||(d[12]&12)!=4))return null;
        if((d[6]&2)!=0&&!supported(d[2]&255,d[13]&255))return null;
        return new FxState(d);
    }
    boolean acceptsAfter(FxState old){return old==null||((revision-old.revision)&65535)<32768;}
    boolean fresh(){return (flags&1)!=0;}
    boolean waiting(){return (flags&8)!=0;}
    static boolean supported(int mode,int caps){return mode==0||mode>=1&&mode<=4&&(caps&(1<<(mode-1)))!=0;}
    static int maxAmount(int mode){return mode==2||mode==3?96:127;}
    static String name(int mode){return mode==0?"原声":mode==1?"震音":mode==2?"合唱":mode==3?"弗兰格":mode==4?"电吉他失真":"未知";}
    String status(){
        if(fresh()&&!supported(desired,caps))return "所存效果需要新版 FPGA 固件，可先选择原声";
        if(result==1)return "正在切换 · 等待 FPGA 确认";
        if(result==4)return "确认超时，请检查配套 FPGA 与 ESP32 固件";
        if(!fresh()||result==5)return "FPGA 离线或状态过期";
        if((coreFlags&8)!=0)return "音频处理超时，请关闭效果并检查固件";
        if(result==3)return "设置失败，参数未接受";
        if(result==6)return "已生效："+name(actual)+" · 保存失败";
        return "实际生效："+name(actual)+((flags&4)!=0?" · 已保存":"")+((coreFlags&4)==0?" · 正在渐变":"");
    }
}
