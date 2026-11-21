package com.ukulele.monitor.ai;
import java.io.*;import java.nio.file.*;import java.security.*;
/** Installs only the validated Mandarin model archive; failure preserves an existing pack. */
public final class VoicePackFile {
 public static final String EXPECTED_SHA256="3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";
 private VoicePackFile(){}
 public static void install(InputStream input,File target)throws IOException{
  File parent=target.getParentFile();if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))throw new IOException("无法创建语音包目录");
  File partial=File.createTempFile("voice-model-",".part",parent);
  try{
   MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}
   long total=0;try(OutputStream out=new FileOutputStream(partial)){byte[] b=new byte[32768];int n;while((n=input.read(b))!=-1){if(n==0)continue;total+=n;if(total>64L*1024*1024)throw new IOException("请选择配套中文语音包（文件过大）");digest.update(b,0,n);out.write(b,0,n);}}
   StringBuilder hash=new StringBuilder();for(byte v:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
   if(!EXPECTED_SHA256.equals(hash.toString()))throw new IOException("语音包校验不一致，请选择配套的 Ukulele-离线中文语音包.zip");
   try{Files.move(partial.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(partial.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
  }finally{if(partial.exists())partial.delete();}
 }
}
