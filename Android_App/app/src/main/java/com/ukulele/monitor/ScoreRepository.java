package com.ukulele.monitor;
import android.content.Context;import android.util.AtomicFile;import org.json.*;import java.io.*;import java.util.*;
/** Originals and normalized events live in app-private storage, independent of URI grants. */
final class ScoreRepository {
 static final int MAX_BYTES=32*1024*1024;
 enum Category {
  ALL("全部"), MIDI("MIDI"), DOCUMENT("PDF／照片"), RECORDING("录奏"), OTHER("其他");
  final String label;
  Category(String label){this.label=label;}
 }
 final File root;
 static final class Item {
  String id,title,mime,status="待校对",difficulty="未分类",warning="",type="";long created,deleted;int bpm=120,outside=0;
  boolean recording;
  int midiTranspose;
  boolean midiBalanced;
  String midiEvents="";
  Category category(){
   if(recording)return Category.RECORDING;
   String media=mime==null?"":mime.toLowerCase(Locale.ROOT);
   String name=title==null?"":title.toLowerCase(Locale.ROOT);
   if(media.contains("midi"))return Category.MIDI;
   if(media.contains("pdf")||media.startsWith("image/"))return Category.DOCUMENT;
   if(!media.isEmpty()&&!media.equals("application/octet-stream"))return Category.OTHER;
   if(name.endsWith(".mid")||name.endsWith(".midi"))return Category.MIDI;
   if(name.matches(".*\\.(pdf|png|jpg|jpeg|webp|heic|heif|bmp|gif|tif|tiff)$"))return Category.DOCUMENT;
   return Category.OTHER;
  }
  JSONObject json()throws Exception{return new JSONObject().put("version",1).put("id",id).put("title",title).put("mime",mime).put("created",created).put("deleted",deleted).put("status",status).put("difficulty",difficulty).put("warning",warning).put("type",type).put("bpm",bpm).put("outside",outside).put("recording",recording).put("midiTranspose",midiTranspose).put("midiEvents",midiEvents).put("midiBalanced",midiBalanced);}
  static Item parse(JSONObject j)throws Exception{if(j.getInt("version")!=1)throw new IOException("曲库版本错误");Item x=new Item();x.id=j.getString("id");valid(x.id);x.title=j.getString("title");x.mime=j.optString("mime","application/octet-stream");x.created=j.optLong("created");x.deleted=j.optLong("deleted");x.status=j.optString("status","待校对");x.difficulty=j.optString("difficulty","未分类");x.warning=j.optString("warning");x.type=j.optString("type");x.bpm=j.optInt("bpm",120);x.outside=j.optInt("outside");
   // Older builds recorded MIDI with only an auto-generated title.
   x.recording=j.optBoolean("recording",x.category()==Category.MIDI&&
       x.title.matches("演奏录音 [0-9]{2}-[0-9]{2} [0-9]{2}-[0-9]{2}-[0-9]{2}\\.mid"));
   int shift=j.optInt("midiTranspose",0);x.midiTranspose=shift>=-24&&shift<=24&&shift%12==0?shift:0;
   x.midiBalanced=j.optBoolean("midiBalanced",false);
   x.midiEvents=j.optString("midiEvents","");
   if(!x.midiEvents.isEmpty()&&!x.midiEvents.matches("midi_[0-9a-f]{32}\\.uks"))throw new IOException("MIDI 指引路径错误");
   return x;}
 }
 ScoreRepository(Context c){this(new File(c.getFilesDir(),"score_library"));}
 ScoreRepository(File directory){root=directory;root.mkdirs();}
 static void valid(String id){if(id==null||!id.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("曲目编号错误");}
 File folder(String id){valid(id);return new File(root,id);}File source(String id){return new File(folder(id),"original");}
 synchronized Item importFile(String title,String mime,byte[] bytes)throws Exception{
  return importFile(title,mime,bytes,false);
 }
 synchronized Item saveRecording(String title,byte[] bytes)throws Exception{
  return importFile(title,"audio/midi",bytes,true);
 }
 private Item importFile(String title,String mime,byte[] bytes,boolean recording)throws Exception{
  if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("文件为空或超过32MB");
  Item x=new Item();
  x.id=UUID.randomUUID().toString().replace("-","");
  x.title=title==null||title.trim().isEmpty()?"未命名曲谱":title;
  boolean midi=bytes.length>=4&&bytes[0]=='M'&&bytes[1]=='T'&&bytes[2]=='h'&&bytes[3]=='d';
  x.mime=midi?"audio/midi":mime==null?"application/octet-stream":mime;
  x.recording=recording;
  x.created=System.currentTimeMillis();
  folder(x.id).mkdirs();
  atomic(source(x.id),bytes);
  write(x);
  return x;
 }
 synchronized void write(Item x)throws Exception{valid(x.id);atomic(new File(folder(x.id),"metadata.json"),x.json().toString().getBytes("UTF-8"));}
 synchronized Item get(String id)throws Exception{return Item.parse(new JSONObject(new String(readAtomic(new File(folder(id),"metadata.json")),"UTF-8")));}
 synchronized List<Item> list(boolean trash){ArrayList<Item> items=new ArrayList<>();File[] dirs=root.listFiles();if(dirs!=null)for(File dir:dirs)if(dir.isDirectory())try{Item x=get(dir.getName());if((x.deleted!=0)==trash)items.add(x);}catch(Exception ignored){}Collections.sort(items,(a,b)->Long.compare(b.created,a.created));return items;}
 synchronized void setEvents(Item x,byte[] data,String warning,int outside)throws Exception{ScoreAnalysis a=ScoreAnalysis.of(data);Item latest=get(x.id);if(latest.deleted!=0)throw new IOException("曲谱已在回收站");latest.bpm=x.bpm;latest.type=x.type;x=latest;atomic(new File(folder(x.id),x.midiEvents.isEmpty()?"events.uks":x.midiEvents),data);x.status=a.notes>0?"可练习":"无可演奏音符";x.difficulty=a.notes>0?a.difficulty:"未分类";x.warning=warning;x.outside=outside;write(x);}
 synchronized byte[] events(String id)throws Exception{
  Item item=get(id);
  if(!item.midiEvents.isEmpty()){
   File mapped=new File(folder(id),item.midiEvents);
   if(mapped.isFile())return readAtomic(mapped);
   return MidiGuide.convert(readAtomic(source(id)),item.midiTranspose,item.midiBalanced).events;
  }
  return readAtomic(new File(folder(id),"events.uks"));
 }
 synchronized void setMidiTranspose(String id,int semitones)throws Exception{setMidiSettings(id,semitones,get(id).midiBalanced);}
 synchronized void setMidiFingering(String id,boolean balanced)throws Exception{setMidiSettings(id,get(id).midiTranspose,balanced);}
 private void setMidiSettings(String id,int semitones,boolean balanced)throws Exception{
  if(semitones<-24||semitones>24||semitones%12!=0)throw new IOException("请选择支持的八度");
  Item item=get(id);if(item.deleted!=0)throw new IOException("曲谱已在回收站");
  byte[] raw=readAtomic(source(id));
  ScorePitch.Result result=MidiGuide.convert(raw,semitones,balanced);
  ScoreAnalysis analysis=ScoreAnalysis.of(result.events);
  String previous=item.midiEvents;
  String next="midi_"+UUID.randomUUID().toString().replace("-","")+".uks";
  atomic(new File(folder(id),next),result.events);
  item.midiTranspose=semitones;item.midiBalanced=balanced;item.outside=result.outside+result.overflow;
  item.status=analysis.notes>0?"可练习":"无可演奏音符";
  item.difficulty=analysis.notes>0?analysis.difficulty:"未分类";
  item.warning=MidiOctaveEditor.label(semitones)+" · "+(balanced?"换弦练习":"低把位")+" · "+result.warning();
  item.midiEvents=next;
  // Commit the metadata pointer only after its complete event file exists.
  try{write(item);}catch(Exception error){new File(folder(id),next).delete();throw error;}
  if(!previous.isEmpty())new File(folder(id),previous).delete();
 }
 synchronized void trash(String id)throws Exception{Item x=get(id);x.deleted=System.currentTimeMillis();write(x);}
 synchronized void restore(String id)throws Exception{Item x=get(id);x.deleted=0;write(x);}
 synchronized void rename(String id,String name)throws Exception{if(name.trim().isEmpty())throw new IOException("名称不能为空");Item x=get(id);x.title=name.trim();write(x);}
 synchronized void purge(String id)throws Exception{Item x=get(id);if(x.deleted==0)throw new IOException("先移到回收站");File dir=folder(id);File[] files=dir.listFiles();if(files!=null)for(File p:files)if(!p.delete())throw new IOException("删除失败");if(!dir.delete())throw new IOException("删除失败");}
 static void atomic(File file,byte[] bytes)throws Exception{AtomicFile a=new AtomicFile(file);FileOutputStream out=null;try{out=a.startWrite();out.write(bytes);a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}}
 static byte[] readAtomic(File file)throws Exception{try(InputStream in=new AtomicFile(file).openRead()){return read(in);}}
 static byte[] read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("文件超过32MB");out.write(b,0,n);}return out.toByteArray();}
}
