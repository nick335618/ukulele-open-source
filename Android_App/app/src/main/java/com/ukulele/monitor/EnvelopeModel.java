package com.ukulele.monitor;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Approximate relative modal energy from the same factory tables used by the FPGA. */
final class EnvelopeModel {
    private static final double RATE=48828.125,Q24=16777216.0,Q32=4294967296.0;
    private static final float[] BODY_LEVEL={.5f,1f,1.5f,2f};
    private static final float[] EXCITATION={.25f,.3125f,.375f,.4375f,.5f,.625f,.75f,1f};
    private final int[] attack=new int[2048],drive=new int[128];
    private final double[] phase=new double[4096],radius=new double[4096],seed=new double[4096];

    EnvelopeModel(InputStream in) throws IOException {
        byte[] buffer=new byte[4+4096+512+4096*12];
        int offset=0;
        while(offset<buffer.length){
            int count=in.read(buffer,offset,buffer.length-offset);
            if(count<0)throw new IOException("包络模型数据不完整");
            offset+=count;
        }
        ByteBuffer b=ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt()!=0x564e4555)throw new IOException("包络模型格式不匹配");
        for(int i=0;i<attack.length;i++)attack[i]=b.getShort()&65535;
        for(int i=0;i<drive.length;i++)drive[i]=b.getInt();
        for(int i=0;i<phase.length;i++){
            phase[i]=(b.getInt()&0xffffffffL)/Q32;radius[i]=b.getInt()/Q24;seed[i]=b.getInt()/Q24;
        }
    }

    float[] preview(int midi,int model,int capo,int options,int ms){
        float[] values=calculate(midi,model,capo,options,ms);
        float[] reference=options==EnvelopeState.FACTORY?values:calculate(midi,model,capo,EnvelopeState.FACTORY,ms);
        float peak=0;
        for(float value:reference)peak=Math.max(peak,value);
        if(peak>0)for(int i=0;i<values.length;i++)values[i]/=peak;
        return values;
    }

    private float[] calculate(int midi,int model,int capo,int options,int ms){
        int note=Math.max(0,Math.min(model==1?32:27,midi-capo-(model==1?50:60)));
        int count=model>=2?4:60;
        int row=model>=2?3840+note*8+(model&1)*4:(model==1?1680:0)+note*60;
        int attackBase=model==1?1024:0;
        int driveIndex=(model==1?64:0)+note;
        double pitch=Math.pow(2,capo/12.0);
        double[] weights=new double[count];
        double reference=0;
        for(int k=0;k<count;k++){
            double w=phase[row+k]*pitch;
            if(w<=0||w>=.45)continue;
            double a=seed[row+k]/Math.sin(w*Math.PI*2);
            weights[k]=a*a;
            reference+=weights[k];
        }
        float[] values=new float[241];
        if(reference<=0)return values;
        double frameStep=ms*RATE/1000/(values.length-1);
        double[] energy=weights.clone(),loss=new double[count];
        for(int k=0;k<count;k++)loss[k]=Math.pow(radius[row+k],2*frameStep);
        double bodySend=model>=2?0:drive[driveIndex]/Q24*BODY_LEVEL[(options>>3)&3];
        double[] bodyEnergy=new double[8],bodyLoss=new double[8],bodyGain=new double[8];
        if(bodySend>0){
            for(int k=0;k<8;k++){
                int slot=3660+model*8+k;
                double w=phase[slot],rho=radius[slot];
                if(w<=0||rho<=0||rho>=1)continue;
                double impulse=bodySend*seed[slot]/Math.sin(w*Math.PI*2);
                bodyLoss[k]=Math.pow(rho,2*frameStep);
                bodyGain[k]=impulse*impulse*(1-bodyLoss[k])/(1-rho*rho);
            }
        }
        for(int i=0;i<values.length;i++){
            double sum=0;
            for(int k=0;k<count;k++){sum+=energy[k];energy[k]*=loss[k];}
            int age=(int)(i*frameStep);
            int scaled;
            switch(options&7){
                case 0:scaled=age*2;break;
                case 1:scaled=age+(age>>1);break;
                case 2:scaled=age+(age>>2);break;
                case 4:scaled=(age>>1)+(age>>2);break;
                case 5:scaled=age>>1;break;
                case 6:scaled=(age>>2)+(age>>3);break;
                case 7:scaled=age>>2;break;
                default:scaled=age;
            }
            int tableIndex=Math.min(1023,scaled*(model>=2?4:1));
            double direct=Math.sqrt(sum/reference)*attack[attackBase+tableIndex]/32768;
            double bodyPower=0;
            for(int k=0;k<8;k++){
                bodyEnergy[k]=bodyEnergy[k]*bodyLoss[k]+direct*direct*bodyGain[k];
                bodyPower+=bodyEnergy[k];
            }
            values[i]=(float)Math.sqrt(direct*direct+bodyPower)*EXCITATION[options>>5];
        }
        return values;
    }
}
