package com.ukulele.monitor;
import java.util.*;
import java.io.*;

/** Numbered notation is a separate layout/grammar: lyrics and barlines are not note digits. */
final class JianpuRecognizer {
    static final class Templates extends ArrayList<OfflineScoreRecognizer.Glyph> {
        final JianpuDigitModel model;
        Templates(JianpuDigitModel model) { this.model = model; }
    }
    static void loadTemplates(InputStream input,List<OfflineScoreRecognizer.Glyph> into)throws IOException{
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"))){String line;int count=0;while((line=reader.readLine())!=null){if(line.startsWith("#")||line.trim().isEmpty())continue;int colon=line.indexOf(':');int code;try{code=Integer.parseInt(line.substring(0,colon));}catch(Exception e){throw new IOException("简谱模板编号错误",e);}if((code<0||code>108||(code>9&&code<100))||line.length()-colon-1!=384||++count>600)throw new IOException("简谱模板格式错误");boolean[] bits=new boolean[384];for(int i=0;i<384;i++){char c=line.charAt(colon+1+i);if(c!='0'&&c!='1')throw new IOException("简谱模板数据错误");bits[i]=c=='1';}into.add(new OfflineScoreRecognizer.Glyph(code,bits));}}
    }
    static final class Token {
        int number,octave,underlines,augmentation,row,x,y,w,h;
        long time;double beats;boolean tie;int rightLimit=Integer.MAX_VALUE;final ArrayList<Integer> underlineRows=new ArrayList<>();
        Token(int n,OfflineScoreRecognizer.Box b,int r){number=n;x=b.x;y=b.y;w=b.w;h=b.h;row=r;}
    }
    static final class Row {ArrayList<OfflineScoreRecognizer.Box> digits=new ArrayList<>();int center,top,height,bars;String label="";}
    static int classify(boolean[] mask,List<OfflineScoreRecognizer.Glyph> templates,int from,int to,double limit){
        JianpuDigitModel.Prediction prediction=null;
        if(from==0&&to==7&&templates instanceof Templates){prediction=((Templates)templates).model.predict(mask);if(prediction.accepted()&&prediction.digit<=7)return prediction.digit;}
        int best=-1;double distance=1;
        for(OfflineScoreRecognizer.Glyph g:templates){if(g.digit<from||g.digit>to||(from==0&&to>=100&&g.digit>7&&g.digit<100))continue;int mismatch=0,union=0;for(int i=0;i<mask.length;i++){if(mask[i]!=g.pixels[i])mismatch++;if(mask[i]||g.pixels[i])union++;}double d=mismatch/(double)Math.max(1,union);if(d<distance){distance=d;best=g.digit;}}
        if(prediction!=null){if(prediction.digit==best&&prediction.score>=.15&&prediction.gap>=.1&&distance<Math.min(limit,.4))return best;limit=Math.min(limit,.2);}
        return distance<limit?best:-1;
    }
    static int median(List<Integer> list){ArrayList<Integer> copy=new ArrayList<>(list);Collections.sort(copy);return copy.get(copy.size()/2);}
    static OfflineScoreRecognizer.Result recognize(int[] pixels,int w,int h,int tonic,int bpm,List<OfflineScoreRecognizer.Glyph> templates){
        boolean[] ink=new boolean[w*h];int dark=0;for(int i=0;i<ink.length;i++){int c=pixels[i];ink[i]=OfflineScoreRecognizer.gray(c)<165;if(ink[i])dark++;}
        if(dark>ink.length*.6){for(int i=0;i<ink.length;i++){int c=pixels[i];ink[i]=OfflineScoreRecognizer.gray(c)>90;}}
        ink=deskew(ink,w,h);
        boolean[] symbolInk=ink;ArrayList<OfflineScoreRecognizer.Box> symbolBoxes=OfflineScoreRecognizer.components(ink,w,h);if(dark>ink.length*.6)ink=closeGaps(ink,w,h);
        ArrayList<OfflineScoreRecognizer.Box> boxes=OfflineScoreRecognizer.components(ink,w,h);
        ArrayList<OfflineScoreRecognizer.Box> seeds=new ArrayList<>();
        for(OfflineScoreRecognizer.Box b:boxes)if(b.h>=8&&b.h<=Math.min(h*.75,128)&&b.w>=2&&b.w<=b.h*1.1){int n=classify(OfflineScoreRecognizer.normalized(ink,w,b),templates,0,7,.45);if(n>=0)seeds.add(b);}
        seeds.sort(Comparator.comparingInt(a->a.y+a.h/2));ArrayList<Row> rows=new ArrayList<>();
        for(OfflineScoreRecognizer.Box b:seeds){int cy=b.y+b.h/2;Row row=rows.isEmpty()?null:rows.get(rows.size()-1);if(row==null||Math.abs(cy-row.center)>Math.max(b.h,row.height)*.45){row=new Row();row.center=cy;row.height=b.h;rows.add(row);}row.digits.add(b);}
        // Barline rows supply a stronger layout anchor than OCR-like strokes in lyrics.
        ArrayList<OfflineScoreRecognizer.Box> anchors=new ArrayList<>();int longestBar=0;double bestBars=0;
        for(OfflineScoreRecognizer.Box b:boxes)if(b.h>=18&&b.h<Math.min(h*.75,w*.12)&&b.w<=Math.max(3,b.h*.18))anchors.add(b);
        for(OfflineScoreRecognizer.Box b:anchors){int peers=0;for(OfflineScoreRecognizer.Box c:anchors)if(c.h>b.h*.8&&c.h<b.h*1.25)peers++;double score=peers*(double)b.h*b.h;if(score>bestBars){bestBars=score;longestBar=b.h;}}
        final int barHeight=longestBar;anchors.removeIf(b->b.h<barHeight*.7||b.h>barHeight*1.5);anchors.sort(Comparator.comparingInt(b->b.y+b.h/2));ArrayList<Row> ruledRows=new ArrayList<>();
        for(int i=0;i<anchors.size();){int end=i+1;OfflineScoreRecognizer.Box first=anchors.get(i);int center=first.y+first.h/2;while(end<anchors.size()&&Math.abs(anchors.get(end).y+anchors.get(end).h/2-center)<first.h*.4)end++;
            if(end-i>=2){Row row=new Row();row.center=center;row.height=first.h/2;for(OfflineScoreRecognizer.Box b:boxes)if(b.h>=first.h*.4&&b.h<=first.h*1.05&&b.w>=2&&b.w<=first.h*.8&&Math.abs(b.y+b.h/2-center)<first.h*.3)row.digits.add(b);if(row.digits.size()>=2)ruledRows.add(row);}i=end;}
        if(!ruledRows.isEmpty())rows=ruledRows;
        ArrayList<Row> music=new ArrayList<>();
        for(Row row:rows){List<Integer> ys=new ArrayList<>(),hs=new ArrayList<>();int left=w,right=0;for(OfflineScoreRecognizer.Box b:row.digits){ys.add(b.y);hs.add(b.h);left=Math.min(left,b.x);right=Math.max(right,b.x+b.w);}row.height=median(hs);row.top=median(ys);row.center=row.top+row.height/2;
            for(OfflineScoreRecognizer.Box b:boxes)if(isBar(b,row))row.bars++;
            int all=0;for(OfflineScoreRecognizer.Box b:boxes)if(b.h>row.height*.6&&Math.abs(b.y+b.h/2-row.center)<row.height*.45)all++;
            // Wide white margins must not reject a clean unruled numeric line.
            // Require six accepted glyphs and a mostly numeric band for this fallback.
            if((row.digits.size()>=3&&row.bars>=2)||(row.digits.size()>=6&&right-left>w*.45&&row.digits.size()>=all*.7)||(row.bars==0&&row.digits.size()>=6&&right-left>row.height*6&&row.digits.size()==all)||(h<row.height*6&&row.digits.size()>=2&&row.bars>0))music.add(row);
        }
        boolean ruled=false;int largestRow=0;for(Row row:music){ruled|=row.bars>=2;largestRow=Math.max(largestRow,row.height);}if(ruled&&h>w*.25)music.removeIf(row->row.bars<2);
        if(music.isEmpty())throw new IllegalArgumentException("未找到可靠简谱音符行，请裁正图片或校对谱型");
        boolean[] repairedInk=ink;
        if(dark>ink.length*.6){ink=symbolInk.clone();for(Row row:music)for(int y=Math.max(0,row.top-row.height/2);y<Math.min(h,row.top+row.height);y++)System.arraycopy(repairedInk,y*w,ink,y*w,w);boxes=OfflineScoreRecognizer.components(ink,w,h);}

        OfflineScoreRecognizer.Result out=new OfflineScoreRecognizer.Result();out.systems=music.size();int firstY=music.get(0).top;
        int detected=key(symbolInk,w,symbolBoxes,firstY,templates);out.detectedTonic=detected;
        if(tonic<0){if(detected<0)throw new IllegalArgumentException("调号未确认，请手动填写简谱1的音高（如F4、D4）");tonic=detected;}
        if(detected>=0&&detected!=tonic)out.uncertain++;
        int[] meter=meter(symbolInk,w,symbolBoxes,firstY,templates);out.meterNumerator=meter[0];out.meterDenominator=meter[1];
        double quarter=60000.0/Math.max(30,Math.min(240,bpm)),cursor=0;List<Double> measures=new ArrayList<>();boolean curves=false,sections=false;StringBuilder notation=new StringBuilder("# 简谱校对稿\n1="+ScorePitch.name(tonic)+"\nBPM="+bpm+"\n反复=是\n"+(meter[0]>0?"拍号="+meter[0]+"/"+meter[1]+"\n":""));
        for(int r=0;r<music.size();r++){
            Row row=music.get(r);int endY=Math.min(h,row.top+row.height),startX=prefixEnd(ink,w,boxes,row,templates);
            boolean[] present=new boolean[w];boolean[] bar=new boolean[w];
            for(OfflineScoreRecognizer.Box b:boxes)if(isBar(b,row))for(int x=b.x;x<Math.min(w,b.x+b.w);x++)bar[x]=true;
            for(int x=startX;x<w;x++)if(!bar[x])for(int y=Math.max(0,row.top);y<endY;y++)if(ink[y*w+x]){present[x]=true;break;}
            ArrayList<Token> tokens=new ArrayList<>();
            for(int x=startX;x<w;){if(!present[x]){x++;continue;}int begin=x;while(x<w&&present[x])x++;int end=x,top=endY,bottom=row.top;
                for(int yy=Math.max(0,row.top);yy<endY;yy++)for(int xx=begin;xx<end;xx++)if(ink[yy*w+xx]){top=Math.min(top,yy);bottom=Math.max(bottom,yy);}
                OfflineScoreRecognizer.Box b=new OfflineScoreRecognizer.Box(begin,top,end-begin,bottom-top+1);
                if(b.w<2)continue;
                boolean punctuation=false;for(OfflineScoreRecognizer.Box whole:boxes)if(whole.x>=begin-row.height*.4&&whole.x<=end&&whole.x+whole.w>=end-2&&whole.h>row.height*1.3&&whole.w>3&&whole.w<row.height*.65&&Math.abs(whole.y+whole.h*.5-row.center)<row.height*.6&&isParenthesis(ink,w,whole))punctuation=true;if(punctuation)continue;
                if(b.h<row.height*.45){if(b.w>row.height*.25&&b.h<row.height*.35&&Math.abs(top+b.h/2-row.center)<row.height*.25&&dashComponent(symbolBoxes,b,row.height))tokens.add(new Token(-1,b,r));continue;}
                int pieces=b.w<row.height*.32?smallPieces(ink,w,b,row.height):0;if(pieces==2){out.repeatSigns++;continue;}
                OfflineScoreRecognizer.Box glyph=null;
                for(OfflineScoreRecognizer.Box whole:boxes)if(whole.h>=row.height*.65&&whole.h<=row.height*1.6&&whole.w<=row.height*1.35&&whole.x>=begin-row.height*.4&&whole.x<=begin+3&&whole.x+whole.w>=end-3&&Math.abs(whole.x+whole.w*.5-(begin+end)*.5)<row.height*.35&&Math.abs(whole.y+whole.h/2-row.center)<row.height*.45){if(glyph==null||whole.h>glyph.h)glyph=whole;}
                if(b.h<row.height*.7&&glyph!=null)b=glyph;
                if(b.h<row.height*.7)continue;
                int n=b.w>row.height*1.35?-1:classify(OfflineScoreRecognizer.normalized(ink,w,b),templates,0,7,.48);
                if(glyph!=null){int complete=classify(OfflineScoreRecognizer.normalized(ink,w,glyph),templates,0,7,.48);if(complete>=0&&(complete==n||glyph.h>b.h*1.25)){b=glyph;n=complete;}}
                if(n<0){if(templates instanceof Templates){JianpuDigitModel.Prediction rejected=((Templates)templates).model.predict(OfflineScoreRecognizer.normalized(ink,w,b));if(rejected.digit==10&&rejected.accepted())continue;}out.uncertain++;out.unresolved++;n=-2;}
                Token t=new Token(n,b,r);t.rightLimit=nextGlyphX(ink,w,row,present,x);t.underlines=underlines(repairedInk,w,h,t,row.height);symbols(symbolInk,w,symbolBoxes,t,row);
                t.beats=Math.pow(.5,t.underlines)*(2-Math.pow(.5,t.augmentation));tokens.add(t);
            }
            detectTies(symbolInk,w,row,tokens);
            if(!row.label.isEmpty())notation.append(row.label).append(":\n");
            ArrayList<Integer> boundaries=barPositions(bar,row.height);int boundaryIndex=0;
            for(Token token:tokens){while(boundaryIndex<boundaries.size()&&boundaries.get(boundaryIndex)<token.x){notation.append(barText(boundaries.get(boundaryIndex++),row,symbolBoxes)).append(' ');}notation.append(tokenText(token)).append(' ');}
            while(boundaryIndex<boundaries.size())notation.append(barText(boundaries.get(boundaryIndex++),row,symbolBoxes)).append(' ');notation.append('\n');
            double measure=0;int previousX=startX;Token previous=null;
            for(Token t:tokens){boolean separator=false;for(int x=previousX;x<t.x&&x<w;x++)if(bar[x])separator=true;if(separator&&measure>0){measures.add(measure);measure=0;}
                t.time=Math.round(cursor);if(t.number==-1){cursor+=quarter;measure++;if(previous!=null)previous.beats++;else out.uncertain++;}else{
                    if(t.number>0&&(previous==null||!previous.tie)){int midi=tonic+new int[]{0,0,2,4,5,7,9,11}[t.number]+12*t.octave;if(midi>=0&&midi<=127)out.notes.add(new ScorePitch.Note(Math.round(cursor),midi));else out.uncertain++;}
                    cursor+=t.beats*quarter;measure+=t.beats;out.rhythmRead++;previous=t;out.numericTokens.add(t);
                }previousX=t.x+t.w;
            }
            if(measure>0)measures.add(measure);
            for(OfflineScoreRecognizer.Box b:boxes){int cy=b.y+b.h/2;
                if(b.w>row.height*1.3&&b.h>row.height*.15&&b.h<row.height*.65&&cy<row.top&&cy>row.top-row.height*.7)curves=true;
                if(b.h>row.height*.3&&b.h<row.height*.75&&b.w<row.height*.8&&cy>row.top-row.height*.65&&cy<row.top&&b.x>startX)out.uncertain++; // grace/ornament area
            }
            if(startX>0)sections=true;
            out.repeatSigns+=repeatDots(boxes,row,bar);
        }
        if(out.notes.isEmpty())throw new IllegalArgumentException("未读出可靠音符，原谱已保留");
        out.notation=notation.toString();out.durationMs=Math.round(cursor);
        if(meter[0]>0){double expected=meter[0]*4.0/meter[1];for(int i=0;i<measures.size();i++)if(Math.abs(measures.get(i)-expected)>.05&&!(i==0&&measures.get(i)<expected)&&!(i==measures.size()-1&&measures.get(i)<expected))out.measureIssues++;}
        out.requiresReview=true; // Experimental numbered-notation branch: never auto-approve an unreviewed draft.
        out.warning=(out.requiresReview?"简谱草稿 · 需校对":"简谱音高/基本节奏检查通过")+" · 1="+ScorePitch.name(tonic)+(meter[0]>0?" · "+meter[0]+"/"+meter[1]:" · 拍号未确认")+(out.repeatSigns>0?" · 反复未展开":"")+(sections?" · 段落演奏顺序需确认":"")+(curves?" · 连音/延音连线需校对":"")+(out.uncertain>0?" · "+out.uncertain+"处符号未确认":"")+(out.measureIssues>0?" · "+out.measureIssues+"个小节时值不一致":"");
        return out;
    }
    static boolean dashComponent(List<OfflineScoreRecognizer.Box> boxes,OfflineScoreRecognizer.Box cut,int height){
        for(OfflineScoreRecognizer.Box b:boxes)if(b.x<=cut.x+2&&b.x+b.w>=cut.x+cut.w-2&&b.y<=cut.y+2&&b.y+b.h>=cut.y+cut.h-2&&b.w>=b.h*2&&b.h<height*.35&&b.w<height*2)return true;return false;
    }
    static void detectTies(boolean[] ink,int width,Row row,List<Token> tokens){
        int top=Math.max(0,row.top-row.height),bottom=row.top;if(bottom<=top)return;boolean[] strip=new boolean[width*(bottom-top)];for(int y=top;y<bottom;y++)System.arraycopy(ink,y*width,strip,(y-top)*width,width);
        for(OfflineScoreRecognizer.Box arc:OfflineScoreRecognizer.components(strip,width,bottom-top)){
            if(arc.w<row.height*.9||arc.w>row.height*15||arc.h<row.height*.12||arc.h>row.height*.95)continue;
            double left=0,right=0,center=0;int nl=0,nr=0,nc=0;
            for(int y=arc.y;y<arc.y+arc.h;y++)for(int x=arc.x;x<arc.x+arc.w;x++)if(strip[y*width+x]){if(x<arc.x+arc.w*.15){left+=y;nl++;}else if(x>arc.x+arc.w*.85){right+=y;nr++;}else if(x>arc.x+arc.w*.4&&x<arc.x+arc.w*.6){center+=y;nc++;}}
            if(nl==0||nr==0||nc==0||left/nl-center/nc<row.height*.08||right/nr-center/nc<row.height*.08)continue;
            int first=-1,last=-1;double dl=1e9,dr=1e9;
            for(int i=0;i<tokens.size();i++){Token t=tokens.get(i);if(t.number<=0)continue;double x=t.x+t.w*.5,a=Math.abs(x-arc.x),b=Math.abs(x-arc.x-arc.w);if(a<dl){dl=a;first=i;}if(b<dr){dr=b;last=i;}}
            if(first>=0&&last==first+1&&dl<row.height*.75&&dr<row.height*.75){Token a=tokens.get(first),b=tokens.get(last);if(a.number==b.number&&a.octave==b.octave)a.tie=true;}
        }
    }
    static ArrayList<Integer> barPositions(boolean[] columns,int height){ArrayList<Integer> positions=new ArrayList<>();for(int x=0;x<columns.length;){if(!columns[x]){x++;continue;}int first=x,last=x;while(x<columns.length){if(columns[x])last=x;else if(x-last>height*.35)break;x++;}positions.add((first+last)/2);}return positions;}
    static String barText(int bar,Row row,List<OfflineScoreRecognizer.Box> boxes){
        for(OfflineScoreRecognizer.Box upper:boxes)if(upper.w<=row.height*.3&&upper.h<=row.height*.3&&Math.abs(upper.x+upper.w/2-bar)<row.height*.75&&upper.y+upper.h/2<row.center&&upper.y+upper.h/2>row.center-row.height*.45)
            for(OfflineScoreRecognizer.Box lower:boxes)if(Math.abs(lower.x-upper.x)<=3&&lower.w<=row.height*.3&&lower.h<=row.height*.3&&lower.y+lower.h/2>row.center&&lower.y+lower.h/2<row.center+row.height*.45)return upper.x>bar?"|:":":|";
        return "|";
    }
    static int nextGlyphX(boolean[] ink,int width,Row row,boolean[] present,int start){
        for(int x=start;x<width;){if(!present[x]){x++;continue;}int begin=x;while(x<width&&present[x])x++;int top=row.top+row.height,bottom=row.top;
            for(int y=row.top;y<row.top+row.height&&y<ink.length/width;y++)for(int xx=begin;xx<x;xx++)if(ink[y*width+xx]){top=Math.min(top,y);bottom=Math.max(bottom,y);}
            if(bottom-top+1>row.height*.5&&x-begin>=2)return begin;
        }return width;
    }
    static String tokenText(Token token){
        if(token.number==-1)return "-";StringBuilder text=new StringBuilder(token.number==-2?"?":Integer.toString(token.number));
        for(int n=0;n<Math.abs(token.octave);n++)text.append(token.octave>0?'\'':',');
        if(token.underlines>0)text.append('/').append(1<<token.underlines);
        for(int n=0;n<token.augmentation;n++)text.append('.');if(token.tie)text.append('~');return text.toString();
    }
    static boolean[] deskew(boolean[] ink,int w,int h){
        int count=0;for(boolean pixel:ink)if(pixel)count++;
        int[] xs=new int[count],ys=new int[count];int index=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(ink[y*w+x]){xs[index]=x;ys[index++]=y;}
        // Sparse full-resolution projection avoids the 2-pixel sampling alias that
        // changed the inferred angle on thin printed strokes.
        double bestAngle=0,bestScore=-1;
        for(int step=-25;step<=25;step++){
            double angle=step*.2*Math.PI/180,slope=Math.tan(angle);int[] rows=new int[h];
            for(int i=0;i<count;i++){int yy=ys[i]+(int)Math.round((xs[i]-w/2)*slope);if(yy>=0&&yy<h)rows[yy]++;}
            double score=0;for(int n:rows)score+=(double)n*n;
            if(score>bestScore){bestScore=score;bestAngle=angle;}
        }
        if(Math.abs(bestAngle)<.0001)return ink;
        double cosine=Math.cos(bestAngle),sine=Math.sin(bestAngle),cx=(w-1)*.5,cy=(h-1)*.5;
        boolean[] corrected=new boolean[ink.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int sx=(int)Math.round(cosine*(x-cx)+sine*(y-cy)+cx);
            int sy=(int)Math.round(-sine*(x-cx)+cosine*(y-cy)+cy);
            if(sx>=0&&sx<w&&sy>=0&&sy<h)corrected[y*w+x]=ink[sy*w+sx];
        }
        return corrected;
    }
    static boolean[] closeGaps(boolean[] ink,int w,int h){boolean[] expanded=new boolean[ink.length],out=new boolean[ink.length];for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){boolean any=false;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)any|=ink[(y+dy)*w+x+dx];expanded[y*w+x]=any;}for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++){boolean all=true;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)all&=expanded[(y+dy)*w+x+dx];out[y*w+x]=all;}return out;}
    static boolean isBar(OfflineScoreRecognizer.Box b,Row r){return b.w<=Math.max(4,r.height*.32)&&b.h>r.height*1.15&&b.h<r.height*3&&Math.abs(b.y+b.h/2-r.center)<r.height*.6;}
    static int prefixEnd(boolean[] ink,int w,List<OfflineScoreRecognizer.Box> boxes,Row row,List<OfflineScoreRecognizer.Glyph> templates){
        for(OfflineScoreRecognizer.Box letter:boxes){
            if(letter.x>w*.13||letter.h<row.height*.55||letter.h>row.height*1.3||letter.w<row.height*.35||Math.abs(letter.y+letter.h/2-row.center)>row.height*.4)continue;
            int label=classify(OfflineScoreRecognizer.normalized(ink,w,letter),templates,0,101,.4);if(label<100&&letter.h<row.height*.9&&letter.y+letter.h<row.top+row.height-row.height*.18)label=classify(OfflineScoreRecognizer.normalized(ink,w,letter),templates,100,101,.4);if(label<100)continue;
            int end=letter.x+letter.w;
            String labelName=""+(char)('A'+label-100);if(label==100){OfflineScoreRecognizer.Box index=null;for(OfflineScoreRecognizer.Box q:boxes)if(q.x>=end&&q.x<end+row.height&&Math.abs(q.y+q.h/2-row.center)<row.height*.5&&q.h>row.height*.5&&q.h<row.height*1.3){int n=classify(OfflineScoreRecognizer.normalized(ink,w,q),templates,1,2,.4);if(n==1||n==2){index=q;break;}}if(index==null)continue;int tag=classify(OfflineScoreRecognizer.normalized(ink,w,index),templates,1,2,.4);labelName+=tag;end=index.x+index.w;}
            if(letter.h<row.height*.9&&letter.y+letter.h<row.top+row.height-row.height*.18){row.label=labelName;return end+3;}
            // A section colon is to the RIGHT of the label and straddles its text centre.
            // Stacked octave dots below a musical digit must never become a prefix.
            for(OfflineScoreRecognizer.Box upper:boxes)if(upper.x>=end&&upper.x<end+row.height*.8&&upper.w<row.height*.3&&upper.h<row.height*.3&&upper.y+upper.h/2<row.center&&upper.y+upper.h/2>row.center-row.height*.45)
                for(OfflineScoreRecognizer.Box lower:boxes)if(Math.abs(upper.x-lower.x)<3&&lower.w<row.height*.3&&lower.h<row.height*.3&&lower.y+lower.h/2>row.center&&lower.y+lower.h/2<row.center+row.height*.45){row.label=labelName;return Math.max(upper.x+upper.w,lower.x+lower.w)+3;}
        }
        return 0;
    }
    static int smallPieces(boolean[] ink,int w,OfflineScoreRecognizer.Box b,int size){boolean[] cut=new boolean[b.w*b.h];for(int y=0;y<b.h;y++)for(int x=0;x<b.w;x++)cut[y*b.w+x]=ink[(b.y+y)*w+b.x+x];ArrayList<OfflineScoreRecognizer.Box> parts=OfflineScoreRecognizer.components(cut,b.w,b.h);if(parts.size()==2&&parts.get(0).w<size*.4&&parts.get(1).w<size*.4&&parts.get(0).h<size*.4&&parts.get(1).h<size*.4)return 2;return 0;}
    static int underlines(boolean[] ink,int w,int h,Token t,int height){
        int count=0,last=-100,from=t.y+t.h,to=Math.min(h,from+height*3),cx=t.x+t.w/2;
        for(int y=from;y<to;y++){
            if(count==0&&y-from>height*.55)break;
            if(count>0&&y-last>height*.35)break;
            int left=cx,right=cx;while(left>0&&ink[y*w+left])left--;while(right<w-1&&ink[y*w+right])right++;
            if(y-from<3&&right-left<t.w*1.4)continue;
            if(right-left>Math.max(t.w*.7,height*.4)&&ink[y*w+cx]){if(y-last>2)count++;last=y;t.underlineRows.add(y);}
        }
        return Math.min(6,count);
    }
    static void symbols(boolean[] ink,int width,List<OfflineScoreRecognizer.Box> boxes,Token t,Row row){
        int cx=t.x+t.w/2;ArrayList<Integer> above=new ArrayList<>(),below=new ArrayList<>();
        for(OfflineScoreRecognizer.Box dot:boxes){
            if(dot.w<1||dot.h<1||dot.w>row.height*.32||dot.h>row.height*.32||dot.w>dot.h*2.5||dot.h>dot.w*2.5)continue;
            if(isRepeatDot(dot,boxes,row))continue;
            int x=dot.x+dot.w/2,y=dot.y+dot.h/2;
            int area=0;for(int yy=dot.y;yy<dot.y+dot.h;yy++)for(int xx=dot.x;xx<dot.x+dot.w;xx++)if(ink[yy*width+xx])area++;if(area<Math.max(3,row.height*row.height*.003))continue;
            int horizontal=0;for(int xx=Math.max(0,x-row.height/2);xx<Math.min(width,x+row.height/2);xx++){boolean present=false;for(int yy=Math.max(0,y-1);yy<=Math.min(ink.length/width-1,y+1);yy++)present|=ink[yy*width+xx];if(present)horizontal++;}if(horizontal>row.height*.5)continue;
            if(Math.abs(x-cx)<Math.max(row.height*.25,t.w*.45)){
                if(y<t.y&&t.y-y<row.height*2)above.add(t.y-y);

            }else if(dot.x>=t.x+t.w&&dot.x+dot.w<t.rightLimit-row.height*.12&&dot.x-t.x-t.w<row.height*(t.w<row.height*.45?1.1:.6)&&y>t.y+row.height*.4&&y<t.y+t.h+row.height*.2)t.augmentation++;
        }
        below=lowerDotDistances(ink,width,t,row.height);
        double lowerLimit=.95;if(!t.underlineRows.isEmpty())lowerLimit=(t.underlineRows.get(t.underlineRows.size()-1)-t.y-t.h)/(double)row.height+.45;
        t.octave=stackedDots(above,row.height,.95)-stackedDots(below,row.height,lowerLimit);
        t.augmentation=Math.min(2,t.augmentation);
    }
    static boolean isRepeatDot(OfflineScoreRecognizer.Box dot,List<OfflineScoreRecognizer.Box> boxes,Row row){
        int center=dot.y+dot.h/2;if(Math.abs(center-row.center)>row.height*.5)return false;
        boolean bar=false;for(OfflineScoreRecognizer.Box b:boxes)if(isBar(b,row)&&Math.abs(dot.x-b.x)<row.height*.75){bar=true;break;}if(!bar)return false;
        for(OfflineScoreRecognizer.Box partner:boxes)if(partner!=dot&&Math.abs(partner.x-dot.x)<3&&partner.w<row.height*.3&&partner.h<row.height*.3&&Math.abs(partner.y-dot.y)>row.height*.2&&Math.abs(partner.y-dot.y)<row.height*.8&&(partner.y+partner.h/2-row.center)*(center-row.center)<0)return true;
        return false;
    }
    static ArrayList<Integer> lowerDotDistances(boolean[] ink,int width,Token t,int height){
        int center=t.x+t.w/2,x0=Math.max(0,center-height),x1=Math.min(width,center+height+1),y0=t.y+t.h+1,y1=Math.min(ink.length/width,y0+height*(3+t.underlines/3));
        ArrayList<Integer> distances=new ArrayList<>();if(y0>=y1)return distances;int rw=x1-x0,rh=y1-y0;boolean[] local=new boolean[rw*rh];
        for(int y=y0;y<y1;y++)System.arraycopy(ink,y*width+x0,local,(y-y0)*rw,rw);
        // Break only confirmed reduction strokes; dots touching a beam then remain
        // individual components instead of being discarded with a wide line box.
        for(int y:t.underlineRows)if(y>=y0&&y<y1)Arrays.fill(local,(y-y0)*rw,(y-y0+1)*rw,false);
        for(OfflineScoreRecognizer.Box dot:OfflineScoreRecognizer.components(local,rw,rh)){
            if(dot.x==0||dot.x+dot.w>=rw||dot.y+dot.h>=rh||dot.w>height*.32||dot.h>height*.32||dot.w>dot.h*2.5||dot.h>dot.w*2.5)continue;
            int area=0;for(int y=dot.y;y<dot.y+dot.h;y++)for(int x=dot.x;x<dot.x+dot.w;x++)if(local[y*rw+x])area++;if(area<Math.max(3,height*height*.003))continue;
            int x=x0+dot.x+dot.w/2;if(Math.abs(x-center)<Math.max(height*.25,t.w*.45))distances.add(y0+dot.y+dot.h/2-t.y-t.h);
        }
        return distances;
    }
    static int stackedDots(List<Integer> distances,int height,double firstLimit){
        Collections.sort(distances);int count=0,previous=0;
        for(int distance:distances){if(count==0){if(distance>height*firstLimit)break;}else if(distance-previous>height*.55)break;else if(distance-previous<Math.max(2,height*.06))continue;count++;previous=distance;if(count==3)break;}
        return count;
    }
    static boolean isParenthesis(boolean[] ink,int w,OfflineScoreRecognizer.Box b){
        int maxSpan=0;double upper=0,lower=0,middle=0;int nu=0,nl=0,nm=0;
        for(int y=0;y<b.h;y++){int left=b.w,right=-1;for(int x=0;x<b.w;x++)if(ink[(b.y+y)*w+b.x+x]){left=Math.min(left,x);right=Math.max(right,x);if(y<b.h/4){upper+=x;nu++;}else if(y>=b.h*3/4){lower+=x;nl++;}else if(y>=b.h/3&&y<b.h*2/3){middle+=x;nm++;}}if(right>=0)maxSpan=Math.max(maxSpan,right-left+1);}
        if(nu==0||nl==0||nm==0||maxSpan>b.w*.7)return false;
        double a=upper/nu-middle/nm,c=lower/nl-middle/nm;
        return a*c>0&&Math.abs(a)>b.w*.15&&Math.abs(c)>b.w*.15;
    }
    static int repeatDots(List<OfflineScoreRecognizer.Box> boxes,Row row,boolean[] bars){int count=0;for(OfflineScoreRecognizer.Box a:boxes){if(a.w>row.height*.25||a.h>row.height*.25||a.w<2||a.h<2||Math.abs(a.y+a.h/2-row.center)>row.height*.5)continue;boolean near=false;for(int x=Math.max(0,a.x-row.height/2);x<Math.min(bars.length,a.x+row.height/2);x++)near|=bars[x];if(!near)continue;for(OfflineScoreRecognizer.Box b:boxes)if(Math.abs(a.x-b.x)<3&&b.w<row.height*.25&&b.h<row.height*.25&&b.y>a.y+row.height*.3&&b.y<a.y+row.height*.8){count++;break;}}return count;}
    static int key(boolean[] ink,int w,List<OfflineScoreRecognizer.Box> boxes,int firstY,List<OfflineScoreRecognizer.Glyph> templates){
        for(OfflineScoreRecognizer.Box a:boxes)if(a.y<firstY&&a.w>a.h*2&&a.h<=5&&a.x<w*.35)
            for(OfflineScoreRecognizer.Box b:boxes)if(Math.abs(a.x-b.x)<4&&b.w>b.h*2&&b.h<=5&&b.y>a.y+2&&b.y<a.y+16)
                for(OfflineScoreRecognizer.Box letter:boxes)if(letter.x>a.x+a.w&&letter.x<a.x+a.w+50&&Math.abs(letter.y+letter.h/2-(a.y+b.y)/2)<25&&letter.h>10){
                    int code=classify(OfflineScoreRecognizer.normalized(ink,w,letter),templates,100,106,.35);if(code<0)continue;
                    boolean hasOne=false;for(OfflineScoreRecognizer.Box one:boxes)if(one.x<a.x&&one.x>a.x-50&&one.h>8&&Math.abs(one.y+one.h/2-letter.y-letter.h/2)<letter.h*.7&&classify(OfflineScoreRecognizer.normalized(ink,w,one),templates,1,1,.35)==1)hasOne=true;if(!hasOne)continue;
                    int accidental=0;for(OfflineScoreRecognizer.Box mark:boxes)if(mark!=letter&&mark.x>=a.x+a.w&&mark.x<letter.x+letter.w+letter.h*.65&&mark.h>letter.h*.4&&Math.abs(mark.y+mark.h/2-letter.y-letter.h/2)<letter.h*.6){int kind=classify(OfflineScoreRecognizer.normalized(ink,w,mark),templates,107,108,.4);if(kind==107)accidental++;else if(kind==108)accidental--;else return -1;}
                    return 60+Math.floorMod(new int[]{9,11,0,2,4,5,7}[code-100]+accidental,12);
                }
        return -1;
    }
    static int[] meter(boolean[] ink,int w,List<OfflineScoreRecognizer.Box> boxes,int firstY,List<OfflineScoreRecognizer.Glyph> templates){for(OfflineScoreRecognizer.Box a:boxes)if(a.y<firstY&&a.x<w*.4&&a.h>=8&&a.h<40){int upper=classify(OfflineScoreRecognizer.normalized(ink,w,a),templates,2,9,.45);if(upper<0)continue;for(OfflineScoreRecognizer.Box b:boxes)if(b.y>a.y+a.h&&b.y<a.y+a.h*2.6&&Math.abs(a.x+a.w/2-b.x-b.w/2)<a.h*.5){int lower=classify(OfflineScoreRecognizer.normalized(ink,w,b),templates,2,8,.45);if(lower==2||lower==4||lower==8)return new int[]{upper,lower};}}return new int[]{0,0};}
}
