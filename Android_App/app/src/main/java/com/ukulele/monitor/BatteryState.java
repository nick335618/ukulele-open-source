package com.ukulele.monitor;

import java.util.Locale;

final class BatteryState {
    final int millivolts, percent, charge, infrared;

    private BatteryState(int mv, int percent, int charge, int infrared) {
        millivolts=mv;this.percent=percent;this.charge=charge;this.infrared=infrared;
    }

    static BatteryState parse(byte[] packet) {
        if(packet==null || packet.length!=8 || (packet[0]&255)!=0xb4 || packet[1]!=1)return null;
        int check=0;for(byte b:packet)check^=b&255;
        int mv=(packet[2]&255)|((packet[3]&255)<<8), percent=packet[4]&255;
        int charge=packet[5]&255, infrared=packet[6]&255;
        if(check!=0 || charge>4 || infrared>3 ||
            (mv==0 ? percent!=255 : mv<5000 || mv>8700 || percent>100))return null;
        return new BatteryState(mv,percent,charge,infrared);
    }

    boolean hasLevel() {return millivolts!=0;}
    int bars() {return !hasLevel()?-1:percent==0?0:Math.min(4,(percent+24)/25);}
    String levelLabel() {return !hasLevel()?"电量未读到":bars()==0?"电量不足":bars()+" 格电量";}
    boolean isCharging() {return charge==2||charge==3;}

    String chargeLabel() {
        switch(charge){
            case 1:return "未充电";
            case 2:return "正在充电";
            case 3:return "正在涓流充电";
            case 4:return "充电指示异常";
            default:return "充电状态未知";
        }
    }

    String label() {
        String voltage=hasLevel()?String.format(Locale.ROOT,"%s · %.2f V",levelLabel(),millivolts/1000d):"电量未读到";
        return voltage+" · "+chargeLabel();
    }
}
