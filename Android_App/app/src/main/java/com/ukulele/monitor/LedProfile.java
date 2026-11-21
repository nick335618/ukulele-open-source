package com.ukulele.monitor;
import java.util.*;
import com.ukulele.monitor.ai.MiniJson;
/** Device-bounded, portable 72-pixel profile. All edits and AI plans use this model. */
public final class LedProfile {
 public final byte[] data;public final int level;
 public LedProfile(byte[] d,int l){if(d==null||d.length!=144||l<0||l>255)throw new IllegalArgumentException("灯光配置长度或亮度无效");data=d.clone();level=l;}
 public static int crc(byte[] d){int c=65535;for(byte b:d){c^=(b&255)<<8;for(int j=0;j<8;j++)c=((c<<1)^((c&32768)!=0?0x1021:0))&65535;}return c;}
 public boolean hasPulse(){for(int i=1;i<144;i+=2)if((data[i]&128)!=0)return true;return false;}
 public String aiContext(){StringBuilder b=new StringBuilder("当前草稿：每弦18灯，每灯[亮度,动态字节]。动态bit0启用、bit2:1周期(1/2/4/8s)、bit6:3相位、bit7短闪。总亮度=").append(level);for(int c=0;c<4;c++){b.append("\n").append("GCEA".charAt(c)).append(":");for(int row=0;row<18;row++){int i=c*18+row;b.append('[').append(data[2*i]&255).append(',').append(data[2*i+1]&255).append(']');}}return b.toString();}
 public int crc(){return crc(data);}
 public static String hex(int x){return String.format(java.util.Locale.ROOT,"%02X",x&255);}
 public String stored(){StringBuilder b=new StringBuilder("LP2").append(hex(level));for(byte v:data)b.append(hex(v));return b.toString();}
 public static LedProfile restore(String s){try{if(s==null||s.length()!=293||!s.startsWith("LP2"))return null;byte[] d=new byte[144];for(int i=0;i<144;i++)d[i]=(byte)Integer.parseInt(s.substring(5+2*i,7+2*i),16);return new LedProfile(d,Integer.parseInt(s.substring(3,5),16));}catch(Exception e){return null;}}
 public java.util.List<String> commands(int ticket){
  if(ticket<=0||ticket>255)throw new IllegalArgumentException();List<String> q=new ArrayList<>();q.add("LPB:"+hex(level)+hex(ticket)+String.format(Locale.ROOT,"%04X",crc()));
  String a="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  for(int off=0;off<144;off+=9){StringBuilder s=new StringBuilder("LP2:");for(int i=off;i<off+9;i+=3){int x=data[i]&255,y=data[i+1]&255,z=data[i+2]&255;s.append(a.charAt(x>>>2)).append(a.charAt(((x&3)<<4)|(y>>>4))).append(a.charAt(((y&15)<<2)|(z>>>6))).append(a.charAt(z&63));}q.add(s.append(hex(off)).append(hex(ticket)).toString());}
  q.add("LPC:"+hex(ticket));return q;
 }
 public int pwm(int pixel,long frame){int b=data[pixel*2]&255,m=data[pixel*2+1]&255,v=b*(level+1)>>8;if((m&1)!=0){int ph=(int)((frame*164)&65535);int x=((ph>>(5+((m>>1)&3)))+((m>>3)<<4))&255;int wave=(m&128)!=0?(x<32?(31-x)*8:0):(x<128?x*2:(255-x)*2);v=v*(wave+1)>>8;}return v;}
 public static final class Plan{public final LedProfile profile;public final String reason;Plan(LedProfile p,String r){profile=p;reason=r;}}
 private static int integer(Map<String,Object> m,String k,int def,int min,int max){if(!m.containsKey(k))return def;Object o=m.get(k);if(!(o instanceof Number))throw new IllegalArgumentException(k+"必须为整数");double d=((Number)o).doubleValue();int n=(int)d;if(d!=n||n<min||n>max)throw new IllegalArgumentException(k+"超出设备范围");return n;}
 /** Compact region rules, applied in order. Reject unsupported capabilities rather than inventing LEDs. */
 public static Plan parseAi(String text){
  if(text==null||text.length()>24000)throw new IllegalArgumentException("AI返回内容过长或为空");String t=text.trim();if(t.startsWith("```")){int n=t.indexOf('\n');int end=t.lastIndexOf("```");if(n<0||end<=n)throw new IllegalArgumentException("配置格式无效");t=t.substring(n+1,end);}
  Map<String,Object> root=MiniJson.asObject(MiniJson.parse(t));if(root==null)throw new IllegalArgumentException("需要灯光配置对象");
  for(String k:root.keySet())if(!Arrays.asList("version","master","reason","design","rules").contains(k))throw new IllegalArgumentException("不支持的配置字段："+k);
  if(integer(root,"version",0,0,2)!=2)throw new IllegalArgumentException("不支持的配置版本");
  if(root.containsKey("color")||root.containsKey("audio")||root.containsKey("code"))throw new IllegalArgumentException("当前硬件不支持颜色、音频驱动或自定义程序");
  Map<String,Object> nested=MiniJson.asObject(root.get("design"));if(nested!=null&&"rules".equals(nested.get("type"))){for(String k:nested.keySet())if(!Arrays.asList("type","rules").contains(k))throw new IllegalArgumentException("未知规则属性");if(root.containsKey("rules"))throw new IllegalArgumentException("规则重复");root=new LinkedHashMap<>(root);root.remove("design");root.put("rules",nested.get("rules"));}
  int level=integer(root,"master",180,0,255);if(root.containsKey("design")){if(root.containsKey("rules"))throw new IllegalArgumentException("design与rules不能同时出现");Map<String,Object> d=MiniJson.asObject(root.get("design"));if(d==null)throw new IllegalArgumentException("设计内容无效");String reason=MiniJson.text(root,"reason","AI灯光设计");if(reason.length()>120)reason=reason.substring(0,120);LedProfile compiled=LedDesignCompiler.compile(d,level);if("text".equals(d.get("type")))reason=String.valueOf(d.getOrDefault("text","GOWIN"))+" · 4行紧凑字模，横屏查看";return new Plan(compiled,reason);}Object rules=root.get("rules");if(!(rules instanceof List)||((List<?>)rules).isEmpty()||((List<?>)rules).size()>72)throw new IllegalArgumentException("需提供1—72条灯光规则");byte[] d=new byte[144];
  for(Object item:(List<?>)rules){Map<String,Object> r=MiniJson.asObject(item);if(r==null)throw new IllegalArgumentException("规则格式错误");
   for(String k:r.keySet())if(!Arrays.asList("strings","from","to","brightness","breath","period","phase","phaseStep","pulse").contains(k))throw new IllegalArgumentException("不支持的灯光属性："+k);
   Object strings=r.get("strings");if(!(strings instanceof String)||!((String)strings).matches("[GCEA]{1,4}"))throw new IllegalArgumentException("弦名只支持G/C/E/A");
   int from=integer(r,"from",1,1,18),to=integer(r,"to",18,1,18);if(to<from)throw new IllegalArgumentException("灯位范围顺序错误");
   int bright=integer(r,"brightness",255,0,255),period=integer(r,"period",4,1,8),phase=integer(r,"phase",0,0,15),step=integer(r,"phaseStep",0,0,15);
   if(period!=1&&period!=2&&period!=4&&period!=8)throw new IllegalArgumentException("呼吸周期支持1、2、4、8秒");
   Object pulse=r.get("pulse");if(pulse!=null&&!(pulse instanceof Boolean))throw new IllegalArgumentException("pulse必须为true或false");Object breath=r.get("breath");if(breath!=null&&!(breath instanceof Boolean))throw new IllegalArgumentException("breath必须为true或false");int rate=period==1?0:period==2?1:period==4?2:3;
   for(char col:((String)strings).toCharArray())for(int row=from-1;row<to;row++){int i="GCEA".indexOf(col)*18+row;d[2*i]=(byte)bright;d[2*i+1]=(byte)((Boolean.TRUE.equals(pulse)?128|1:0)|(Boolean.TRUE.equals(breath)?1:0)|(rate<<1)|(((phase+(row-from+1)*step)&15)<<3));}
  }
  String reason=MiniJson.text(root,"reason","AI灯光配置");if(reason.length()>120)reason=reason.substring(0,120);return new Plan(new LedProfile(d,level),reason);
 }
 public static final String AI_PROMPT="你是72灯单色尤克里里灯阵设计师。必须理解用户描述并生成硬件可执行设计，只输出JSON。灯阵横屏为18列×4行，上到下G/C/E/A，灯位1到18。优先使用经过验证的设计配方，程序会精确排灯，禁止自己猜字样。格式：{\"version\":2,\"master\":220,\"reason\":\"设计说明\",\"design\":{\"type\":\"starfield_meteor\",\"density\":28,\"period\":4,\"brightness\":255,\"direction\":\"forward\"}}。type:starfield星空错峰短闪、meteor移动流星渐隐拖尾、starfield_meteor星光和流星同时、text静态字样。文字用text字段，大写英文字母，按3×4像素字模排版，I仅1列，字间隔1列，总宽不能超过18；GOWIN可完整显示，中文、长字样不能谎称实现。density为星星密度1..100，period为1/2/4/8秒，brightness/master为0..255，direction为forward或reverse。闪烁与流星是确定性周期动画，不是真随机；不支持颜色、音频响应。心形与箭头优先用design.type=shape，shape=heart或arrow，style=breath或pulse或steady，direction=forward/reverse，phaseStep默认心形0箭头1；程序精确画出头部主干，不要自己猜图案。其他几何形状等自定义图案，可用design.type=bitmap，rows为4个18字符的字符串，每字符0..9表示相对亮度，4行对应GCEA，0为关闭9为最亮。style为steady/breath/pulse，流动闪烁用pulse，缓慢明暗呼吸用breath；phaseStep0..15，direction为forward向右或reverse向左，由程序处理方向的相位正负；period和brightness同上。这不是限定几个模板，必须按需求自由排出图形。只要用户指定具体灯号及不同亮度，必须用rules精确表达，不得改用9级量化的bitmap。比如E弦第2/5/9灯亮度80/160/240就是三条strings=E、from=to=2/5/9、brightness=80/160/240的规则；不能移动灯号。心形整体呼吸需phaseStep=0保持形状同步。向右箭头可用bitmap的三行箭头头部和横向主干，第四行留空，不要画成两条横线。若需求是任意独立LED/区域组合，则使用rules替代design：[{\"strings\":\"GCEA\",\"from\":1,\"to\":18,\"brightness\":220,\"breath\":true,\"pulse\":false,\"period\":4,\"phase\":0,\"phaseStep\":1}]，最多72规则按序覆盖，未指定关闭；pulse=true为短闪渐灭，breath=true为三角呼吸，phase与phaseStep0..15，strings为GCEA子集。星空伴流星必须使用starfield_meteor，生成GOWIN字样必须使用text配方，不能只给全亮呼吸。用户改亮暗快慢疏密方向应落实到数值。用户要求修改当前设计时参考随附草稿，保留没有要求改变的灯位；用户要求全新设计则重新设计。必须返回字面量JSON，严禁使用字符串相加、乘法或任何代码表达式，rows必须展开成完整18字符。规则可放根rules或design.type=rules及design.rules。只能在reason说明硬件不能实现的取舍，不得假装已经实现。";
}
