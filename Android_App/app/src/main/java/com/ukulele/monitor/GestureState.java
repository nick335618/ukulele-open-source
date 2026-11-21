package com.ukulele.monitor;

/** Dedicated status leaves the existing A0/v2 settings and MTU contract intact. */
final class GestureState {
    final int desired,actual,result,flags,ticket,revision,capabilities;
    private GestureState(byte[] d){desired=d[2]&255;actual=d[3]&255;result=d[4]&255;flags=d[5]&255;
        ticket=(d[6]&255)|((d[7]&255)<<8);revision=(d[8]&255)|((d[9]&255)<<8);capabilities=d[10]&255;}
    static GestureState parse(byte[] d){
        if(d==null || d.length!=13 || (d[0]&255)!=0xA4 || (d[1]!=1&&d[1]!=2) || d[11]!=0)return null;
        int supported=d[1]==2?7:3;
        int crc=0;for(int i=0;i<12;i++)crc^=d[i]&255;if(crc!=(d[12]&255))return null;
        if((d[2]&255)>supported || ((d[3]&255)>supported && (d[3]&255)!=255) || (d[4]&255)>6 || (d[5]&255)>15 ||
           ((d[10]&255)!=0 && (d[10]&255)!=supported))return null;
        if((d[5]&1)!=0 && ((d[3]&255)>supported || d[10]!=supported))return null;
        if((d[5]&2)!=0 && ((d[5]&1)==0 || d[2]!=d[3]))return null;
        return new GestureState(d);
    }
    boolean acceptsAfter(GestureState old){if(old==null)return true;int n=(revision-old.revision)&65535;return n<32768;}
    boolean fresh(){return (flags&1)!=0;}
    boolean waiting(){return (flags&8)!=0;}
    boolean selectableSource(){return capabilities==7;}
    String sourceName(){return selectableSource() && (actual&4)!=0 ? "辅助电容揉弦" : "原品格判定";}
    String status(){
        if(result==1)return "等待 FPGA 确认 · 当前："+actualText();
        if(result==4)return "回读超时，请更新配套 FPGA/ESP32 或检查连接";
        if(result==5 || !fresh())return "FPGA 状态尚未确认，开关暂不可用";
        if(result==3)return "设置失败，设备未接受该参数";
        if(result==6)return "已生效，但设备保存失败；重启可能恢复旧设置";
        return "实际生效："+actualText()+((flags&4)!=0?" · 已保存在设备":" · 尚未保存");
    }
    private String actualText(){return actual==255?"未知":("滑音"+((actual&1)!=0?"开":"关")+" / 颤音"+((actual&2)!=0?"开":"关"));}
}
