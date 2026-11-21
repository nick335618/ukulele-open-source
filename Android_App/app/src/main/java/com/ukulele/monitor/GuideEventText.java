package com.ukulele.monitor;
import java.io.ByteArrayOutputStream;
/** Lossless editable text for locally indexed exact-time scores. */
public final class GuideEventText{
 private GuideEventText(){}
 public static boolean isExact(String text){return text!=null&&text.trim().startsWith("# UKS1-V2");}
 public static String format(byte[] raw,String description){SongTimeline t=SongTimeline.parse(GuideSong.upgrade(raw));StringBuilder s=new StringBuilder("# UKS1-V2\n");if(description!=null)for(String line:description.split("\\r?\\n"))s.append("# ").append(line).append('\n');for(SongTimeline.Event e:t.events)s.append(e.deltaMs).append(',').append(e.mask).append(',').append(e.fret).append(',').append(e.level).append(',').append(e.flags).append('\n');return s.toString();}
 public static byte[] legacy(String text){
  StringBuilder clean=new StringBuilder("# UKS1-V2\n");
  for(String line:text.split("\\r?\\n")){int hash=line.indexOf('#');if(hash>=0)line=line.substring(0,hash);line=line.trim();if(line.isEmpty())continue;String[] f=line.split("[,;\\s]+");if(f.length<3||f.length>5)throw new IllegalArgumentException("旧曲目字段错误");clean.append(f[0]).append(',').append(f[1]).append(',').append(f[2]).append(',').append(f.length>3?f[3]:"180").append(',').append(f.length>4?f[4]:"0").append('\n');}
  return GuideSong.upgrade(decode(clean.toString(),1));
 }
 public static byte[] parse(String text){return decode(text,2);}
 private static byte[] decode(String text,int version){
  if(!isExact(text)||text.length()>1024*1024)throw new IllegalArgumentException("精确曲谱标记或长度错误");
  ByteArrayOutputStream rows=new ByteArrayOutputStream();int count=0;
  for(String line:text.split("\\r?\\n")){line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;String[] f=line.split(",",-1);if(f.length!=5)throw new IllegalArgumentException("每行需要5个事件字段");int[] x=new int[5];for(int i=0;i<5;i++)x[i]=Integer.parseInt(f[i].trim());if(x[0]<0||x[0]>65535||x[1]<0||x[1]>15||x[2]<0||x[2]>18||x[3]<0||x[3]>255||(x[4]!=0&&x[4]!=128))throw new IllegalArgumentException("事件参数超出范围");if(++count>10921)throw new IllegalArgumentException("曲谱超出容量");rows.write(x[0]);rows.write(x[0]>>8);for(int i=1;i<5;i++)rows.write(x[i]);}
  byte[] body=rows.toByteArray(),out=new byte[body.length+8];out[0]='U';out[1]='K';out[2]='S';out[3]='1';out[4]=(byte)version;out[6]=(byte)count;out[7]=(byte)(count>>8);System.arraycopy(body,0,out,8,body.length);SongTimeline.parse(out);return out;
 }
}
