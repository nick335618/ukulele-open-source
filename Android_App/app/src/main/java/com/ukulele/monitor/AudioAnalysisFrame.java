package com.ukulele.monitor;

/** Strict, platform-independent parser for FPGA analysis protocol A1.
 * All fragments must belong to one sequence and arrive in exact offset order.
 * A complete snapshot is published only after its sum16 matches. */
public final class AudioAnalysisFrame {
    public final int[] bins = new int[2048];
    private final int[] pendingBins = new int[2048];
    public final float[] pcm = new float[256];
    public final float[] xy = new float[256];
    private final byte[] xyBytes=new byte[512];
    private final byte[] pcmBytes = new byte[512];
    private int phase, next, sum, sequence = -1, completedSequence = -1;
    private boolean active;
    public boolean scopeOnly, hasPcm=true;
    private final byte[] compactBytes=new byte[3086];
    private int compactNext,compactSequence=-1,compactTag;private boolean compactActive,compactScope;
    public double sampleRate = 48828.125;
    public long receivedAtMs;
    /** Independent completed image for UI; a new partial frame cannot mutate it. */
    public AudioAnalysisFrame snapshot(long receivedAt) {
        AudioAnalysisFrame copy=new AudioAnalysisFrame();
        System.arraycopy(bins,0,copy.bins,0,bins.length);
        System.arraycopy(pcm,0,copy.pcm,0,pcm.length);System.arraycopy(xy,0,copy.xy,0,xy.length);
        copy.scopeOnly=scopeOnly;copy.hasPcm=hasPcm;copy.peakBin=peakBin;copy.sampleRate=sampleRate;copy.completedSequence=completedSequence;
        copy.receivedAtMs=receivedAt;return copy;
    }
    public int peakBin;
    private int pendingPeakBin;
    public int sequence() { return completedSequence; }
    private static int u(byte b) { return b & 255; }
    private static int word(byte[] b, int i) { return u(b[i]) | (u(b[i + 1]) << 8); }
    public void reset() { compactActive=false;compactNext=0;compactSequence=-1;active = false; phase = next = sum = 0; sequence = completedSequence = -1; }
    private boolean reject() { compactActive=false;active = false; return false; }
    public boolean accept(byte[] b) {
        if(b!=null&&b.length==20&&(u(b[0])==0xA2||u(b[0])==0xA4))return acceptCompact(b);
        if (b == null || b.length != 20 || (u(b[0]) != 0xA1&&u(b[0])!=0xA3) || u(b[1]) > 4) return reject();
        int type = u(b[1]), seq = word(b, 2), offset = word(b, 4);
        if (type == 0) {
            if (seq == completedSequence || offset != 0 || word(b, 6) != (u(b[0])==0xA3?256:4096) ||
                    u(b[8]) != 0x80 || u(b[9]) != 0xF0 || u(b[10]) != 0xFA || u(b[11]) != 2 ||
                    word(b, 12) != 1024 || word(b, 14) > 2047 || u(b[16]) != 1 ||
                    u(b[17]) != 2 || u(b[18]) != 3 || u(b[19]) != 32) return reject();
            compactActive=false;scopeOnly=u(b[0])==0xA3;sequence = seq; pendingPeakBin = word(b, 14); phase = scopeOnly?2:1; next = sum = 0; active = true;
            return false;
        }
        if (!active || u(b[0])!=(scopeOnly?0xA3:0xA1) || seq != sequence || type != phase || offset != next) return reject();
        if (type == 3) {
            if (offset != 0 || word(b, 6) != sum) return reject();
            for (int i = 8; i < 20; i++) if (b[i] != 0) return reject();
            for (int i = 0; i < 256; i++) {pcm[i] = (short) word(pcmBytes, i * 2) / 32768f;xy[i]=(short)word(xyBytes,i*2)/32768f;}
            if(!scopeOnly){System.arraycopy(pendingBins,0,bins,0,2048);peakBin=pendingPeakBin;}
            hasPcm=true;active = false; completedSequence = sequence; return true;
        }
        int size = type == 1 ? 2048 : 512;
        for (int i = 0; i < 14; i++) {
            int value = u(b[6 + i]);
            if (offset + i < size) {
                if (type == 1 && value > 192) return reject();
                if (type == 1) pendingBins[offset + i] = value; else if(type==2)pcmBytes[offset + i] = b[6 + i];else xyBytes[offset+i]=b[6+i];
                sum = (sum + value) & 65535;
            } else if (value != 0) return reject();
        }
        next += 14;
        if (next >= size) { next = 0; phase=phase==1?2:(phase==2?4:3); }
        return false;
    }

    private static int crc16(byte[] data,int length){int c=65535;for(int i=0;i<length;i++){c^=u(data[i])<<8;for(int j=0;j<8;j++)c=((c&32768)!=0?(c<<1)^0x1021:c<<1)&65535;}return c;}
    private boolean acceptCompact(byte[] b){
        int tag=u(b[0]),type=u(b[1]),seq=word(b,2),off=word(b,4);
        if(type==0){byte[] header=b.clone();compactScope=tag==0xa4&&word(b,6)==256;
            header[0]=(byte)(compactScope?0xa3:0xa1);accept(header);
            if(!active)return reject();active=false;compactActive=true;compactTag=tag;compactNext=0;compactSequence=seq;return false;}
        if(!compactActive||seq!=compactSequence||tag!=compactTag)return reject();
        if(type==1){if(off!=compactNext||compactNext+14>compactBytes.length)return reject();System.arraycopy(b,6,compactBytes,compactNext,14);compactNext+=14;return false;}
        if(type!=3||off!=0)return reject();
        int size=word(b,6), min=tag==0xa2?1024:1, max=tag==0xa2?3072:2048;
        if(size<min||size>max||((size+13)/14)*14!=compactNext||crc16(compactBytes,size)!=word(b,8))return reject();
        for(int i=10;i<20;i++)if(b[i]!=0)return reject();for(int i=size;i<compactNext;i++)if(compactBytes[i]!=0)return reject();
        int cursor=0;
        if(!compactScope){
            int index=0;
            while(index<2048&&cursor<size){int value=u(compactBytes[cursor++]);if(value==255){if(cursor>=size)return reject();int run=u(compactBytes[cursor++]);if(run<3||index+run>2048)return reject();java.util.Arrays.fill(pendingBins,index,index+run,0);index+=run;}else {if(value>192)return reject();pendingBins[index++]=value;}}
            if(index!=2048)return reject();
        }
        int sampleBytes=tag==0xa2?1024:compactScope?512:0;
        if(size-cursor!=sampleBytes)return reject();
        hasPcm=sampleBytes>0;
        if(hasPcm)for(int i=0;i<256;i++)pcm[i]=(short)word(compactBytes,cursor+i*2)/32768f;
        if(tag==0xa2)for(int i=0;i<256;i++)xy[i]=(short)word(compactBytes,cursor+512+i*2)/32768f;
        if(!compactScope){System.arraycopy(pendingBins,0,bins,0,2048);peakBin=pendingPeakBin;}
        scopeOnly=compactScope;completedSequence=seq;compactActive=false;return true;
    }
    public double peakHz() { return bins[peakBin] == 0 ? 0 : peakBin * sampleRate / 4096; }
}
