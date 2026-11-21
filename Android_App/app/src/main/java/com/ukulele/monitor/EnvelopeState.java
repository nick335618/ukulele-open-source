package com.ukulele.monitor;

final class EnvelopeState {
    static final int FACTORY=235,FIRST=60,LAST=87;
    static final String[] ATTACK={"0.50×","0.67×","0.80×","1×","1.33×","2×","2.67×","4×"};
    static final String[] BODY={"0.5×","1×","1.5×","2×"};
    static final String[] FORCE={"25%","31%","38%","44%","50%","63%","75%","100%"};
    static final int[] SHIFTS={0,3,5},MASKS={7,3,7};
    private static final String[] NAMES={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};

    final boolean fresh;
    final int bank,midi,value,ticket,result,modifiedMask;

    private EnvelopeState(boolean fresh,int bank,int midi,int value,int ticket,int result,int mask){
        this.fresh=fresh;this.bank=bank;this.midi=midi;this.value=value;this.ticket=ticket;this.result=result;modifiedMask=mask;
    }

    static boolean playable(int midi){return midi>=FIRST&&midi<=LAST;}
    static int bit(int midi){return 1<<(midi-FIRST);}
    static String name(int midi){return NAMES[midi%12]+(midi/12-1);}

    static EnvelopeState parse(byte[] data){
        if(data==null||data.length!=14||(data[0]&255)!=0xb5||data[1]!=3)return null;
        int crc=0;
        for(byte b:data)crc^=b;
        int flags=data[2]&255,bank=data[3]&255,key=data[4]&255,value=data[5]&255,result=data[8]&255;
        int ticket=(data[6]&255)|((data[7]&255)<<8);
        int mask=(data[9]&255)|((data[10]&255)<<8)|((data[11]&255)<<16)|((data[12]&255)<<24);
        if(crc!=0||flags>1||bank>3||key>27||ticket>32767||(mask&0xf0000000)!=0||
                (result!=0&&result!=1&&result!=2&&result!=4&&result!=6))return null;
        if(flags==1&&((mask&(1<<key))!=0)==(value==FACTORY))return null;
        return new EnvelopeState(flags==1,bank,key+FIRST,value,ticket,result,mask);
    }
}
