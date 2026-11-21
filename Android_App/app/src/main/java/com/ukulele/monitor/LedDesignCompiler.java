package com.ukulele.monitor;

import java.util.*;

/** AI chooses a bounded visual recipe; this compiler places every physical LED exactly. */
final class LedDesignCompiler {
 private LedDesignCompiler(){}
 static int number(Map<String,Object> m,String key,int def,int min,int max){
  Object v=m.get(key);if(v==null)return def;if(!(v instanceof Number))throw new IllegalArgumentException(key+"必须是整数");
  double n=((Number)v).doubleValue();if(n!=(int)n||n<min||n>max)throw new IllegalArgumentException(key+"超出范围");return (int)n;
 }
 static LedProfile compile(Map<String,Object> d,int master){
  for(String k:d.keySet())if(!Arrays.asList("type","text","density","period","brightness","direction","rows","style","phaseStep","shape").contains(k))throw new IllegalArgumentException("未知设计参数："+k);
  String type=String.valueOf(d.get("type"));int period=number(d,"period",4,1,8);if(period!=1&&period!=2&&period!=4&&period!=8)throw new IllegalArgumentException("周期只能为1/2/4/8秒");
  int brightness=number(d,"brightness",255,0,255),density=number(d,"density",28,1,100),rate=period==1?0:period==2?1:period==4?2:3;
  String direction=String.valueOf(d.getOrDefault("direction","forward"));if(!direction.equals("forward")&&!direction.equals("reverse"))throw new IllegalArgumentException("流动方向无效");
  byte[] data=new byte[144];
  if(type.equals("shape")){
   String shape=String.valueOf(d.get("shape"));String[] rows;
   if(shape.equals("heart"))rows=new String[]{"0990990","9999999","0999990","0099900"};
   else if(shape.equals("arrow"))rows=new String[]{"000000000000090","999999999999999","000000000000090","000000000000000"};
   else throw new IllegalArgumentException("此形状请使用自定义点阵描述");
   int offset=(18-rows[0].length())/2,step=number(d,"phaseStep",shape.equals("arrow")?1:0,0,15);
   String style=String.valueOf(d.getOrDefault("style",shape.equals("arrow")?"pulse":"breath"));if(!Arrays.asList("steady","pulse","breath").contains(style))throw new IllegalArgumentException("不支持的图案动态");
   for(int y=0;y<4;y++)for(int x=0;x<rows[y].length();x++){
    int xx=offset+x;if(direction.equals("reverse"))xx=17-xx;
    set(data,y,xx,brightness*(rows[y].charAt(x)-'0')/9,(style.equals("steady")?0:1)|(style.equals("pulse")?128:0)|(rate<<1)|(((-x*step)&15)<<3));
   }
  }else if(type.equals("bitmap")){
   Object raw=d.get("rows");if(!(raw instanceof List)||((List<?>)raw).size()!=4)throw new IllegalArgumentException("图案需要4行，每行18个亮度字符");
   String style=String.valueOf(d.getOrDefault("style","steady"));if(!Arrays.asList("steady","breath","pulse").contains(style))throw new IllegalArgumentException("不支持的图案动态");
   int step=number(d,"phaseStep",0,0,15);
   for(int y=0;y<4;y++){Object row=((List<?>)raw).get(y);if(!(row instanceof String)||!((String)row).matches("[0-9]{18}"))throw new IllegalArgumentException("每行需要18个0—9亮度字符");
    for(int x=0;x<18;x++)set(data,y,x,brightness*(((String)row).charAt(x)-'0')/9,
      (style.equals("steady")?0:1)|(style.equals("pulse")?128:0)|(rate<<1)|(((direction.equals("forward")?-x*step:x*step)&15)<<3));
   }
  }else if(type.equals("text")){
   String text=String.valueOf(d.getOrDefault("text","GOWIN")).toUpperCase(Locale.ROOT).trim();
   if(text.isEmpty()||text.length()>9)throw new IllegalArgumentException("请输入可放入18列的短字母图案");
   int width=-1;for(char c:text.toCharArray())width+=glyph(c)[0].length()+1;
   if(width>18)throw new IllegalArgumentException("字样宽度为"+width+"列，灯阵只有18列，请缩短文字");
   int x=(18-width)/2;for(char letter:text.toCharArray()){
    String[] shape=glyph(letter);for(int y=0;y<4;y++)for(int col=0;col<shape[y].length();col++)if(shape[y].charAt(col)=='1')set(data,y,x+col,brightness,0);
    x+=shape[0].length()+1;
   }
  }else if(type.equals("starfield")||type.equals("meteor")||type.equals("starfield_meteor")){
   if(!type.equals("meteor")){
    // Stable, distributed stars: the same description previews the exact same device profile.
    for(int i=0;i<72;i++){int h=(i*73+19)%101;if(h<density && (!type.equals("starfield_meteor")||i/18!=0)){
      int phase=(i*7+i/18*3)&15,r=Math.min(3,rate+(i%3));
      set(data,i/18,i%18,Math.max(1,brightness*(65+(i*11)%36)/100),128|1|(r<<1)|(phase<<3));
    }}
   }
   if(!type.equals("starfield")){
    int strings=type.equals("meteor")?4:1;
    for(int s=0;s<strings;s++)for(int r=0;r<18;r++){
      int offset=r*16/18,phase=((direction.equals("forward")?-offset:offset)+s*4)&15;
      set(data,s,r,brightness,128|1|(rate<<1)|(phase<<3));
    }
   }
  }else throw new IllegalArgumentException("不支持的设计类型："+type);
  return new LedProfile(data,master);
 }
 static void set(byte[] d,int s,int r,int b,int m){int i=s*18+r;d[i*2]=(byte)b;d[i*2+1]=(byte)m;}
 static LedProfile.Plan finish(String wish,String text,LedProfile previous){
  try{
   LedProfile.Plan plan=LedProfile.parseAi(text);
   // A four-row arrow/heart has a verifiable silhouette; don't trust a model's claim that a line is an arrow.
   boolean arrow=wish.contains("箭头"),heart=wish.contains("心形");
   if((arrow^heart)&&!wish.matches("(?s).*(星|字样|文字|同时|另外|除了|不要|改成|只点).*")){
    Map<String,Object> shape=new HashMap<>();shape.put("type","shape");shape.put("shape",arrow?"arrow":"heart");
    String style=wish.contains("常亮")||wish.contains("不闪")||wish.contains("不呼吸")?"steady":wish.contains("闪")||wish.contains("流动")?"pulse":wish.contains("呼吸")?"breath":"steady";
    shape.put("style",style);shape.put("direction",wish.contains("向左")?"reverse":"forward");shape.put("phaseStep",arrow&&wish.contains("流动")?1:0);
    int period=wish.contains("慢")?8:arrow?2:4;java.util.regex.Matcher time=java.util.regex.Pattern.compile("(1|2|4|8)\\s*秒").matcher(wish);if(time.find())period=Integer.parseInt(time.group(1));shape.put("period",period);
    int brightness=0;for(int i=0;i<72;i++)brightness=Math.max(brightness,plan.profile.data[i*2]&255);shape.put("brightness",brightness);
    plan=new LedProfile.Plan(compile(shape,plan.profile.level),(arrow?"箭头":"心形")+" · 已校正4行灯阵轮廓 · "+(style.equals("steady")?"常亮":period+"秒"+(style.equals("pulse")?"短闪":"呼吸")));
   }
   return enforce(wish,plan,previous);
  }catch(IllegalArgumentException error){
   LedProfile.Plan fallback=new LedProfile.Plan(previous!=null?previous:new LedProfile(new byte[144],220),"");
   LedProfile.Plan direct=enforce(wish,fallback,previous);if(direct!=fallback)return direct;throw error;
  }
 }
 /** Explicit numeric constraints are compiled independently of the model's drawing arithmetic. */
 static LedProfile.Plan enforce(String wish,LedProfile.Plan plan,LedProfile previous){
  java.util.regex.Matcher pos=java.util.regex.Pattern.compile("([GCEA])\\s*弦(?:的)?\\s*第?([0-9、,，\\s]+)(?:颗)?灯").matcher(wish);
  java.util.regex.Matcher levels=java.util.regex.Pattern.compile("亮度(?:分别)?(?:为|是)?\\s*([0-9、,，\\s]+)").matcher(wish);
  if(!pos.find()||!levels.find())return plan;
  String string=pos.group(1),positions=pos.group(2),values=levels.group(1);
  // Complex multi-region descriptions remain with the region-rule compiler, never partially rewritten.
  if(pos.find())return plan;
  String[] indices=positions.trim().split("[、,，\\s]+"),brightness=values.trim().split("[、,，\\s]+");
  if(brightness.length!=1&&brightness.length!=indices.length)throw new IllegalArgumentException("指定灯位与亮度数量不一致");
  boolean only=wish.contains("只")||wish.matches("(?s).*(?:其他|其余).*(?:熄灭|关闭|全灭).*");
  byte[] data=only?new byte[144]:(previous!=null?previous.data.clone():plan.profile.data.clone());
  int period=4;java.util.regex.Matcher time=java.util.regex.Pattern.compile("(1|2|4|8)\\s*秒").matcher(wish);if(time.find())period=Integer.parseInt(time.group(1));
  int meta=(wish.contains("闪")?129:wish.contains("呼吸")?1:0)|((period==1?0:period==2?1:period==4?2:3)<<1);
  for(int i=0;i<indices.length;i++){
   int row=Integer.parseInt(indices[i]),level=Integer.parseInt(brightness[brightness.length==1?0:i]);
   if(row<1||row>18||level<0||level>255)throw new IllegalArgumentException("灯位必须1—18，亮度必须0—255");
   int phase=wish.contains("流动")?((wish.contains("向左")?row:-row)&15):0;
   set(data,"GCEA".indexOf(string),row-1,level,meta|(phase<<3));
  }
  return new LedProfile.Plan(new LedProfile(data,plan.profile.level),"已逐项核对指定灯位与亮度 · "+string+"弦");
 }
 static String[] glyph(char c){
  switch(c){
   case 'G':return new String[]{"111","100","101","111"};case 'O':return new String[]{"111","101","101","111"};
   case 'W':return new String[]{"101","101","111","101"};case 'I':return new String[]{"1","1","1","1"};
   case 'N':return new String[]{"101","111","111","101"};case 'A':return new String[]{"010","101","111","101"};
   case 'B':return new String[]{"110","111","101","110"};case 'C':return new String[]{"111","100","100","111"};
   case 'D':return new String[]{"110","101","101","110"};case 'E':return new String[]{"111","110","100","111"};
   case 'F':return new String[]{"111","100","110","100"};case 'H':return new String[]{"101","101","111","101"};
   case 'J':return new String[]{"001","001","101","111"};case 'K':return new String[]{"101","110","110","101"};
   case 'L':return new String[]{"100","100","100","111"};case 'M':return new String[]{"101","111","101","101"};
   case 'P':return new String[]{"111","101","111","100"};case 'Q':return new String[]{"111","101","111","001"};
   case 'R':return new String[]{"110","101","110","101"};case 'S':return new String[]{"111","100","001","111"};
   case 'T':return new String[]{"111","010","010","010"};case 'U':return new String[]{"101","101","101","111"};
   case 'V':return new String[]{"101","101","101","010"};case 'X':return new String[]{"101","010","010","101"};
   case 'Y':return new String[]{"101","101","010","010"};case 'Z':return new String[]{"111","001","010","111"};
   case ' ':return new String[]{"0","0","0","0"};
   default:throw new IllegalArgumentException("4行点阵暂不支持这个字符："+c);
  }
 }
 static LedProfile.Plan sample(String type){Map<String,Object> d=new HashMap<>();d.put("type",type);d.put("text","GOWIN");d.put("period",type.equals("meteor")?2:4);return new LedProfile.Plan(compile(d,220),type.equals("text")?"GOWIN · 横屏查看字样":type.equals("meteor")?"移动亮点与渐隐拖尾":"错峰星光闪烁，伴随一束流星");}
}
