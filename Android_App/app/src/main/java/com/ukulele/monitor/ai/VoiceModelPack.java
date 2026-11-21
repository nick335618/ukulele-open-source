package com.ukulele.monitor.ai;
import android.content.Context;import java.io.*;
/** Full builds bundle the model; light builds reuse private data or import the identical archive. */
public final class VoiceModelPack {
 private VoiceModelPack(){}
 private static File folder(Context c){return new File(c.getNoBackupFilesDir(),"voice");}
 private static File imported(Context c){return new File(folder(c),"model-cn.zip");}
 public static boolean ready(Context c){File root=new File(folder(c),"vosk-model-small-cn-0.22");return new File(root,".ready-0.22").isFile()&&new File(root,"am/final.mdl").isFile()&&new File(root,"graph/HCLr.fst").isFile();}
 public static boolean bundled(Context c){try{String[] entries=c.getAssets().list("voice");if(entries!=null)for(String n:entries)if(n.equals("model-cn.zip"))return true;}catch(IOException ignored){}return false;}
 public static boolean available(Context c){return ready(c)||imported(c).isFile()||bundled(c);}
 public static String status(Context c){return ready(c)?"离线中文语音已就绪":imported(c).isFile()?"语音包已导入，首次使用时准备":bundled(c)?"已内置离线中文语音包":"轻量版 · 可使用系统语音或导入离线包";}
 public static InputStream open(Context c)throws IOException{File f=imported(c);return f.isFile()?new FileInputStream(f):c.getAssets().open("voice/model-cn.zip");}
 public static void install(Context c,InputStream in)throws IOException{VoicePackFile.install(in,imported(c));}
}
