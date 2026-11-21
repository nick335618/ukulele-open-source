package com.ukulele.monitor;

/** Physical fret coordinates; saved scores retain fingerings relative to the capo. */
public final class CapoMapping {
 private CapoMapping(){}
 public static int clamp(int n){return Math.max(0,Math.min(12,n));}
 public static int relative(int physical,int capo){return Math.max(0,physical-clamp(capo));}
 public static int physical(int relative,int capo){int n=relative+clamp(capo);return relative>=0&&relative<=18&&n<=18?n:31;}
 public static int marker(int row,int capo,int level,int mode){return mode!=0&&level>0&&capo>0&&row>=0&&row<=clamp(capo)?Math.max(1,level>>2):0;}
 public static int guidePixel(int relative,int row,int level,int capo){
  if(row<0||row>=18||relative<0||relative>18)return 0;
  int n=clamp(capo),p=physical(relative,n);
  return (relative==0?row>=n:p<=18&&(row==p-1||row==p))?level:0;
 }
 public static String note(int midi){String[] n={"C","C♯","D","E♭","E","F","F♯","G","A♭","A","B♭","B"};return n[midi%12]+(midi/12-1);}
}
