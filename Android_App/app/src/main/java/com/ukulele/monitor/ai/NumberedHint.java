package com.ukulele.monitor.ai;
import java.util.*;import java.util.regex.*;
/** Align independently read degrees to local geometric timing; a count mismatch must stay visible. */
public final class NumberedHint {
 private static final Pattern DEGREE=Pattern.compile("(?<![0-9])([#b]?)([0-7?])([#b]?)([',]*(?:/[0-9]+)?[-_.~]*)");
 private static boolean header(String s){return s.trim().matches("(?i)(?:BPM[:=].*|1\\s*=.*|[1-9][0-9]?/(?:2|4|8|16)|OCTAVE:.*|TIMING:.*|REPEAT:.*|#(?:\\s.*|$))");}
 public static String fuse(String vision,String local){
  if(vision.contains("?"))return vision;
  vision=JianpuText.normalize(vision).text;
  ArrayList<String> digits=new ArrayList<>();for(String line:vision.split("\\r?\\n"))if(!header(line)){Matcher m=DEGREE.matcher(line);while(m.find())digits.add(m.group(1)+m.group(2)+m.group(3));}
  int total=count(local);
  if(total!=digits.size())return vision+"\n?\n# 本机与AI音符数量不一致（"+total+"/"+digits.size()+"），请核对漏音";
  StringBuilder out=new StringBuilder();int index=0;
  for(String line:local.split("\\r?\\n")){
   if(header(line)){
    String replacement=line;
    String kind=line.trim().matches("(?i)1\\s*=.*")?"(?im)^\\s*(1\\s*=\\s*[A-G][#b]?[3-6]?)\\s*$":line.trim().matches("(?i)BPM[:=].*")?"(?im)^\\s*(BPM[:=]\\s*\\d{1,3})\\s*$":line.trim().matches("[1-9][0-9]?/(?:2|4|8|16)")?"(?m)^\\s*([1-9][0-9]?/(?:2|4|8|16))\\s*$":null;
    if(kind!=null){Matcher read=Pattern.compile(kind).matcher(vision);if(read.find())replacement=read.group(1);}
    out.append(replacement).append('\n');continue;
   }
   Matcher m=DEGREE.matcher(line);StringBuffer row=new StringBuffer();while(m.find()){
    String number=digits.get(index++);String marks=m.group(4);
    // A rest cannot inherit octave or tie marks from a mistakenly read note.
    if(number.equals("0"))marks=marks.replace("'","").replace(",","").replace("~","");
    m.appendReplacement(row,Matcher.quoteReplacement(number+marks));
   }m.appendTail(row);out.append(row).append('\n');
  }
  return out.toString().trim();
 }
 public static int count(String text){int total=0;for(String line:text.split("\\r?\\n"))if(!header(line)){Matcher m=DEGREE.matcher(line);while(m.find())total++;}return total;}
}
