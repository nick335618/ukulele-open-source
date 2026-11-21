package com.ukulele.monitor;

import java.util.*;
import java.util.regex.*;

/** Editable numbered score -> exact event times. Kept independent of Android and OCR. */
final class ScoreNotation {
    private static final Pattern NOTE = Pattern.compile("([#b]?)([0-7?])([',]*)(?:/(2|4|8|16|32|64))?(\\.{0,2})(~?)");
    private static final int MAX = 10922;
    static final class Atom {
        int midi, line;
        double duration;
        boolean tie, dash;
        String control;
    }
    static final class Parsed {
        final ArrayList<ScorePitch.Note> notes = new ArrayList<>();
        long durationMs;
        int bpm, tonic;
        String csv() { return ScoreDraft.text(notes, durationMs); }
    }

    static Parsed parse(String text) {
        if (text == null || text.length() > 1000000) throw new IllegalArgumentException("简谱文稿过大");
        Parsed out = new Parsed();out.bpm=120;out.tonic=60;
        int bpm=120,tonic=60,lineNo=0;boolean repeat=true,minor=false;
        int[] accidental = new int[8];
        LinkedHashMap<String,ArrayList<Atom>> sections=new LinkedHashMap<>();
        ArrayList<Atom> current=new ArrayList<>();sections.put("",current);
        ArrayList<String> order=new ArrayList<>();
        for(String raw:text.replace('’','\'').replace('，',',').replace('。','.').split("\\r?\\n")){
            lineNo++;String line=raw.trim();if(line.isEmpty()||(line.startsWith("#")&&(line.length()<2||!Character.isDigit(line.charAt(1)))))continue;
            int eq=line.indexOf('=');
            if(eq>=0){String key=line.substring(0,eq).trim(),value=line.substring(eq+1).trim();
                if(key.equals("1")){tonic=ScoreDraft.pitch(value);if(tonic<0)throw problem(lineNo,"主音应为F4、D4等音名");out.tonic=tonic;Arrays.fill(accidental,0);}
                else if(key.equalsIgnoreCase("BPM")){try{bpm=Integer.parseInt(value);}catch(Exception e){throw problem(lineNo,"BPM无效");}if(bpm<30||bpm>240)throw problem(lineNo,"BPM应为30—240");out.bpm=bpm;}
                else if(key.equals("顺序")||key.equalsIgnoreCase("order")){order.clear();if(!value.isEmpty())for(String name:value.split("[\\s,]+"))order.add(name.toUpperCase(Locale.ROOT));}
                else if(key.equals("反复")||key.equalsIgnoreCase("repeat")){if(value.equals("是")||value.equalsIgnoreCase("yes"))repeat=true;else if(value.equals("否")||value.equalsIgnoreCase("no"))repeat=false;else throw problem(lineNo,"反复应为是或否");}
                else if(key.equals("调式")){if(value.equals("大调"))minor=false;else if(value.equals("自然小调"))minor=true;else throw problem(lineNo,"调式应为大调或自然小调");}
                else if(key.equals("拍号")||key.equalsIgnoreCase("meter")){if(!value.matches("[1-9][0-9]?/(2|4|8|16)"))throw problem(lineNo,"拍号无效");}
                else throw problem(lineNo,"未知设置："+key);
                continue;
            }
            if(line.matches("[A-Ga-g][0-9]?:")){String label=line.substring(0,line.length()-1).toUpperCase(Locale.ROOT);if(sections.containsKey(label)){if(sections.get(label)==current)continue;throw problem(lineNo,"段落名称重复："+label);}current=new ArrayList<>();sections.put(label,current);Arrays.fill(accidental,0);continue;}
            line=line.replace("|:"," |: ").replace(":|"," :| ").replaceAll("(?<!:)\\|(?!:)"," | ");
            for(String token:line.split("\\s+")){
                if(token.isEmpty())continue;
                if(token.equals("~")){if(current.isEmpty())throw problem(lineNo,"连音符前缺少音符");Atom previous=current.get(current.size()-1);if(previous.control!=null||previous.dash||previous.midi<0)throw problem(lineNo,"连音符应紧跟发声音符");previous.tie=true;continue;}
                if(token.equals("|")||token.equals("|:")||token.equals(":|")){Atom a=new Atom();a.control=token;a.line=lineNo;current.add(a);Arrays.fill(accidental,0);continue;}
                Atom a=new Atom();a.line=lineNo;
                if(token.equals("-")){a.dash=true;a.duration=60000.0/bpm;current.add(a);continue;}
                Matcher m=NOTE.matcher(token);if(!m.matches())throw problem(lineNo,"无法解析音符："+token);
                if(m.group(2).equals("?"))throw problem(lineNo,"仍有 ? 待确认音符，请对照原图填写数字");
                int degree=m.group(2).charAt(0)-'0',octave=0;for(char c:m.group(3).toCharArray())octave+=c=='\''?1:-1;
                if(Math.abs(octave)>3)throw problem(lineNo,"八度点超出范围");
                if(!m.group(1).isEmpty())accidental[degree]=m.group(1).equals("#")?1:-1;
                int[] scale=minor?new int[]{0,0,2,3,5,7,8,10}:new int[]{0,0,2,4,5,7,9,11};
                a.midi=degree==0?-1:tonic+scale[degree]+12*octave+accidental[degree];
                if(degree>0&&(a.midi<0||a.midi>127))throw problem(lineNo,"音高超出MIDI范围");
                if(degree==0&&(!m.group(1).isEmpty()||!m.group(3).isEmpty()))throw problem(lineNo,"休止符0不能带升降或八度点");
                double beat=m.group(4)==null?1:1.0/Integer.parseInt(m.group(4));int dots=m.group(5).length();
                a.duration=60000.0/bpm*beat*(2-Math.pow(.5,dots));a.tie=!m.group(6).isEmpty();
                if(a.tie&&a.midi<0)throw problem(lineNo,"休止符不能连音");current.add(a);if(current.size()>MAX)throw problem(lineNo,"音符超过设备容量");
            }
        }
        ArrayList<Atom> play=new ArrayList<>();
        if(order.isEmpty())for(ArrayList<Atom> section:sections.values())append(play,expand(section,repeat));
        else{if(!sections.get("").isEmpty())throw new IllegalArgumentException("设置段落顺序前，请为未命名的音符段添加名称");for(String name:order){ArrayList<Atom> section=sections.get(name);if(section==null)throw new IllegalArgumentException("顺序中的段落不存在："+name);append(play,expand(section,repeat));}}
        double cursor=0;int last=-2;boolean tied=false,havePrevious=false;
        for(Atom a:play){
            if(a.control!=null)continue;
            if(a.dash){if(!havePrevious||tied)throw problem(a.line,"延音横线必须跟在音符或休止之后，且不能插入连音中间");cursor+=a.duration;}
            else{if(tied){if(a.midi!=last)throw problem(a.line,"连音两端音高必须相同");}else out.notes.add(new ScorePitch.Note(Math.round(cursor),a.midi));cursor+=a.duration;last=a.midi;havePrevious=true;tied=a.tie;}
            if(cursor>7200000)throw problem(a.line,"曲谱时长超过2小时");
        }
        if(tied)throw new IllegalArgumentException("最后一个连音缺少后续音符");
        if(out.notes.isEmpty())throw new IllegalArgumentException("请输入音符");
        out.durationMs=Math.round(cursor);return out;
    }
    private static ArrayList<Atom> expand(List<Atom> source,boolean enabled){
        ArrayList<Atom> out=new ArrayList<>();ArrayDeque<Integer> start=new ArrayDeque<>();
        for(Atom a:source){if(a.control==null||a.control.equals("|")){out.add(a);continue;}
            if(!enabled)continue;
            if(a.control.equals("|:")){if(start.size()>=3)throw problem(a.line,"反复嵌套过深");start.push(out.size());}
            else{int from=start.isEmpty()?0:start.pop();if(from==out.size())throw problem(a.line,"反复段为空");ArrayList<Atom> copy=new ArrayList<>(out.subList(from,out.size()));append(out,copy);}
            if(out.size()>MAX)throw problem(a.line,"反复展开超过设备容量");
        }
        if(enabled&&!start.isEmpty())throw new IllegalArgumentException("反复开始符 |: 缺少结束符 :|");return out;
    }
    private static void append(ArrayList<Atom> target,List<Atom> values){if(target.size()+values.size()>MAX)throw new IllegalArgumentException("曲谱展开超过设备容量");target.addAll(values);}
    private static IllegalArgumentException problem(int line,String message){return new IllegalArgumentException("第"+line+"行："+message);}
}
