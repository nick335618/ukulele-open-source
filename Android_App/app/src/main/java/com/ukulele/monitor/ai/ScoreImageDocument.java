package com.ukulele.monitor.ai;

import java.util.*;

/** Original document stays on the phone; only selected raster pages go to vision. */
public final class ScoreImageDocument {
    public final String name, mime;
    public final byte[] original;
    public final List<byte[]> pages;
    public final int firstPage, totalPages;
    public final List<AiScoreAssistant.ScoreDraft> localDrafts;
    public final List<byte[]> recognitionImages;
    public final List<Boolean> numericSections;
    public final List<String> numericHints;
    public final List<AiScoreAssistant.ScoreDraft> recognitionLocalDrafts;
    public ScoreImageDocument(String name,String mime,byte[] original,List<byte[]> pages,int first,int total) {
        this(name,mime,original,pages,first,total,null);
    }
    public ScoreImageDocument(String name,String mime,byte[] original,List<byte[]> pages,int first,int total,List<AiScoreAssistant.ScoreDraft> local) {
        this(name,mime,original,pages,first,total,local,null,null,null);
    }
    public ScoreImageDocument(String name,String mime,byte[] original,List<byte[]> pages,int first,int total,List<AiScoreAssistant.ScoreDraft> local,List<byte[]> sections,List<Boolean> numeric,List<AiScoreAssistant.ScoreDraft> sectionLocal) {
        this(name,mime,original,pages,first,total,local,sections,numeric,sectionLocal,null);
    }
    public ScoreImageDocument(String name,String mime,byte[] original,List<byte[]> pages,int first,int total,List<AiScoreAssistant.ScoreDraft> local,List<byte[]> sections,List<Boolean> numeric,List<AiScoreAssistant.ScoreDraft> sectionLocal,List<String> hints) {
        if(pages==null||pages.isEmpty()||pages.size()>6||first<1||total<first+pages.size()-1)
            throw new IllegalArgumentException("请选择连续1至6页谱面");
        this.name=name==null?"曲谱":name;this.mime=mime==null?"image/jpeg":mime;
        this.original=original;this.pages=Collections.unmodifiableList(new ArrayList<>(pages));
        firstPage=first;totalPages=total;
        if(local!=null&&local.size()!=pages.size())throw new IllegalArgumentException("分页检查数量不一致");
        localDrafts=local==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(local));
        recognitionImages=sections==null?this.pages:Collections.unmodifiableList(new ArrayList<>(sections));
        numericHints=hints==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(hints));
        if(!numericHints.isEmpty()&&numericHints.size()!=recognitionImages.size())throw new IllegalArgumentException("识谱提示数量错误");
        numericSections=numeric==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(numeric));
        recognitionLocalDrafts=sectionLocal==null?localDrafts:Collections.unmodifiableList(new ArrayList<>(sectionLocal));
        if(recognitionImages.isEmpty()||recognitionImages.size()>36||(!numericSections.isEmpty()&&numericSections.size()!=recognitionImages.size())||(!recognitionLocalDrafts.isEmpty()&&recognitionLocalDrafts.size()!=recognitionImages.size()))throw new IllegalArgumentException("识谱分段数量错误");
    }
    public int sectionCount(){return recognitionImages.size();}
    public int editableSections(){return sectionCount()>1&&numericSections.size()==sectionCount()&&!numericSections.contains(false)?1:sectionCount();}
    public String pageLabel(){return totalPages==1?"1页":("第"+firstPage+"—"+(firstPage+pages.size()-1)+"页 / 共"+totalPages+"页");}
}
