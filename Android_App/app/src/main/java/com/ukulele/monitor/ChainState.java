package com.ukulele.monitor;
final class ChainState {
 final int mask,target,actualMask,flags,result,ticket,revision,armed;final boolean enabled;final int[] amount;
 private ChainState(byte[] d){enabled=d[2]==1;mask=u(d[3]);amount=new int[]{u(d[4]),u(d[5]),u(d[6]),u(d[7])};target=u(d[8]);actualMask=u(d[9]);flags=u(d[10]);result=u(d[11]);ticket=u(d[12])|(u(d[13])<<8);revision=u(d[14])|(u(d[15])<<8);armed=u(d[16]);}
 static ChainState parse(byte[] d){
  if(d==null||d.length!=20||u(d[0])!=0xae||d[1]!=1||d[2]<0||d[2]>1||u(d[3])>15||u(d[4])>127||u(d[5])>96||u(d[6])>96||u(d[7])>127||u(d[8])>5||u(d[9])>15||u(d[10])>31||u(d[11])>6||u(d[16])>1||u(d[17])!=127||d[18]!=0)return null;
  int crc=0;for(byte b:d)crc^=u(b);return crc==0?new ChainState(d):null;
 }
 boolean fresh(){return (flags&16)!=0;}
 boolean acceptsAfter(ChainState old){return old==null||((revision-old.revision)&65535)<32768;}
 static String command(int mask,int[] a,int target,int ticket){
  if(a==null||a.length!=4||mask>15&&mask!=254||mask<0||target<0||target>5||ticket<1||ticket>32767||a[0]<0||a[0]>127||a[1]<0||a[1]>96||a[2]<0||a[2]>96||a[3]<0||a[3]>127)throw new IllegalArgumentException();
  return String.format(java.util.Locale.ROOT,"XC:%02X%02X%02X%02X%02X%02X%04X",mask,a[0],a[1],a[2],a[3],target,ticket);
 }
 private static int u(byte x){return x&255;}
}
