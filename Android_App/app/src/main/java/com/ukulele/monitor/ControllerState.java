package com.ukulele.monitor;

/** ESP32 accepted settings; LED fields do not certify physical LED output. */
final class ControllerState {
    final int volume, timbre, ledMode, ledAxis, speed, brightness, ledFlags;
    final int flags, revision, appliedGain, appliedTimbre, optoPreset;
    final int version, desiredMode, actualMode, operation, operationResult, operationTicket, diagnosticMask;

    private ControllerState(byte[] d) {
        volume = d[2] & 255; timbre = d[3] & 255;
        ledMode = d[4] & 255; ledAxis = d[5] & 255;
        speed = d[6] & 255; brightness = d[7] & 255; ledFlags = d[8] & 255;
        flags = d[9] & 255; revision = (d[10] & 255) | ((d[11] & 255) << 8);
        appliedGain = d[12] & 255; appliedTimbre = d[13] & 255;
        optoPreset = d[14] & 255;
        version=d[1]&255;
        desiredMode=version==2 ? d[15]&255 : 255;
        actualMode=version==2 ? d[16]&255 : 255;
        operation=version==2 ? (d[17]&255)>>>4 : 0;
        operationResult=version==2 ? d[17]&15 : 0;
        operationTicket=version==2 ? d[18]&255 : 0;
        diagnosticMask=version==2 ? d[19]&255 : 0;
    }

    static ControllerState parse(byte[] d) {
        if(d==null || d.length<2 || (d[0]&255)!=0xA0)return null;
        final boolean legacy=d[1]==1 && d.length==16;
        final boolean modern=d[1]==2 && d.length==20;
        if(!legacy && !modern)return null;
        if ((d[2] & 255) > 100 || (d[3] & 255) > 1 || ((d[4] & 255) > 6 && !(modern && (d[4]&255)==8)) ||
            (d[5] & 255) > 1 || (d[9] & 255) > (modern?31:7)) return null;
        if(legacy && d[15]!=0)return null;
        if(modern && ((d[15]&255)>1 || ((d[16]&255)>1 && (d[16]&255)!=255) ||
            ((d[17]&255)>>>4)>9 || (d[17]&15)>6))return null;
        if ((d[13] & 255) > 1 && (d[13] & 255) != 255) return null;
        if ((d[14] & 255) > 3 && (d[14] & 255) != 255) return null;
        return new ControllerState(d);
    }

    boolean isNewerThan(ControllerState old) {
        if (old == null) return true;
        if(version!=old.version)return version>old.version;
        int distance = (revision - old.revision) & 65535;
        return distance > 0 && distance < 32768;
    }

    boolean fpgaOnline() { return (flags & 1) != 0; }
    boolean songPlaying() { return (flags & 2) != 0; }
    boolean audioSettingsApplied() { return (flags & 4) != 0; }
    boolean playModeConfirmed(){return version==2 && fpgaOnline() && actualMode<=1 && desiredMode==actualMode && (flags&8)!=0;}
    String modeStatus(){
        if(version<2)return "需要新版 FPGA/ESP32 固件确认演奏模式";
        if(!fpgaOnline())return "FPGA 离线，尚未确认演奏模式";
        if(actualMode>1)return "等待 FPGA 实际模式回读";
        String name=actualMode==0?"一拍锁定":"四弦复音";
        if(operation==7 && operationResult>=3){
            String[] errors={"失败","超时","离线","设备忙"};
            return "实际："+name+" · 切换"+errors[operationResult-3];
        }
        if(desiredMode!=actualMode || operationResult==1)return "实际："+name+" · 等待确认";
        return "实际生效："+name;
    }
    String operationStatus(){
        String[] names={"设备设置","回读","预设应用","保存","载入","临时默认","保存默认","演奏模式","参数修改","重新同步"};
        String name=names[Math.min(operation,9)];
        switch(operationResult){
            case 1:return name+"：等待 FPGA 确认";
            case 2:return operation==5?"已临时应用默认，尚未保存":name+"：成功";
            case 3:return name+"：失败（协议、参数或存储错误）";
            case 4:return name+"：超时，未确认完成";
            case 5:return name+"：FPGA 离线";
            case 6:return name+"：设备繁忙，请稍后重试";
            default:return "等待操作结果";
        }
    }
}
