package com.ukulele.monitor;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
/** Shared deterministic matcher; catalogue entries are indexed once, not on each keystroke. */
public final class FuzzySongSearch {
 private static final Pattern MARKS=Pattern.compile("\\p{M}+");
 private static final Pattern NON_WORD=Pattern.compile("[^\\p{L}\\p{N}]+");
 private FuzzySongSearch(){}
 public static String normalize(String s){return NON_WORD.matcher(MARKS.matcher(Normalizer.normalize(s==null?"":s,Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT)).replaceAll("");}
 public static final class Entry{
  final String title,keys;final String[] words;
  public Entry(String title,String keys){this.title=normalize(title);this.keys=normalize(keys);String[] raw=(title+" "+keys).split("[\\s|,，]+");words=new String[raw.length];for(int i=0;i<raw.length;i++)words[i]=normalize(raw[i]);}
  public int score(String query){return scorePrepared(query,normalize(query));}
  public int scorePrepared(String query,String q){
   if(q.isEmpty())return 1;if(title.equals(q))return 1000;if(title.startsWith(q))return 900;if(title.contains(q))return 800;if(keys.contains(q))return 650;
   String[] tokens=(query==null?"":query).trim().split("\\s+");
   if(tokens.length>1){boolean all=true;for(String w:tokens){String n=normalize(w);if(!keys.contains(n)&&!title.contains(n)){all=false;break;}}if(all)return 600;}
   if(q.length()>=3){int j=0;for(int i=0;i<title.length()&&j<q.length();i++)if(title.charAt(i)==q.charAt(j))j++;if(j==q.length()&&title.length()<=q.length()*2+2)return 400;}
   if(q.length()>=3){if(distance(q,title,q.length()>=8?2:1))return 300;for(String word:words)if(word.length()>=3&&distance(q,word,q.length()>=8?2:1))return 300;}
   return 0;
  }
 }
 public static int score(String title,String keys,String query){return new Entry(title,keys).score(query);}
 private static boolean distance(String a,String b,int limit){
  if(Math.abs(a.length()-b.length())>limit)return false;
  int[] prev=new int[b.length()+1],older=null;for(int j=0;j<prev.length;j++)prev[j]=j;
  for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;int min=i;
   for(int j=1;j<=b.length();j++){int v=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));if(older!=null&&j>1&&a.charAt(i-1)==b.charAt(j-2)&&a.charAt(i-2)==b.charAt(j-1))v=Math.min(v,older[j-2]+1);next[j]=v;min=Math.min(min,v);}
   if(min>limit)return false;older=prev;prev=next;
  }return prev[b.length()]<=limit;
 }
}
