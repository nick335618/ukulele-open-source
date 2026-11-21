package com.ukulele.monitor;
import java.util.*;

/**
 * 「试听乐谱」离线合成器：把 {@link PreviewScore} 渲染成 48kHz 单声道 16bit PCM。
 *
 * <p>本版把内部合成从「正弦 + tanh」换成了尤克里里拨弦音色（Karplus-Strong 弦模型）：
 * 每个音符一条延迟线，起始填入噪声作为拨片激励，之后信号在延迟线内循环；环路里的
 * 一阶低通代表尼龙弦的高频损耗，环路增益按 T60 反推，所以低音余音长、高音余音短
 * （约 0.8~3.2 秒），听感是「拨弦」而不是「电子琴」。
 *
 * <p>为什么用分数延迟：延迟线长度只能取整数，直接取整会让高把位音准偏移十几音分。
 * 这里整数延迟走延迟线指针、小数部分用一阶全通滤波器补足（幅度恒为 1），再把全通与环路低通
 * 在基频处的相位延迟一起补偿掉，因此实际音高误差远小于 5 音分。不用线性插值的原因是：
 * 线性插值的幅度响应会随频率下降，高音每绕一圈都额外变小声，4186Hz 的音几十毫秒就衰减没了。
 *
 * <p>为什么 note-off 要单独做短释放：乐谱 Note.end 到了就删 voice 会造成波形硬切（爆音）。
 * 这里用约 60ms 的 smoothstep 淡出，既在 40~80ms 的快速阻尼区间内、不拖节奏，又保证
 * 相邻样本连续。末尾再对整条总线做 20ms 淡出，保证播放头停在 duration 处时没有跳变。
 *
 * <p>混音后的「木质感」链路：4.8ms 短反馈梳状滤波器（木箱驻波）+ 低频暖化 + 二阶 6kHz 低通，
 * 反馈很小（约 18ms 衰减 60dB），只加琴体染色、不加可闻混响尾巴，避免节奏浑浊。
 *
 * <p>响度：拨弦音色衰减快、峰值因数高，同峰值下听感比旧的正弦实现轻不少。因此在软限幅前加了几级：
 * 先 2.8 倍预增益，再做静态功率压缩 y=sign(x)*|x|^0.75——把低电平的衰减尾段抬起来、把峰值相对压住，
 * 相当于压缩动态范围，RMS 明显提高而峰值不涨。静态曲线不跟随包络，所以没有压缩器常见的“抽吸”感，
 * 也保持完全确定性。压缩后再过一级二阶 ~5kHz 低通，磨掉非线性产生的高频、避免刺耳并控制采样级跳变；
 * 最后用 1.7 倍默认归一化和平滑软限幅 x/sqrt(1+x²) 收口——输出严格小于满量程、不会出现削波平台。
 *
 * <p>输出增益：{@link #setOutputGain(float)} 是加性 API（0~2，默认 1.0 即本类的“新默认响度”），
 * UI 音量滑杆 0~200% 可映射到 0~2 再做二次放大；该增益在软限幅前乘上，非法值忽略，字段 volatile 保证线程可见。
 *
 * <p>对外语义与旧实现完全一致：setSpeed 只改变乐谱时间推进速度、不改变音高；
 * positionMs()=乐谱时间*1000；frames() 是已输出帧数；done() 在乐谱时间到达 duration 后为 true。
 * 噪声来自固定种子的 xorshift，因此同一份乐谱每次渲染结果完全一致，便于离线测试。
 * 本类不引用任何 Android API、不每样本分配对象，可在普通 JVM 上直接跑。
 */
final class PreviewPcm {
    static final int RATE=48000;

    // ---------------------------------------------------------------- 合成参数
    private static final int MAX_VOICES=32;                 // 同时发声上限，超出时抢占最旧的 voice
    private static final int ATTACK_FRAMES=RATE*5/1000;     // 5ms 起音斜坡：保留拨弦感，又把起音处相邻样本跳变压在无爆音范围
    private static final int RELEASE_FRAMES=RATE*60/1000;   // 60ms note-off 释放（需求 40~80ms 区间）
    private static final double EXC_PEAK=1.50;              // 单音激励峰值，多音叠加后由软限幅收口
    private static final double LOOP_LOSS_IN_FILTER=0.12;   // T60 损耗里分给环路低通的比例，其余由环路增益承担
    private static final double MIN_FREQ=27.5,MAX_FREQ=4186.0; // A0~C8（MIDI 21~108）：覆盖常见乐谱音域；夹紧只为限制延迟线长度
    private static final int COMB_FRAMES=RATE*48/10000;     // 4.8ms 琴体梳状延迟（需求 4~6ms）
    private static final float COMB_FEEDBACK=0.16f;         // 反馈很小：约 60dB/18ms 衰减，只留木箱感
    private static final float COMB_MIX=0.25f;
    private static final float WARM_K=0.0233f;              // ~180Hz 一阶低通，补木箱低频暖度
    private static final float WARM_MIX=0.28f;
    private static final float AIR_K=0.5441f;               // 二阶 6kHz 低通（每级 1-exp(-2π*6000/48000)）
    private static final float DC_R=0.999f;                 // 一阶隔直（高通约 7.6Hz）：挡住直流又不吃掉低音
    private static final float DRIVE=2.8f;                  // 进入响度级之前的预增益：先把拨弦信号抬到压缩曲线的有效区间
    private static final float LOUD_GAIN=1.7f;              // 功率压缩后的默认输出归一化：决定最终默认响度（峰值由软限幅兜底）
    private static final float POST_K=0.48f;                // 压缩后置二阶低通（每级≈5kHz）：磨掉非线性产生的高频，控制刺耳与采样级跳变
    private static final int SCALE=30000;                   // 软限幅后 |y|<1，30000 留足峰值余量（峰值恒 <32000）

    private final PreviewScore score;
    private final ArrayList<Voice> voices=new ArrayList<>();
    private final float[] comb=new float[COMB_FRAMES];      // 琴体短梳状滤波器（复用，不每帧分配）
    private int combIndex;
    private float warm,air,air2,post,post2,dcIn,dcOut;
    private long noiseState=0x2545F4914F6CDD1DL;            // 固定种子：确定性渲染
    private double speed,time;
    private long frame;
    private int cursor;
    private volatile float outputGain=1f;                   // UI 输出增益（0~2，默认 1）；read() 每块读一次，见 setOutputGain

    PreviewPcm(PreviewScore s,double rate){score=s;setSpeed(rate);}
    void setSpeed(double rate){if(!Double.isFinite(rate)||rate<.25||rate>2)throw new IllegalArgumentException("试听倍速超出范围");speed=rate;}
    double speed(){return speed;}
    double positionMs(){return time*1000;}
    long frames(){return frame;}
    boolean done(){return time>=score.duration;}

    /**
     * 加性 API：设置输出增益（建议 0~2，默认 1.0 就是本类的默认响度）。
     * UI 音量滑杆 0~200% 可映射到 0~2；倍率在软限幅前乘上，所以 1 以上会自然进入软饱和、不会削波。
     * 非法值（NaN/Inf/负数/>2）直接忽略并保留上一次有效值；字段 volatile，主线程设置、音频线程读取安全。
     */
    void setOutputGain(float gain){
        if(Float.isFinite(gain)&&gain>=0f&&gain<=2f)outputGain=gain;
    }

    /** 渲染最多 out.length 帧；到达乐谱末尾后返回 0。尾部不足一帧的音符由释放包络自然收掉。 */
    int read(short[] out){
        int written=0;
        float volume=outputGain; // 每个块读取一次，避免每样本读 volatile；块长 256 帧约 5ms，滑杆拖动足够跟手
        for(;written<out.length&&!done();written++,frame++,time+=speed/RATE){
            // 1) 起音：同一帧内可能有多个音符（和弦），按乐谱时间依次唤醒
            while(cursor<score.notes.size()&&score.notes.get(cursor).start<=time){
                PreviewScore.Note n=score.notes.get(cursor++);
                if(n.end>time){
                    if(voices.size()>=MAX_VOICES)voices.remove(0); // 抢占最旧 voice
                    voices.add(new Voice(n));
                }
            }
            // 2) 混音：Note.end 到点的 voice 先进入短释放再取样本
            float sum=0f;
            for(int i=voices.size()-1;i>=0;i--){
                Voice v=voices.get(i);
                if(!v.released&&time>=v.note.end)v.released=true;
                sum+=v.tick();
                if(v.finished)voices.remove(i);
            }
            // 3) 琴体共鸣 -> 低频暖化 -> 二阶 6kHz 低通 -> 隔直 -> 预增益 -> 功率压缩 -> 默认归一化 -> UI 增益
            float mixed=loudCurve(bodyChain(sum)*DRIVE)*LOUD_GAIN*volume;
            // 3.5) 压缩后的后置低通（二阶）：非线性会重新产生高频，先磨掉再淡出/软限幅，避免刺耳并压低采样级跳变
            post+=(mixed-post)*POST_K;
            post2+=(post-post2)*POST_K;
            mixed=post2;
            // 4) 末尾 20ms（按真实播放时间折算）整体淡出，避免播放头停在 duration 处产生硬切
            double remaining=score.duration-time,fadeSpan=0.02*speed;
            if(remaining<fadeSpan&&fadeSpan>0){
                float fade=(float)(remaining/fadeSpan);
                if(fade<0f)fade=0f;
                mixed*=fade;
            }
            out[written]=(short)Math.round(softClip(mixed)*SCALE);
        }
        return written;
    }

    /** 总线染色：短梳状滤波（木箱驻波）-> 低频暖化 -> 二阶 6kHz 低通 -> 隔直。每样本常数次运算。 */
    private float bodyChain(float x){
        float delayed=comb[combIndex];
        comb[combIndex]=x+delayed*COMB_FEEDBACK;
        if(++combIndex==COMB_FRAMES)combIndex=0;
        float y=x+delayed*COMB_MIX;          // 反馈梳状：4.8ms 处形成一串很窄的琴体共振
        warm+=(y-warm)*WARM_K;               // 一阶低通 ~180Hz，取它的低频部分当木箱暖度
        y+=warm*WARM_MIX;
        air+=(y-air)*AIR_K;                  // 二阶 6kHz 低通：磨掉数字噪声的“沙沙”感，同时压低起音处的采样级跳变
        air2+=(air-air2)*AIR_K;
        y=air2;
        float dc=y-dcIn+DC_R*dcOut;          // 一阶隔直：软限幅前必须没有直流，否则会被压成偏移
        dcIn=y;dcOut=dc;
        return dc;
    }

    /**
     * 平滑软限幅：y=x/sqrt(1+x²)，严格单调、|y|<1 且永不出现平台。
     * 比 tanh 更适合这里的理由：饱和曲线连续可导，密集和弦起音时只是变“圆”，不会产生硬削波谐波，
     * 也不存在「连续若干样本贴着满量程」的尖峰平台。
     */
    private static float softClip(float x){
        return x/(float)Math.sqrt(1.0+x*x);
    }

    /**
     * 静态功率压缩（响度级核心）：y=sign(x)*|x|^0.75。
     *
     * <p>为什么需要：拨弦余音衰减快、峰值因数高，同样峰值下 RMS 会比旧的正弦实现低 9dB 左右，
     * 手机外放听感明显偏轻。功率曲线把 |x|<1 的衰减尾段按幂函数抬高、把 |x|>1 的峰值相对压低，
     * 相当于压缩动态范围：RMS 上来、峰值不涨，且是静态映射——不跟随包络，所以没有压缩器的“抽吸”感，
     * 也不引入状态，天然保持确定性。
     *
     * <p>实现：|x|^0.75 = sqrt(sqrt(|x|³))，两次 sqrt 比每样本 Math.pow 便宜很多；指数取 0.75
     * （建议区间 0.6~0.75 中压缩最轻的一档），在明显提升响度的同时尽量保留拨弦的起音与衰减形状。
     */
    private static float loudCurve(float x){
        float a=x<0?-x:x;
        float y=(float)Math.sqrt(Math.sqrt(a*a*a));
        return x<0?-y:y;
    }

    /** xorshift64 噪声：固定种子 + 整数运算，确定性且不分配对象。 */
    private float nextNoise(){
        long x=noiseState;
        x^=x<<13;x^=x>>>7;x^=x<<17;
        noiseState=x;
        return (x>>>40)*(1f/8388608f)-1f;    // 取高 24 bit，映射到 [-1,1)
    }

    /**
     * 求一阶低通系数 a，使其在 omega 处的幅度恰好等于 mag。
     * 幅度 |H|=(1-a)/sqrt(1-2a·cosω+a²) 随 a 单调下降，二分 24 次足够（每个 note 开始时算一次，不在采样循环里）。
     */
    private static double lowpassFor(double omega,double mag){
        double c=Math.cos(omega),lo=0,hi=0.96;
        for(int i=0;i<24;i++){
            double mid=(lo+hi)*0.5;
            double m=(1-mid)/Math.sqrt(1-2*mid*c+mid*mid);
            if(m>mag)lo=mid;else hi=mid;
        }
        return (lo+hi)*0.5;
    }

    /**
     * 求一阶全通滤波器系数 η，使它在 omega 处的相位延迟等于 target（0~1 个样本）。
     * 一阶全通 H(z)=(η+z⁻¹)/(1+η z⁻¹) 幅度恒为 1，相位延迟随 η 从 1 单调降到 0，二分即可。
     * 用它做分数延迟的好处是不会像线性插值那样额外低通，高音不会因为插值而变短。
     */
    private static double allpassFor(double omega,double target){
        double lo=0,hi=1;
        for(int i=0;i<30;i++){
            double mid=(lo+hi)*0.5;
            if(allpassDelay(mid,omega)>target)lo=mid;else hi=mid;
        }
        return (lo+hi)*0.5;
    }

    /** 一阶全通滤波器在 omega 处的相位延迟（样本数）。 */
    private static double allpassDelay(double eta,double omega){
        double s=Math.sin(omega),c=Math.cos(omega);
        double argNum=Math.atan2(-s,eta+c);
        double argDen=Math.atan2(-eta*s,1.0+eta*c);
        return -(argNum-argDen)/omega;
    }

    /** 一个拨弦声部：延迟线 + 环路低通 + 每圈衰减；note-off 后叠加 smoothstep 短释放。 */
    private final class Voice {
        final PreviewScore.Note note;
        final float[] line;final int size;
        final int dint;                  // 延迟线整数延迟（样本）
        final float eta;                 // 一阶全通系数：补足小数延迟，幅度恒为 1
        final float damp,loopGain;       // 环路低通系数 / 每圈增益
        int write;float apx1,apy1;float lp;int age;
        boolean released,finished;int releaseAge;

        Voice(PreviewScore.Note x){
            note=x;
            // 1) 音高与 T60：MIDI 音高 -> 频率；越界夹紧保证延迟线长度合理
            double f=440.0*Math.pow(2.0,(Math.max(0,Math.min(127,x.pitch))-69)/12.0);
            if(f<MIN_FREQ)f=MIN_FREQ;else if(f>MAX_FREQ)f=MAX_FREQ;
            double omega=2*Math.PI*f/RATE;
            // 低音余音长、高音余音短：T60=3.2*(196/f)^(2/3)，夹在 0.8~3.2s，接近尼龙弦听感
            double t60=3.2*Math.pow(196.0/f,2.0/3.0);
            if(t60<0.8)t60=0.8;else if(t60>3.2)t60=3.2;
            // 2) 每圈目标增益 reach=10^(-3/(T60*f))：一个周期衰减一次，T60 秒后正好 -60dB
            double reach=Math.pow(10.0,-3.0/(t60*f));
            // 3) 把总损耗拆成“低通吃 12%、环路增益吃 88%”，低通额外让高次谐波衰减更快
            double filterMag=Math.pow(reach,LOOP_LOSS_IN_FILTER);
            damp=(float)lowpassFor(omega,filterMag);
            // 系数有上限（0.96）：极低音时低通实际衰减可能和期望不同，按实际幅度反推环路增益，
            // 保证基频 T60 仍然精确；再按全通幅度恒为 1 处理。
            double actualMag=(1.0-damp)/Math.sqrt(1.0-2.0*damp*Math.cos(omega)+damp*(double)damp);
            loopGain=(float)(reach/actualMag);
            // 4) 环路总延迟必须等于 P=RATE/f：整数部分放延迟线，小数部分由全通滤波器补；
            //    环路低通和全通在基频处的相位延迟都从总延迟里扣掉，保证音准。
            double poleDelay=Math.atan(damp*Math.sin(omega)/(1.0-damp*Math.cos(omega)))/omega;
            double wanted=RATE/f-poleDelay;
            int integer=(int)Math.floor(wanted);
            if(integer<2)integer=2;
            dint=integer;
            eta=(float)allpassFor(omega,wanted-integer);
            size=dint+2;
            // 5) 激励：带速度决定亮度的低通噪声（拨片越重噪声越亮），峰值归一化后由力度决定响度
            int velocity=Math.max(1,Math.min(127,x.velocity));
            double vel=velocity/127.0;
            float velGain=(float)Math.pow(vel,1.15);
            if(velGain<0.06f)velGain=0.06f;
            float smooth=(float)(0.78-0.50*Math.pow(vel,0.7));
            line=new float[size];
            float nz=0f,mean=0f;
            for(int i=0;i<size;i++){
                nz=smooth*nz+(1f-smooth)*nextNoise();
                line[i]=nz;
                mean+=nz;
            }
            mean/=size;
            float peak=0f;
            for(int i=0;i<size;i++){
                float v=line[i]-mean; // 去直流：否则延迟环里会留一个衰减很慢的直流偏移
                line[i]=v;
                float abs=v<0?-v:v;
                if(abs>peak)peak=abs;
            }
            float norm=peak>1e-6f?(float)(EXC_PEAK*velGain/peak):0f;
            for(int i=0;i<size;i++)line[i]*=norm;
            // 6) n=0 的读位置固定为 (0-dint) mod size；全通/低通状态预置成该处样本，
            //    这样起音第一帧就是期望的激励，而不会出现滤波器从 0 收敛的瞬态。
            float x0=line[size-dint];
            apx1=x0;apy1=x0;lp=x0;
        }

        /** 产生一个样本；延迟线内循环、环路低通、包络都只做常数次浮点运算。 */
        float tick(){
            // 整数延迟从延迟线读一个样本，再经一阶全通补足小数延迟（全通幅度恒为 1）
            int index=write-dint;
            if(index<0)index+=size;
            float x=line[index];
            float ap=eta*x+apx1-eta*apy1;
            apx1=x;apy1=ap;
            // 环路低通：尼龙弦的高频损耗，越高频衰减越快
            lp+=(1f-damp)*(ap-lp);
            float out=lp*loopGain;
            line[write]=out;
            if(++write==size)write=0;
            // 起音斜坡（smoothstep），避免第一个样本跳变；也只在前几毫秒需要 age
            if(age<ATTACK_FRAMES){
                float t=age/(float)ATTACK_FRAMES;
                out*=t*t*(3f-2f*t);
                age++;
            }
            // note-off 后的 60ms 快速阻尼：smoothstep 从 1 连续落到 0，不会硬切出爆音
            if(released){
                float u=1f-releaseAge/(float)RELEASE_FRAMES;
                releaseAge++;
                if(u<=0f){finished=true;return 0f;}
                out*=u*u*(3f-2f*u);
            }
            return out;
        }
    }
}
