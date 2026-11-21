package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范简谱文本解析器（纯 Java、零依赖，可 JVM 单测）。
 *
 * <p><b>规范格式（同时作为视觉模型识谱后的转录格式）：</b>
 * <pre>
 * BPM:96
 * 1=C
 * 4/4
 * 1 1 5 5 | 6 6 5 - | 4 4 3 3 | 2 2 1 -
 * </pre>
 * 空格分隔 token；{@code 1-7} 为音符、{@code 0} 为休止；前/后缀 {@code #}/{@code b} 为升降；
 * 后缀 {@code '} 升八度（可多个）、{@code ,} 降八度；时值后缀 {@code -} 延长一拍（可多个）、
 * {@code _} 半拍、{@code .} 附点（×1.5，可与 {@code _} 组合）；{@code |} 小节线忽略。
 * 默认 BPM 96、默认 1=C、每拍 = 60000/BPM 毫秒。
 *
 * <p><b>为什么解析器要自己实现：</b>AI 识谱/转写之后必须落到可验证的确定性解析上；
 * 让模型直接产出设备事件曾导致节奏和音高凭空的「不可靠」体验，而简谱文本人可校对、
 * 程序可断言。
 */
public final class JianpuParser {

    /** 解析结果；{@link #error} 非空表示失败。 */
    public static final class ParseResult {
        public final List<AbcParser.ParsedNote> notes;
        public final int bpm;
        public final String key;
        public final String meter;
        public final String error;
        public final long durationMs;
        public String normalizedText="";public boolean rhythmUncertain,barDraft,visibleOrder;public int measureIssues;

        private ParseResult(List<AbcParser.ParsedNote> notes, int bpm, String key,
                            String meter, String error, long durationMs) {
            this.notes = Collections.unmodifiableList(notes);
            this.bpm = bpm;
            this.key = key == null ? "" : key;
            this.meter = meter == null ? "" : meter;
            this.error = error;
            this.durationMs = durationMs;
        }

        public boolean ok() {
            return error == null && !notes.isEmpty();
        }
    }

    private static final int MIN_NOTES = 4;
    private static final int MAX_INPUT_CHARS = 60000;
    private static final int MAX_NOTES = 20000;
    private static final Pattern NOTE_TOKEN =
            Pattern.compile("^([#b]?)([0-7])([#b]?)([',]*)((?:-|_|\\.)*)$");
    private static final Pattern HEADER_BPM = Pattern.compile("(?i)^BPM\\s*[:：]\\s*(\\d{1,3})$");
    private static final Pattern HEADER_KEY = Pattern.compile("^1\\s*=\\s*([A-Ga-g][#b]?)$");
    private static final Pattern HEADER_METER = Pattern.compile("^(\\d{1,2})\\s*/\\s*(\\d{1,2})$");

    private JianpuParser() {
    }

    /**
     * 解析规范简谱文本。永不抛异常：失败时返回带中文原因的 ParseResult。
     */
    public static ParseResult parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return fail("没有简谱内容", 96, "C", "4/4");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            return fail("简谱文本过长，无法解析", 96, "C", "4/4");
        }
        JianpuText.Result cleaned;
        try{cleaned=JianpuText.normalize(text);text=cleaned.text;}catch(Exception e){return fail(e.getMessage(),96,"C","4/4");}
        int bpm = 96;
        String key = "C";int baseOctave=4;
        String meter = "4/4";
        List<AbcParser.ParsedNote> notes = new ArrayList<AbcParser.ParsedNote>();
        double beatMs = 60000.0 / 96.0;
        java.util.ArrayDeque<Integer> repeats=new java.util.ArrayDeque<>();
        boolean tieNext=false;int measureStart=0,issues=0;double measureMs=0;

        try {
            String[] lines = text.replace("\r", "").split("\n", -1);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // Markdown 代码块围栏 / 注释行：跳过。
                if (line.startsWith("```") || line.startsWith("//") || line.startsWith(">")) {
                    continue;
                }
                Matcher bh=Pattern.compile("(?i)^BPM\\s*[:=]\\s*(\\d{1,3})$").matcher(line);
                Matcher kh=Pattern.compile("(?i)^1\\s*=\\s*([A-G][#b]?)([3-6]?)$").matcher(line);
                Matcher mh=HEADER_METER.matcher(line);
                if(bh.matches()){bpm=clampBpm(Integer.parseInt(bh.group(1)));beatMs=60000.0/bpm;continue;}
                if(kh.matches()){key=normalizeKey(kh.group(1));if(!kh.group(2).isEmpty())baseOctave=Integer.parseInt(kh.group(2));continue;}
                if(mh.matches()){int n=Integer.parseInt(mh.group(1)),d=Integer.parseInt(mh.group(2));if(n<1||n>16||(d!=2&&d!=4&&d!=8&&d!=16))return fail("拍号无效",bpm,key,meter);meter=n+"/"+d;continue;}
                if(line.startsWith("# ")||line.startsWith("#节奏")||line.startsWith("TIMING:")||line.startsWith("OCTAVE:"))continue;
                String normalized=line.replace("|:"," |: ").replace(":|"," :| ").replaceAll("(?<!:)\\|(?!:)"," | ");
                String[] musicTokens = normalized.split("\\s+");
                for (String token : musicTokens) {
                    String t = token.trim();
                    if(t.isEmpty())continue;
                    if(t.equals("|")||t.equals("|:")||t.equals(":|")){
                        String[] met=meter.split("/");double expected=beatMs*Integer.parseInt(met[0])*4.0/Integer.parseInt(met[1]);
                        if(measureMs>expected+3)issues++;
                        if(cleaned.barDraft&&measureMs>0)scaleMeasure(notes,measureStart,expected);
                        measureMs=0;measureStart=notes.size();
                        if(!cleaned.visibleOrder&&t.equals("|:")){if(repeats.size()>=3)return fail("反复嵌套过深",bpm,key,meter);repeats.push(notes.size());}
                        if(!cleaned.visibleOrder&&t.equals(":|")){int begin=repeats.isEmpty()?0:repeats.pop(),end=notes.size();if(begin==end)return fail("反复段为空",bpm,key,meter);for(int q=begin;q<end;q++){AbcParser.ParsedNote n=notes.get(q);notes.add(new AbcParser.ParsedNote(n.midi,n.durationMs));if(notes.size()>MAX_NOTES)return fail("反复展开过长",bpm,key,meter);}measureStart=notes.size();}
                        continue;
                    }
                    if(t.equals("~")){if(notes.isEmpty()||notes.get(notes.size()-1).isRest())return fail("连音前缺少发声音符",bpm,key,meter);tieNext=true;continue;}
                    boolean tied=t.endsWith("~");if(tied)t=t.substring(0,t.length()-1);
                    if(cleaned.barDraft&&(tied||tieNext))return fail("含连音的谱请保留原时值校对，不适用小节均分",bpm,key,meter);
                    Matcher fraction=Pattern.compile("^([#b]?[0-7][#b]?[',]*)/(2|4|8|16|32|64)([.]*?)$").matcher(t);
                    if(fraction.matches()){StringBuilder shortNote=new StringBuilder(fraction.group(1));int den=Integer.parseInt(fraction.group(2));for(int q=1;q<den;q*=2)shortNote.append('_');t=shortNote+fraction.group(3);}

                    if ("-".equals(t)) {
                        // 独立的 "-"：延长前一个音符一拍（传统简谱写法 "5 -"）。
                        long extension = Math.round(beatMs);
                        if(notes.isEmpty())return fail("延音横线前缺少音符",bpm,key,meter);
                        extendPrevious(notes, extension);measureMs+=beatMs;
                        continue;
                    }
                    Matcher noteMatcher = NOTE_TOKEN.matcher(t);
                    if (!noteMatcher.matches()) {
                        if (containsCjk(t)) {
                            // 歌词/说明混在谱面里：按整行歌词忽略，不影响主旋律解析。
                            continue;
                        }
                        return fail("无法识别的简谱记号：「" + abbreviate(t) + "」（应形如 1-7/0，可带 # b ' , - _ .）",
                                bpm, key, meter);
                    }
                    int prefixAccidental = accidentalOf(noteMatcher.group(1));
                    int degree = Integer.parseInt(noteMatcher.group(2));
                    int suffixAccidental = accidentalOf(noteMatcher.group(3));
                    int accidental = prefixAccidental != 0 ? prefixAccidental : suffixAccidental;
                    String octaveMarks = noteMatcher.group(4) == null ? "" : noteMatcher.group(4);
                    int octave = 0;
                    for (int i = 0; i < octaveMarks.length(); i++) {
                        octave += octaveMarks.charAt(i) == '\'' ? 1 : -1;
                    }
                    String durationMarks = noteMatcher.group(5) == null ? "" : noteMatcher.group(5);
                    int extensions = 0;
                    int halves = 0;
                    int dots = 0;
                    for (int i = 0; i < durationMarks.length(); i++) {
                        char c = durationMarks.charAt(i);
                        if (c == '-') {
                            extensions++;
                        } else if (c == '_') {
                            halves++;
                        } else {
                            dots++;
                        }
                    }
                    double beats = Math.pow(.5,halves) + extensions;
                    if (dots > 0) {
                        beats *= (2.0 - Math.pow(2.0, -dots));
                    }
                    long durationMs = Math.max(1, Math.round(beats * beatMs));
                    measureMs+=beats*beatMs;
                    if (degree == 0) {
                        if(tied||tieNext)return fail("休止符不能连音",bpm,key,meter);
                        notes.add(new AbcParser.ParsedNote(-1, durationMs));
                        continue;
                    }
                    int tonicPc = tonicPitchClass(key);
                    int tonicMidi = (baseOctave+1)*12 + tonicPc;
                    int[] major = {0, 2, 4, 5, 7, 9, 11};
                    int midi = tonicMidi + major[degree - 1] + 12 * (octave+cleaned.octave) + accidental;
                    if (midi < 0 || midi > 127) {
                        notes.add(new AbcParser.ParsedNote(-1, durationMs));
                    } else {
                        if(tieNext){if(notes.isEmpty()||notes.get(notes.size()-1).midi!=midi)return fail("延音连线两端必须同音",bpm,key,meter);extendPrevious(notes,durationMs);}else notes.add(new AbcParser.ParsedNote(midi, durationMs));
                        tieNext=tied;
                    }
                    if (notes.size() > MAX_NOTES) {
                        return fail("简谱音符过多，无法解析", bpm, key, meter);
                    }
                }
            }
        } catch (Throwable t) {
            return fail("简谱解析异常：" + safeMessage(t), bpm, key, meter);
        }
        if(tieNext)return fail("最后的延音连线缺少后续同音",bpm,key,meter);
        if(!repeats.isEmpty())return fail("反复符号缺少结束标记",bpm,key,meter);
        if (notes.isEmpty()) {
            return fail("没有解析到简谱音符（应是 1-7/0 + 时值后缀的规范写法）", bpm, key, meter);
        }
        int sounding = 0;
        for (AbcParser.ParsedNote note : notes) {
            if (!note.isRest()) {
                sounding++;
            }
        }
        if (sounding == 0) {
            return fail("全部是休止符，没有可演奏的音符", bpm, key, meter);
        }
        if (notes.size() < MIN_NOTES) {
            return fail("简谱音符过少（只有 " + notes.size() + " 个）", bpm, key, meter);
        }
        if(measureMs>0){String[] met=meter.split("/");double expected=beatMs*Integer.parseInt(met[0])*4.0/Integer.parseInt(met[1]);if(measureMs>expected+3)issues++;if(cleaned.barDraft)scaleMeasure(notes,measureStart,expected);}
        long total = 0;
        for (AbcParser.ParsedNote note : notes) {
            total += note.durationMs;
        }
        ParseResult result=new ParseResult(notes,bpm,key,meter,null,total);
        result.normalizedText=(cleaned.octave==0?"":"OCTAVE:"+cleaned.octave+"\n")+(cleaned.barDraft?"TIMING:BAR_DRAFT\n":"")+(cleaned.visibleOrder?"REPEAT:VISIBLE\n":"")+text;
        result.measureIssues=issues;result.rhythmUncertain=issues>0||cleaned.barDraft;result.barDraft=cleaned.barDraft;
        result.visibleOrder=cleaned.visibleOrder;
        return result;
    }

    private static void scaleMeasure(List<AbcParser.ParsedNote> notes,int start,double target){
        long sum=0;for(int i=start;i<notes.size();i++)sum+=notes.get(i).durationMs;if(sum<=0)return;
        double cumulative=0;long rounded=0;
        for(int i=start;i<notes.size();i++){AbcParser.ParsedNote n=notes.get(i);cumulative+=n.durationMs*target/sum;long next=Math.round(cumulative);notes.set(i,new AbcParser.ParsedNote(n.midi,Math.max(1,next-rounded)));rounded=next;}
    }

    /** 文本里是否像规范简谱（用于「文本→简谱解析」前的快速判断，减少无效解析）。 */
    public static boolean looksLikeJianpu(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        int noteTokens = 0;
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if ("|".equals(t) || "-".equals(t)) {
                continue;
            }
            if (NOTE_TOKEN.matcher(t).matches()) {
                noteTokens++;
                if (noteTokens >= 4) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 内部工具

    private static void extendPrevious(List<AbcParser.ParsedNote> notes, long extensionMs) {
        if (notes.isEmpty() || extensionMs <= 0) {
            return;
        }
        int index = notes.size() - 1;
        AbcParser.ParsedNote last = notes.get(index);
        notes.set(index, new AbcParser.ParsedNote(last.midi, last.durationMs + extensionMs));
    }

    private static int accidentalOf(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return "#".equals(value) ? 1 : ("b".equals(value) ? -1 : 0);
    }

    private static int tonicPitchClass(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        char letter = Character.toUpperCase(key.charAt(0));
        int letterIndex = "CDEFGAB".indexOf(letter);
        if (letterIndex < 0) {
            return 0;
        }
        int[] table = {0, 2, 4, 5, 7, 9, 11};
        int value = table[letterIndex];
        if (key.length() > 1) {
            char accidental = key.charAt(1);
            if (accidental == '#') {
                value++;
            } else if (accidental == 'b') {
                value--;
            }
        }
        return ((value % 12) + 12) % 12;
    }

    private static String normalizeKey(String raw) {
        String value = raw == null ? "C" : raw.trim();
        if (value.isEmpty()) {
            return "C";
        }
        char letter = Character.toUpperCase(value.charAt(0));
        if ("CDEFGAB".indexOf(letter) < 0) {
            return "C";
        }
        StringBuilder builder = new StringBuilder().append(letter);
        if (value.length() > 1 && (value.charAt(1) == '#' || value.charAt(1) == 'b')) {
            builder.append(value.charAt(1));
        }
        return builder.toString();
    }

    private static int clampBpm(int bpm) {
        if (bpm < 20) {
            return 20;
        }
        return Math.min(bpm, 400);
    }

    private static boolean containsCjk(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // 基本 CJK 区 + 扩展 A 区，覆盖中文歌词/说明。
            if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF')) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String value) {
        String flat = value == null ? "" : value.replace('\n', ' ').trim();
        return flat.length() <= 16 ? flat : flat.substring(0, 16) + "…";
    }

    private static ParseResult fail(String error, int bpm, String key, String meter) {
        return new ParseResult(new ArrayList<AbcParser.ParsedNote>(), bpm, key, meter, error, 0);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        String flat = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120);
    }
}
