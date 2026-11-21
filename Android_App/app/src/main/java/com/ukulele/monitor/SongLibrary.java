package com.ukulele.monitor;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;
/** Indexed local practice scores. Notes are read only when selecting a song. */
public final class SongLibrary {
 public static final class Song {
  public final String id,title,subtitle,sourceUrl,arrangement,asset,difficulty,category,styles,searchKeys,license;
  public final FuzzySongSearch.Entry index;
  public final int bpm,noteCount;public final long durationMs;
  Song(JSONObject x)throws Exception{
   id=x.getString("id");title=x.getString("title");subtitle=x.optString("subtitle");difficulty=x.optString("difficulty");
   sourceUrl=x.optString("sourceUrl");arrangement=x.optString("arrangement");asset=x.getString("asset");
   category=x.optString("category","熟悉旋律");styles=x.optString("styles",category);license=x.optString("license");searchKeys=x.optString("searchKeys")+" "+subtitle+" "+category+" "+difficulty;
   index=new FuzzySongSearch.Entry(title,searchKeys);
   bpm=x.getInt("bpm");durationMs=x.getLong("durationMs");noteCount=x.getInt("noteCount");
   if(!asset.startsWith("songs/")||asset.contains(".."))throw new IllegalArgumentException("曲目资源路径错误");
  }
 }
 private static volatile List<Song> cached;
 private static final LinkedHashMap<String,byte[]> bytes=new LinkedHashMap<String,byte[]>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,byte[]> e){return size()>8;}};
 public static List<Song> load(Context c)throws Exception{
  List<Song> found=cached;if(found!=null)return found;
  synchronized(SongLibrary.class){if(cached==null){JSONArray a=new JSONArray(new String(read(c,"songs/library.json"),java.nio.charset.StandardCharsets.UTF_8));ArrayList<Song> out=new ArrayList<>();HashSet<String> ids=new HashSet<>();for(int i=0;i<a.length();i++){Song s=new Song(a.getJSONObject(i));if(!ids.add(s.id))throw new IOException("曲目编号重复");out.add(s);}cached=Collections.unmodifiableList(out);}return cached;}
 }
 public static List<Song> search(List<Song> songs,String query){ArrayList<Song> out=new ArrayList<>();final HashMap<Song,Integer> scores=new HashMap<>();String q=FuzzySongSearch.normalize(query);for(Song s:songs){int v=s.index.scorePrepared(query,q);if(v>0){out.add(s);scores.put(s,v);}}if(!FuzzySongSearch.normalize(query).isEmpty())out.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));return out;}
 public static byte[] events(Context c,String path)throws Exception{byte[] data=read(c,path);if(data.length>=8&&data[0]=='U'&&data[1]=='K'&&data[2]=='S'&&data[3]=='1')return GuideSong.upgrade(data);return GuideEventText.legacy(new String(data,java.nio.charset.StandardCharsets.UTF_8));}
 public static byte[] read(Context c,String path)throws Exception{
  if(path==null||!path.startsWith("songs/")||path.contains(".."))throw new IOException("无效曲目路径");
  synchronized(bytes){byte[] b=bytes.get(path);if(b!=null)return b.clone();}
  byte[] data;
  if(path.endsWith(".uks")){
   data=null;try(ZipInputStream zip=new ZipInputStream(c.getResources().openRawResource(R.raw.practice_catalog))){ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().equals(path)){data=readAll(zip,65535);break;}}
   if(data==null)throw new IOException("曲目文件缺失");
  }else try(InputStream in=c.getResources().openRawResource(SongCatalogResources.id(path))){data=readAll(in,4*1024*1024);}
  synchronized(bytes){bytes.put(path,data);}return data.clone();
 }
 private static byte[] readAll(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("曲目资源过大");out.write(b,0,n);}return out.toByteArray();}
}
