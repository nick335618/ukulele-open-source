package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ukulele.monitor.fun.ChordLibrary;

/**
 * AI 曲谱助手 3.0：多来源搜索（ABC 多站 / MIDI 直链 / Ukulele tab 解析）+ 候选列表。
 *
 * <p><b>为什么升级：</b>用户反馈「导入乐谱不能只搜 abcnotation，ukulele facilele 等也要有；
 * 搜不到怎么办；重名结果要能试听一小段再选」。2.0 只有「ABC → MIDI」两条串行路线，结果
 * 单一且搜不到时只给一句失败。3.0 把「AI 当搜索/识别助手，App 做确定性转换」扩展成多来源：
 * <ul>
 *   <li><b>ABC 谱库：</b>abcnotation.com、thesession.org、mutopiaproject.org、tunearch.org。
 *       先用 SearchResult.content（DeepSeek 返回的正文），没有再 Fetch；{@link AbcParser}
 *       解析为确定性事件；版权页/只有头的假 ABC 明确跳过。</li>
 *   <li><b>MIDI：</b>bitmidi.com 等页面用 {@link WebDownloader} 直接下 HTML，正则找
 *       {@code /uploads/xxxx.mid} 直链，再下载并用 MThd 校验；成功返回 midiBytes 交 App 既有
 *       MIDI 解析器。</li>
 *   <li><b>Ukulele tab：</b>ukutabs.com、ukulele-tabs.com、ukulele-facile.com 的四行品格 tab 用
 *       {@link UkuleleTabParser} 确定性解析；只有和弦/图片的页面记入 skippedSources，绝不编造。</li>
 * </ul>
 * 结果按「ABC 源 > TAB > MIDI」排序，最多 6 个候选交给 UI 试听选择；找不到时返回已尝试来源、
 * 跳过原因和可执行建议。
 */
public final class AiScoreAssistant {

    // ------------------------------------------------------------------ 对外数据

    /** 一个可导入的候选曲谱。旧字段保留兼容，3.0 新增 sourceName/sourceId。 */
    public static final class ScoreDraft {
        public final String title;
        public final String transcription;
        public final String eventsText;
        public final String source;
        /** 非空表示已经拿到标准 MIDI 文件，UI 应走 App 既有 MIDI 解析器导入。 */
        public final byte[] midiBytes;
        /** true = 由 ABC/简谱/TAB/MIDI 确定性解析而来；false = AI 转录，需用户校对。 */
        public final boolean deterministic;
        /** 来源网页/文件的 URL，便于用户核对。 */
        public final String sourceUrl;
        /** 来源站名（展示用），如 abcnotation.com / bitmidi.com / ukulele-tabs.com。 */
        public final String sourceName;
        /** 来源类型 + 站点，如 abc-abcnotation.com / midi-bitmidi.com / tab-ukulele-tabs.com。 */
        public final String sourceId;
        public final int omittedNotes;

        /** 旧构造器：保持 v3.12.0 调用方兼容。 */
        public ScoreDraft(String title, String transcription, String eventsText, String source) {
            this(title, transcription, eventsText, source, null, false, "", "", "");
        }

        /** v3.12.1 构造器：保持兼容。 */
        public ScoreDraft(String title, String transcription, String eventsText, String source,
                          byte[] midiBytes, boolean deterministic, String sourceUrl) {
            this(title, transcription, eventsText, source, midiBytes, deterministic, sourceUrl, "", "");
        }

        public ScoreDraft(String title, String transcription, String eventsText, String source,
                          byte[] midiBytes, boolean deterministic, String sourceUrl,
                          String sourceName, String sourceId) {
            this(title,transcription,eventsText,source,midiBytes,deterministic,sourceUrl,sourceName,sourceId,0);
        }
        public ScoreDraft(String title,String transcription,String eventsText,String source,byte[] midiBytes,boolean deterministic,String sourceUrl,int omittedNotes){
            this(title,transcription,eventsText,source,midiBytes,deterministic,sourceUrl,"","",omittedNotes);
        }
        public ScoreDraft(String title,String transcription,String eventsText,String source,byte[] midiBytes,boolean deterministic,String sourceUrl,String sourceName,String sourceId,int omittedNotes){
            this.omittedNotes=Math.max(0,omittedNotes);
            this.title = title == null ? "" : title;
            this.transcription = transcription == null ? "" : transcription;
            this.eventsText = eventsText == null ? "" : eventsText;
            this.source = source == null ? "" : source;
            this.midiBytes = midiBytes;
            this.deterministic = deterministic;
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.sourceId = sourceId == null ? "" : sourceId;
        }
    }

    public interface DraftCallback {
        void onSuccess(ScoreDraft draft);

        void onError(DeepSeekClient.AiError error);
    }

    /** 多候选搜索结果：{@code candidates} 永不为空（为空走 onError）。 */
    public interface CandidatesCallback {
        void onSuccess(List<ScoreDraft> candidates, List<String> triedSources,
                       List<String> skippedSources);

        void onError(DeepSeekClient.AiError error);
    }

    // ------------------------------------------------------------------ 常量

    private static final int TRANSCRIBE_MAX_TOKENS = 4000;
    private static final int IMAGE_MAX_TOKENS = 6000;
    /** 搜索候选最少音符数/时长：低于它的多是「8 音 4 秒」的片段或错误曲目，直接跳过。 */
    private static final int MIN_SEARCH_NOTES = 12;
    private static final long MIN_SEARCH_DURATION_MS = 8000L;
    private static final int ALIAS_MAX_TOKENS = 60;
    private static final int MAX_ALIASES = 3;
    private static final int DEFAULT_MAX_CANDIDATES = 6;
    private static final int MAX_ALLOWED_CANDIDATES = 8;
    private static final int MAX_ABC_PAGES_PER_SOURCE = 3;
    private static final int MAX_ABC_PER_SOURCE = 2;
    private static final int MAX_MIDI_PAGES_PER_SOURCE = 2;
    private static final int MAX_MIDI_PER_PAGE = 2;
    private static final int MAX_TAB_PAGES_PER_SOURCE = 2;
    private static final int PLAIN_TEXT_LIMIT = 8000;
    private static final int IMAGE_MAX_BYTES = 10 * 1024 * 1024;
    private static final int MIN_AUTHORITY = 2;
    private static final int MIN_TAB_NOTES = 8;
    /** 多来源搜索的总预算与「搜到多少条就提前收工」阈值：避免用户干等 90 秒。 */
    private static final long TOTAL_BUDGET_MS = 90000L;
    /** MIDI/TAB 阶段最多尝试前几个歌名（别名优先），控制耗时。 */
    private static final int MAX_NAMES_MIDI_TAB = 2;
    /** 候选达到这个数量（且 TAB 至少尝试过一次）就提前结束。 */
    private static final int EARLY_FINISH_CANDIDATES = 5;
    private static final float LOW_TEMPERATURE = 0.2f;

    /** ABC 谱库来源（顺序即优先级）。 */
    private static final String[] ABC_SOURCES = {
            "abcnotation.com", "thesession.org", "mutopiaproject.org", "tunearch.org"};
    /** Ukulele tab 来源（顺序即优先级）。 */
    private static final String[] TAB_SOURCES = {
            "ukutabs.com", "ukulele-tabs.com", "ukulele-facile.com"};
    /** 通用 MIDI 搜索里认可的 MIDI 站点（host 包含即命中）。 */
    private static final String[] MIDI_HOSTS = {
            "bitmidi.com", "midiworld.com", "freemidi.org", "hamienet.com", "mfiles.co.uk",
            "mutopiaproject.org", "midishow.com", "musicaneo.com", "8notes.com"};
    /** MIDI 阶段先试 bitmidi 站内搜索，再试通用搜索里的 MIDI 站。 */
    private static final String MIDI_SOURCE_BITMIDI = "bitmidi.com";
    private static final String MIDI_SOURCE_GENERIC = "通用 MIDI 搜索";

    private final DeepSeekClient client;
    private final android.content.Context context;

    public AiScoreAssistant(DeepSeekClient client) {
        this(client,null);
    }
    public AiScoreAssistant(DeepSeekClient client,android.content.Context context){this.client=client;this.context=context==null?null:context.getApplicationContext();}


    // ------------------------------------------------------------------ 联网搜谱（多候选）

    /**
     * 多来源搜谱，返回最多 {@code maxCandidates} 个候选（试听选择用）。
     * 无候选时 onError 的 message 会列出已尝试来源、跳过原因和可执行建议。
     */
    public void searchCandidates(String songName,int maxCandidates,CandidatesCallback callback){
        final CandidatesCallback once=new OnceCandidates(callback);
        if(context==null){searchOnlineCandidates(songName,maxCandidates,once);return;}
        new Thread(()->{List<ScoreDraft> local=null;try{local=com.ukulele.monitor.LocalScoreSearch.find(context,songName,maxCandidates<=0?6:maxCandidates);}catch(Exception ignored){}
            final List<ScoreDraft> found=local;new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                if(found!=null&&!found.isEmpty())once.onSuccess(found,java.util.Collections.singletonList("内置离线练习曲库"),java.util.Collections.emptyList());
                else searchOnlineCandidates(songName,maxCandidates,once);
            });
        },"local-score-search").start();
    }
    private void searchOnlineCandidates(String songName, int maxCandidates, CandidatesCallback callback) {
        final CandidatesCallback out = new OnceCandidates(callback);
        if (client == null) {
            out.onError(notReadyError());
            return;
        }
        final String name = songName == null ? "" : songName.trim();
        if (name.isEmpty()) {
            out.onError(inputError("请输入想导入的歌名"));
            return;
        }
        int limit = maxCandidates <= 0 ? DEFAULT_MAX_CANDIDATES : maxCandidates;
        limit = Math.max(1, Math.min(MAX_ALLOWED_CANDIDATES, limit));
        final SearchPipeline pipeline = new SearchPipeline(name, limit, out);
        if (containsNonAscii(name)) {
            requestAliases(name, new AliasCallback() {
                @Override
                public void onAliases(List<String> aliases) {
                    pipeline.names = buildNameList(name, aliases);
                    startAbcPhase(pipeline);
                }
            });
        } else {
            pipeline.names = buildNameList(name, null);
            startAbcPhase(pipeline);
        }
    }

    /** 旧单结果接口：取多候选中的第一个，保持 v3.12.1 UI 兼容。 */
    public void searchAndConvert(String songName, DraftCallback callback) {
        final DraftCallback out = new OnceDraft(callback);
        searchCandidates(songName, 1, new CandidatesCallback() {
            @Override
            public void onSuccess(List<ScoreDraft> candidates, List<String> triedSources,
                                  List<String> skippedSources) {
                if (candidates == null || candidates.isEmpty()) {
                    out.onError(new DeepSeekClient.AiError(0, "no_candidates",
                            buildNoCandidateMessage(triedSources, skippedSources), true));
                } else {
                    out.onSuccess(candidates.get(0));
                }
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
                out.onError(error);
            }
        });
    }

    // ------------------------------------------------------------------ 文本 / 图片（确定性解析 + AI 转写）

    /**
     * 文本 → 曲谱：顺序为 规范简谱 → ABC → Ukulele TAB → AI 转写规范简谱 → 简谱解析。
     */
    public void convertPlainText(String titleHint, String rawText, DraftCallback callback) {
        final DraftCallback out = new OnceDraft(callback);
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            out.onError(inputError("没有可转换的谱面内容，请粘贴简谱、ABC 谱或 TAB"));
            return;
        }
        if(com.ukulele.monitor.GuideEventText.isExact(text)){
            try{com.ukulele.monitor.GuideEventText.parse(text);out.onSuccess(new ScoreDraft(resolveTitle(titleHint,null),text,text,"精确时值事件校验",null,true,""));}
            catch(Exception invalid){out.onError(conversionError("事件校验失败："+invalid.getMessage()));}return;
        }
        List<String> pages;
        try {pages=ScorePageComposer.split(text);}catch(Exception e){out.onError(inputError(e.getMessage()));return;}
        if(!pages.isEmpty()){convertTextPages(titleHint,pages,0,new ArrayList<ScoreDraft>(),out);return;}
        if(text.contains("?")||text.contains("…")){out.onError(inputError("转录仍有?或省略标记，请先补齐音符"));return;}
        if(ChordScoreParser.looksLike(text)){
            ChordScoreParser.Result chords=ChordScoreParser.parse(text);
            if(chords.ok())out.onSuccess(new ScoreDraft(resolveTitle(titleHint,null),text,chords.events,"和弦规则解析",null,true,""));
            else out.onError(conversionError(chords.error));
            return;
        }
        if(UkuleleTabParser.looksLikeTab(text)){
            TabEventBuilder.Result tab=TabEventBuilder.build(text);
            if(tab.ok())out.onSuccess(new ScoreDraft(resolveTitle(titleHint,null),TabEventBuilder.canonical(text),tab.events,tab.estimatedRhythm?"TAB原弦位 · 节奏待校对":"TAB原弦位与时值解析",null,!tab.estimatedRhythm,""));
            else out.onError(conversionError(tab.error));
            return;
        }
        // 1) 规范简谱（确定性）。
        JianpuParser.ParseResult jianpu = JianpuParser.parse(text);
        if (jianpu.ok()) {
            deliverJianpu(resolveTitle(titleHint,null),text,true,"简谱文本",out);
            return;
        }
        // 2) ABC 文本（确定性）。
        String abcBlock = AbcParser.extractAbc(text);
        if (!abcBlock.isEmpty()) {
            AbcParser.ParseResult abc = AbcParser.parse(abcBlock);
            if (abc.ok()) {
                deliverArrangement(resolveTitle(titleHint, abc.title), abcBlock, abc.notes,
                        true, "ABC 解析", "", null, out);
                return;
            }
        }
        // 3) Ukulele 四线 tab（确定性）。
        UkuleleTabParser.ParseResult tab = UkuleleTabParser.parse(text);
        if (tab.ok()) {
            String tabText = UkuleleTabParser.extractTabText(text);
            deliverArrangement(resolveTitle(titleHint, null),
                    tabText.isEmpty() ? text : tabText, tab.notes, true, "TAB 解析", "", null, out);
            return;
        }
        if(JianpuParser.looksLikeJianpu(JianpuText.normalize(text).text)){out.onError(conversionError(jianpu.error));return;}
        // 4) AI 转写成规范简谱，再用确定性解析器校验。
        if (client == null) {
            out.onError(conversionError("这段文本不是可解析的简谱/ABC/TAB。可以改用「搜曲谱」或"
                    + "「相册识谱」，或粘贴规范简谱（如 1 1 5 5 / 含 X: K: 的 ABC 谱）"));
            return;
        }
        if (text.length() > PLAIN_TEXT_LIMIT) {
            out.onError(inputError("内容超过8000字，请按乐章或段落分批导入，避免漏掉后半段。"));return;
        }
        requestJianpuRewrite(titleHint, text, true, out);
    }

    /** 图片 → 曲谱：视觉模型只输出规范简谱 JSON，之后走确定性简谱解析。 */
    public void convertImage(final String titleHint, final byte[] image, final String mime,
                             DraftCallback callback) {
        final DraftCallback out = new OnceDraft(callback);
        if (client == null) {
            out.onError(notReadyError());
            return;
        }
        if (image == null || image.length == 0) {
            out.onError(inputError("没有读取到图片，请重新选择一张谱面照片"));
            return;
        }
        if (image.length > IMAGE_MAX_BYTES) {
            out.onError(inputError("图片超过 10MB，请先压缩或换一张更小的谱面照片"));
            return;
        }
        requestImageJianpu(titleHint, image, mime, true, out, IMAGE_STAGE_CLASSIFY);
    }

    public interface PageProgress { void page(int current,int total); }
    public void convertDocument(final String title,final ScoreImageDocument doc,final PageProgress progress,final DraftCallback out){
        if(doc==null){out.onError(inputError("未选择谱面"));return;}
        imagePage(title,doc,progress,0,new ArrayList<ScoreDraft>(),new OnceDraft(out));
    }
    private void imagePage(final String title,final ScoreImageDocument doc,final PageProgress progress,final int index,final List<ScoreDraft> drafts,final DraftCallback out){
        if(index==doc.sectionCount()){
            boolean numbered=doc.sectionCount()>1&&doc.numericSections.size()==doc.sectionCount()&&!doc.numericSections.contains(false);
            if(numbered){StringBuilder joined=new StringBuilder("REPEAT:VISIBLE\n# 主旋律草稿：按图片可见顺序，不展开反复与小字装饰音；请对照原谱校对。\n");for(int n=0;n<drafts.size();n++)joined.append("# 分段 ").append(n+1).append('\n').append(drafts.get(n).transcription).append('\n');
                String all=joined.toString();
                DraftCallback wrapped=new DraftCallback(){public void onSuccess(ScoreDraft d){out.onSuccess(new ScoreDraft(title,"%%PAGE:1\n"+d.transcription,d.eventsText,d.source,d.midiBytes,false,d.sourceUrl,d.omittedNotes));}public void onError(DeepSeekClient.AiError e){out.onSuccess(new ScoreDraft(title,"%%PAGE:1\n"+all,"","简谱转录待校对："+e.message));}};
                if(all.contains("?")){wrapped.onSuccess(new ScoreDraft(title,all,"","有未确定符号，请逐行核对"));return;}
                deliverJianpu(title,all,false,"逐行主旋律草稿 · 装饰音请对照原图",wrapped);
            }else out.onSuccess(ScorePageComposer.join(title,drafts));return;
        }
        if(progress!=null)progress.page(index+1,doc.sectionCount());
        if(doc.recognitionLocalDrafts.size()==doc.sectionCount()&&doc.recognitionLocalDrafts.get(index)!=null){drafts.add(doc.recognitionLocalDrafts.get(index));imagePage(title,doc,progress,index+1,drafts,out);return;}
        DraftCallback partCallback=new DraftCallback(){
            public void onSuccess(ScoreDraft draft){drafts.add(draft);imagePage(title,doc,progress,index+1,drafts,out);}
            public void onError(DeepSeekClient.AiError e){
                if(e!=null&&(e.httpCode==401||e.httpCode==402||e.httpCode==403||e.code.contains("key")||e.code.contains("balance")||e.code.contains("cancel"))){out.onError(e);return;}
                drafts.add(new ScoreDraft(title,"?\n# 本页未识别，请补全后重新校验", "", "待校对"));
                imagePage(title,doc,progress,index+1,drafts,out);
            }
        };
        if(doc.numericSections.size()==doc.sectionCount()&&doc.numericSections.get(index))convertNumberedImage(title,doc.recognitionImages.get(index),doc.numericHints.size()==doc.sectionCount()?doc.numericHints.get(index):"",partCallback);
        else convertImage(title,doc.recognitionImages.get(index),"image/jpeg",partCallback);
    }
    private void convertTextPages(final String title,final List<String> pages,final int index,final List<ScoreDraft> drafts,final DraftCallback out){
        if(index==pages.size()){out.onSuccess(ScorePageComposer.join(title,drafts));return;}
        if(pages.get(index).trim().isEmpty()||pages.get(index).contains("?")){out.onError(inputError("第"+(index+1)+"页仍有空白或?音符，请补全"));return;}
        convertPlainText(title,pages.get(index),new DraftCallback(){
            public void onSuccess(ScoreDraft draft){drafts.add(draft);convertTextPages(title,pages,index+1,drafts,out);}
            public void onError(DeepSeekClient.AiError e){out.onError(new DeepSeekClient.AiError(e==null?0:e.httpCode,"page_error","第"+(index+1)+"页："+(e==null?"校验失败":e.message),false));}
        });
    }

    // ------------------------------------------------------------------ ABC 阶段

    private void startAbcPhase(SearchPipeline pipeline) {
        pipeline.phase = SearchPipeline.PHASE_ABC;
        pipeline.nameIndex = 0;
        pipeline.sourceIndex = 0;
        nextAbcSource(pipeline);
    }

    private void nextAbcSource(final SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            startMidiPhase(pipeline);
            return;
        }
        if (pipeline.candidates.size() >= pipeline.maxCandidates) {
            finish(pipeline);
            return;
        }
        if (pipeline.nameIndex >= pipeline.names.size()) {
            startMidiPhase(pipeline);
            return;
        }
        if (pipeline.sourceIndex >= ABC_SOURCES.length) {
            pipeline.nameIndex++;
            pipeline.sourceIndex = 0;
            nextAbcSource(pipeline);
            return;
        }
        final String name = pipeline.names.get(pipeline.nameIndex);
        final String domain = ABC_SOURCES[pipeline.sourceIndex++];
        beginSource(pipeline, domain, "abc-" + domain);
        final String query = name + " abc notation site:" + domain;
        searchAbcSource(pipeline, name, domain, query, true);
    }

    private void searchAbcSource(final SearchPipeline pipeline, final String name,
                                 final String domain, final String query,
                                 final boolean allowFallback) {
        client.search(query, 5, new DeepSeekClient.SearchCallback() {
            @Override
            public void onSuccess(List<DeepSeekClient.SearchResult> results) {
                List<DeepSeekClient.SearchResult> pages =
                        abcPages(results, MAX_ABC_PAGES_PER_SOURCE);
                if (pages.isEmpty() && allowFallback) {
                    // "abc notation" 关键词对部分站内搜索太宽/太窄，去掉再试一次。
                    searchAbcSource(pipeline, name, domain, name + " site:" + domain, false);
                    return;
                }
                if (pages.isEmpty()) {
                    pipeline.sourceSkipReason = "无搜索结果";
                    endSource(pipeline);
                    nextAbcSource(pipeline);
                    return;
                }
                pipeline.currentPages = pages;
                pipeline.pageIndex = 0;
                pipeline.currentDomain = domain;
                fetchAbcPage(pipeline);
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
                if (allowFallback) {
                    searchAbcSource(pipeline, name, domain, name + " site:" + domain, false);
                    return;
                }
                pipeline.sourceSkipReason = "搜索接口失败";
                endSource(pipeline);
                nextAbcSource(pipeline);
            }
        });
    }

    private void fetchAbcPage(final SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            pipeline.sourceSkipReason = "超过搜索预算";
            endSource(pipeline);
            startMidiPhase(pipeline);
            return;
        }
        if (pipeline.pageIndex >= pipeline.currentPages.size()
                || pipeline.candidates.size() >= pipeline.maxCandidates) {
            endSource(pipeline);
            nextAbcSource(pipeline);
            return;
        }
        final DeepSeekClient.SearchResult page = pipeline.currentPages.get(pipeline.pageIndex++);
        // 1) 先吃 SearchResult.content：很多站点 Fetch 会 403/404/504，但正文已经在搜索结果里。
        AbcParser.ParseResult fromContent = parseAbcContent(page.content);
        if (fromContent != null) {
            addAbcCandidate(pipeline, page, fromContent, page.content);
            fetchAbcPage(pipeline);
            return;
        }
        if (hasCopyrightOnly(page.content)) {
            pipeline.sourceSkipReason = "版权页未显示谱面";
            fetchAbcPage(pipeline);
            return;
        }
        // 2) content 没有 ABC 时才 Fetch 页面兜底。
        client.fetchPage(page.url, new DeepSeekClient.FetchCallback() {
            @Override
            public void onSuccess(DeepSeekClient.PageContent content) {
                String body = content == null || content.content == null ? "" : content.content;
                AbcParser.ParseResult parsed = parseAbcContent(body);
                if (parsed != null) {
                    addAbcCandidate(pipeline, page, parsed, body);
                } else if (hasCopyrightOnly(body)) {
                    pipeline.sourceSkipReason = "版权页未显示谱面";
                }
                fetchAbcPage(pipeline);
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
                // 页面抓不到就换下一条候选，不阻塞后续来源。
                fetchAbcPage(pipeline);
            }
        });
    }

    private void addAbcCandidate(SearchPipeline pipeline, DeepSeekClient.SearchResult page,
                                 AbcParser.ParseResult parsed, String abcSourceText) {
        String abc = AbcParser.extractAbc(abcSourceText);
        if (abc.isEmpty()) {
            return;
        }
        String domain = pipeline.currentDomain;
        // 搜索"abc notation"时可能命中 abcnotation 的「Keys and modes」等 ABC 示例页/无关页；
        // 用页面标题与歌名/别名做相关性过滤，避免把别的曲子当成本曲。
        if (!isRelevantToSong(pipeline, parsed.title, page)) {
            pipeline.sourceSkipReason = "页面标题与歌名不匹配";
            return;
        }
        int sameSource = 0;
        for (ScoreDraft existing : pipeline.candidates) {
            if (existing.sourceId.equals("abc-" + domain)) {
                sameSource++;
            }
        }
        if (sameSource >= MAX_ABC_PER_SOURCE) {
            return;
        }
        ScoreArranger.Arrangement arrangement = ScoreArranger.arrange(parsed.notes);
        if (!arrangement.ok()) {
            pipeline.sourceSkipReason = "编配失败（" + arrangement.error + "）";
            return;
        }
        if (arrangement.totalNotes < MIN_SEARCH_NOTES
                || arrangement.durationMs < MIN_SEARCH_DURATION_MS) {
            // 8 音 / 4 秒的片段几乎一定是搜索命中的错误曲目或谱面片段，不能当整首歌给用户。
            pipeline.sourceSkipReason = "片段过短（" + arrangement.totalNotes + " 音/"
                    + arrangement.durationMs + "ms），已跳过";
            return;
        }
        String title = resolveTitle(pipeline.songName, parsed.title);
        ScoreDraft draft = new ScoreDraft(title, abc, arrangement.eventsText,
                "ABC · " + domain, null, true, page.url, domain, "abc-" + domain);
        addCandidate(pipeline, draft);
    }

    /** 页面/曲谱标题是否与请求的歌名或别名相关（纯启发式，宁缺毋滥）。 */
    private static boolean isRelevantToSong(SearchPipeline pipeline, String parsedTitle,
                                            DeepSeekClient.SearchResult page) {
        List<String> tokens = new ArrayList<String>();
        if (pipeline.names != null) {
            for (String name : pipeline.names) {
                tokens.addAll(nameTokens(name));
            }
        }
        if (tokens.isEmpty()) {
            // 没有英文别名（例如别名服务失败、只有中文原名）时不做过滤，避免全部误杀。
            return true;
        }
        String haystack = normalizeForMatch((parsedTitle == null ? "" : parsedTitle)
                + " " + (page.title == null ? "" : page.title) + " " + page.url);
        for (String token : tokens) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<String>();
        if (name == null) {
            return tokens;
        }
        String normalized = normalizeForMatch(name);
        String[] parts = normalized.split("[^a-z0-9]+");
        for (String part : parts) {
            if (part.length() >= 3 && !part.matches("^[0-9]+$") && !isRelevanceStopword(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /** 通用词不参与歌名相关性判断，避免 "song/music/tune" 之类的页面混进候选。 */
    private static boolean isRelevanceStopword(String token) {
        return "song".equals(token) || "songs".equals(token) || "music".equals(token)
                || "tune".equals(token) || "tunes".equals(token) || "sheet".equals(token)
                || "piano".equals(token) || "guitar".equals(token) || "ukulele".equals(token)
                || "tab".equals(token) || "version".equals(token) || "arrangement".equals(token)
                || "traditional".equals(token) || "trad".equals(token);
    }

    /** 小写 + 去重音（Frère → frere），只用于标题相关性比较。 */
    private static String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        try {
            String decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD);
            return decomposed.replaceAll("\\p{M}+", "");
        } catch (Throwable ignored) {
            return lower;
        }
    }

    // ------------------------------------------------------------------ MIDI 阶段

    private void startMidiPhase(SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        pipeline.phase = SearchPipeline.PHASE_MIDI;
        pipeline.midiAttempted = true;
        pipeline.nameIndex = 0;
        pipeline.sourceIndex = 0;
        nextMidiSource(pipeline);
    }

    private void nextMidiSource(final SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            startTabPhase(pipeline);
            return;
        }
        if (pipeline.candidates.size() >= pipeline.maxCandidates
                || pipeline.candidates.size() >= earlyThreshold(pipeline)) {
            // ABC 已经给出足够候选：MIDI 不再加码，直接去 TAB 尝试一个来源后收工。
            startTabPhase(pipeline);
            return;
        }
        if (pipeline.nameIndex >= Math.min(pipeline.names.size(), MAX_NAMES_MIDI_TAB)) {
            startTabPhase(pipeline);
            return;
        }
        if (pipeline.sourceIndex >= 2) {
            pipeline.nameIndex++;
            pipeline.sourceIndex = 0;
            nextMidiSource(pipeline);
            return;
        }
        final String name = pipeline.names.get(pipeline.nameIndex);
        final boolean bitmidiOnly = pipeline.sourceIndex == 0;
        pipeline.sourceIndex++;
        if (!bitmidiOnly && pipeline.candidates.size() >= 3) {
            // bitmidi 已试过、且已经有 3 个候选：通用 MIDI 搜索常伴随很慢的页面 HTML 下载，
            // 直接跳到 TAB 阶段试一个来源后收工，避免用户等太久。
            pipeline.nameIndex++;
            pipeline.sourceIndex = 0;
            nextMidiSource(pipeline);
            return;
        }
        final String display = bitmidiOnly ? MIDI_SOURCE_BITMIDI : MIDI_SOURCE_GENERIC;
        beginSource(pipeline, display, bitmidiOnly ? "midi-bitmidi.com" : "midi-generic");
        final String query = bitmidiOnly ? name + " midi site:bitmidi.com" : name + " midi";
        client.search(query, 5, new DeepSeekClient.SearchCallback() {
            @Override
            public void onSuccess(List<DeepSeekClient.SearchResult> results) {
                List<DeepSeekClient.SearchResult> pages =
                        midiPages(results, bitmidiOnly, MAX_MIDI_PAGES_PER_SOURCE);
                if (pages.isEmpty()) {
                    pipeline.sourceSkipReason = "无搜索结果";
                    endSource(pipeline);
                    nextMidiSource(pipeline);
                    return;
                }
                pipeline.currentPages = pages;
                pipeline.pageIndex = 0;
                pipeline.currentName = name;
                probeMidiPage(pipeline);
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
                pipeline.sourceSkipReason = "搜索接口失败";
                endSource(pipeline);
                nextMidiSource(pipeline);
            }
        });
    }

    private void probeMidiPage(final SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            pipeline.sourceSkipReason = "超过搜索预算";
            endSource(pipeline);
            startTabPhase(pipeline);
            return;
        }
        if (pipeline.pageIndex >= pipeline.currentPages.size()
                || pipeline.candidates.size() >= pipeline.maxCandidates) {
            endSource(pipeline);
            nextMidiSource(pipeline);
            return;
        }
        final DeepSeekClient.SearchResult page = pipeline.currentPages.get(pipeline.pageIndex);
        final String host = hostOf(page.url);
        final int candidatesBefore = pipeline.candidates.size();
        // 1) 搜索结果 URL 本身就是 .mid 直链时先试（Wiki 的 File: 页除外，它是 HTML 页）；
        //    正文里的 .mid 链接也直接试。
        List<String> links = new ArrayList<String>();
        if (looksLikeMidiUrl(page.url)
                && !page.url.toLowerCase(Locale.ROOT).contains("/wiki/")) {
            links.add(page.url);
        }
        links.addAll(MidiUrlFinder.find(page.content, page.url));
        pipeline.pageIndex++;
        final Runnable afterLinks = new Runnable() {
            @Override
            public void run() {
                if (pipeline.finished) {
                    return;
                }
                if (pipeline.candidates.size() == candidatesBefore) {
                    // 正文/URL 都没下载到 MIDI 时，再下载页面 HTML 提取直链兜底。
                    probeMidiPageHtml(pipeline, page, host);
                } else {
                    probeMidiPage(pipeline);
                }
            }
        };
        if (!links.isEmpty()) {
            downloadMidiLinks(pipeline, page, host, links, 0, afterLinks);
            return;
        }
        probeMidiPageHtml(pipeline, page, host);
    }

    /** bitmidi 等站点：Fetch 只返回文字、没有下载链接，但直接下载页面 HTML 能拿到 .mid 直链。 */
    private void probeMidiPageHtml(final SearchPipeline pipeline,
                                   final DeepSeekClient.SearchResult page, final String host) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            pipeline.sourceSkipReason = "超过搜索预算";
            probeMidiPage(pipeline);
            return;
        }
        WebDownloader.downloadText(page.url, WebDownloader.DEFAULT_MAX_BYTES,
                new WebDownloader.TextCallback() {
                    @Override
                    public void onSuccess(String html) {
                        List<String> htmlLinks = MidiUrlFinder.findFromHtml(html, page.url);
                        if (htmlLinks.isEmpty()) {
                            pipeline.sourceSkipReason = "页面无 .mid 下载链接";
                            probeMidiPage(pipeline);
                            return;
                        }
                        downloadMidiLinks(pipeline, page, host, htmlLinks, 0, new Runnable() {
                            @Override
                            public void run() {
                                probeMidiPage(pipeline);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        pipeline.sourceSkipReason = "页面 HTML 下载失败";
                        probeMidiPage(pipeline);
                    }
                });
    }

    private void downloadMidiLinks(final SearchPipeline pipeline,
                                   final DeepSeekClient.SearchResult page, final String host,
                                   final List<String> links, final int index,
                                   final Runnable next) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline) || index >= links.size() || index >= MAX_MIDI_PER_PAGE
                || pipeline.candidates.size() >= pipeline.maxCandidates) {
            next.run();
            return;
        }
        String url = links.get(index);
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            downloadMidiLinks(pipeline, page, host, links, index + 1, next);
            return;
        }
        WebDownloader.download(url, WebDownloader.DEFAULT_MAX_BYTES,
                new WebDownloader.DownloadCallback() {
                    @Override
                    public void onSuccess(byte[] data) {
                        if (WebDownloader.looksLikeMidi(data)) {
                            String title = resolveTitle(pipeline.songName, null);
                            ScoreDraft draft = new ScoreDraft(title, "", "",
                                    "MIDI · " + host, data, true, page.url, host, "midi-" + host);
                            addCandidate(pipeline, draft);
                        }
                        downloadMidiLinks(pipeline, page, host, links, index + 1, next);
                    }

                    @Override
                    public void onError(String message) {
                        downloadMidiLinks(pipeline, page, host, links, index + 1, next);
                    }
                });
    }

    // ------------------------------------------------------------------ TAB 阶段

    private void startTabPhase(SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        pipeline.phase = SearchPipeline.PHASE_TAB;
        pipeline.nameIndex = 0;
        pipeline.sourceIndex = 0;
        nextTabSource(pipeline);
    }

    private void nextTabSource(final SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            finish(pipeline);
            return;
        }
        if (pipeline.tabAttempted
                && pipeline.candidates.size() >= earlyThreshold(pipeline)) {
            // 已经拿到足够候选且 TAB 至少尝试过一个来源：收工，别让用户继续等。
            finish(pipeline);
            return;
        }
        if (pipeline.candidates.size() >= pipeline.maxCandidates) {
            finish(pipeline);
            return;
        }
        if (pipeline.nameIndex >= Math.min(pipeline.names.size(), MAX_NAMES_MIDI_TAB)) {
            finish(pipeline);
            return;
        }
        if (pipeline.sourceIndex >= TAB_SOURCES.length) {
            pipeline.nameIndex++;
            pipeline.sourceIndex = 0;
            nextTabSource(pipeline);
            return;
        }
        final String name = pipeline.names.get(pipeline.nameIndex);
        final String domain = TAB_SOURCES[pipeline.sourceIndex++];
        pipeline.tabAttempted = true;
        beginSource(pipeline, domain, "tab-" + domain);
        searchTabSource(pipeline, name, domain, name + " ukulele tab site:" + domain, true);
    }

    private void searchTabSource(final SearchPipeline pipeline, final String name,
                                 final String domain, final String query,
                                 final boolean allowFallback) {
        client.search(query, 5, new DeepSeekClient.SearchCallback() {
            @Override
            public void onSuccess(List<DeepSeekClient.SearchResult> results) {
                List<DeepSeekClient.SearchResult> pages = tabPages(results, domain,
                        MAX_TAB_PAGES_PER_SOURCE);
                if (pages.isEmpty() && allowFallback) {
                    searchTabSource(pipeline, name, domain, name + " site:" + domain, false);
                    return;
                }
                if (pages.isEmpty()) {
                    pipeline.sourceSkipReason = "无搜索结果";
                    endSource(pipeline);
                    nextTabSource(pipeline);
                    return;
                }
                pipeline.currentPages = pages;
                pipeline.pageIndex = 0;
                pipeline.currentDomain = domain;
                probeTabPage(pipeline);
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
                if (allowFallback) {
                    searchTabSource(pipeline, name, domain, name + " site:" + domain, false);
                    return;
                }
                pipeline.sourceSkipReason = "搜索接口失败";
                endSource(pipeline);
                nextTabSource(pipeline);
            }
        });
    }

    private void probeTabPage(final SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        if (budgetExceeded(pipeline)) {
            pipeline.sourceSkipReason = "超过搜索预算";
            endSource(pipeline);
            finish(pipeline);
            return;
        }
        if (pipeline.pageIndex >= pipeline.currentPages.size()
                || pipeline.candidates.size() >= pipeline.maxCandidates) {
            endSource(pipeline, "仅和弦/图片，未找到可解析的四行 tab");
            nextTabSource(pipeline);
            return;
        }
        final DeepSeekClient.SearchResult page =
                pipeline.currentPages.get(pipeline.pageIndex++);
        // 1) Search.content 先试：ukulele-tabs 的正文里直接带四行 tab。
        UkuleleTabParser.ParseResult fromContent = parseTabContent(page.content);
        if (fromContent != null) {
            addTabCandidate(pipeline, page, fromContent, page.content);
            probeTabPage(pipeline);
            return;
        }
        if (pipeline.candidates.size() >= 3) {
            // 候选已够多：不再为 TAB 下载整页 HTML（部分站点很慢），直接记录跳过原因。
            pipeline.sourceSkipReason = "仅和弦/图片，未找到可解析的四行 tab";
            probeTabPage(pipeline);
            return;
        }
        // 2) 正文没有 tab（或只有和弦）时下载页面 HTML 再试一次。
        WebDownloader.downloadText(page.url, WebDownloader.DEFAULT_MAX_BYTES,
                new WebDownloader.TextCallback() {
                    @Override
                    public void onSuccess(String html) {
                        UkuleleTabParser.ParseResult parsed = parseTabContent(html);
                        if (parsed != null) {
                            addTabCandidate(pipeline, page, parsed, html);
                        }
                        probeTabPage(pipeline);
                    }

                    @Override
                    public void onError(String message) {
                        probeTabPage(pipeline);
                    }
                });
    }

    private void addTabCandidate(SearchPipeline pipeline, DeepSeekClient.SearchResult page,
                                 UkuleleTabParser.ParseResult parsed, String pageText) {
        if (parsed.notes.size() < MIN_TAB_NOTES) {
            pipeline.sourceSkipReason = "tab 音符过少";
            return;
        }
        TabEventBuilder.Result arrangement = TabEventBuilder.build(TabEventBuilder.canonical(pageText));
        if (!arrangement.ok()) {
            pipeline.sourceSkipReason = "tab 编配失败";
            return;
        }
        if (arrangement.notes < MIN_SEARCH_NOTES
                || arrangement.durationMs < MIN_SEARCH_DURATION_MS) {
            pipeline.sourceSkipReason = "tab 片段过短（" + arrangement.notes + " 音/"
                    + arrangement.durationMs + "ms），已跳过";
            return;
        }
        String domain = pipeline.currentDomain;
        String tabText = TabEventBuilder.canonical(pageText);
        ScoreDraft draft = new ScoreDraft(resolveTitle(pipeline.songName, null),
                tabText, arrangement.events, "TAB · " + domain+(arrangement.estimatedRhythm?" · 节奏待校对":""), null, !arrangement.estimatedRhythm,
                page.url, domain, "tab-" + domain);
        addCandidate(pipeline, draft);
    }

    // ------------------------------------------------------------------ 结果收集 / 排序 / 结束

    private void addCandidate(SearchPipeline pipeline, ScoreDraft draft) {
        if (draft == null || pipeline.candidates.size() >= pipeline.maxCandidates) {
            return;
        }
        String key = draft.sourceUrl.isEmpty()
                ? (draft.sourceId + "|" + draft.title + "|" + noteOnCount(draft.eventsText))
                : draft.sourceUrl.toLowerCase(Locale.ROOT);
        if (!pipeline.seenKeys.add(key)) {
            return;
        }
        pipeline.candidates.add(draft);
        pipeline.sourceHadCandidate = true;
    }

    private void beginSource(SearchPipeline pipeline, String displayName, String sourceId) {
        pipeline.sourceName = displayName;
        pipeline.sourceId = sourceId;
        pipeline.sourceStartCandidates = pipeline.candidates.size();
        pipeline.sourceHadCandidate = false;
        pipeline.sourceSkipReason = null;
        addUnique(pipeline.triedSources, displayName);
    }

    private void endSource(SearchPipeline pipeline) {
        endSource(pipeline, "未找到可解析谱面");
    }

    private void endSource(SearchPipeline pipeline, String defaultReason) {
        if (pipeline.sourceHadCandidate) {
            return;
        }
        String reason = pipeline.sourceSkipReason == null ? defaultReason : pipeline.sourceSkipReason;
        addUnique(pipeline.skippedSources, pipeline.sourceName + "（" + reason + "）");
    }

    private void finish(SearchPipeline pipeline) {
        if (pipeline.finished) {
            return;
        }
        pipeline.finished = true;
        List<ScoreDraft> sorted = sortAndLimitCandidates(pipeline.candidates,
                pipeline.maxCandidates);
        if (sorted.isEmpty()) {
            pipeline.out.onError(new DeepSeekClient.AiError(0, "no_candidates",
                    buildNoCandidateMessage(pipeline.triedSources, pipeline.skippedSources), true));
            return;
        }
        pipeline.out.onSuccess(sorted, new ArrayList<String>(pipeline.triedSources),
                new ArrayList<String>(pipeline.skippedSources));
    }

    // ------------------------------------------------------------------ 候选排序 / 无结果诊断（纯函数，可单测）

    /** 候选排序 + 截断：确定性优先 → ABC 源 → TAB → MIDI → 音数接近「常见长度」的版本靠前。 */
    public static List<ScoreDraft> sortAndLimitCandidates(List<ScoreDraft> candidates, int max) {
        List<ScoreDraft> result = new ArrayList<ScoreDraft>();
        if (candidates == null) {
            return result;
        }
        Set<String> seen = new HashSet<String>();
        for (ScoreDraft draft : candidates) {
            if (draft == null) {
                continue;
            }
            String key = draft.sourceUrl.isEmpty()
                    ? (draft.sourceId + "|" + draft.title + "|" + noteOnCount(draft.eventsText))
                    : draft.sourceUrl.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                result.add(draft);
            }
        }
        // 同一来源类别内按「音数合理度」排序：优先较短的主旋律版本，超长编配/混入其它曲子的
        // ABC（例如把 abcnotation 示例页当成本曲）排后面；用户可试听后自行选更长版本。
        // 来源类别：ABC 谱库（不分站点）> TAB > MIDI，对应任务的「ABC 源优先于 TAB/MIDI」。
        Collections.sort(result, new Comparator<ScoreDraft>() {
            @Override
            public int compare(ScoreDraft left, ScoreDraft right) {
                int leftCategory = sourceCategory(left);
                int rightCategory = sourceCategory(right);
                if (leftCategory != rightCategory) {
                    return leftCategory - rightCategory;
                }
                if (left.deterministic != right.deterministic) {
                    return left.deterministic ? -1 : 1;
                }
                int leftNotes = noteOnCount(left.eventsText);
                int rightNotes = noteOnCount(right.eventsText);
                boolean leftHasEvents = leftNotes > 0;
                boolean rightHasEvents = rightNotes > 0;
                if (leftHasEvents != rightHasEvents) {
                    return leftHasEvents ? -1 : 1;
                }
                if (leftHasEvents && leftNotes != rightNotes) {
                    return leftNotes - rightNotes;
                }
                // 音数相同再按站点优先级（abcnotation > thesession > mutopia > tunearch）。
                return domainPriority(left) - domainPriority(right);
            }
        });
        int limit = max <= 0 ? DEFAULT_MAX_CANDIDATES : max;
        if (result.size() > limit) {
            return new ArrayList<ScoreDraft>(result.subList(0, limit));
        }
        return result;
    }

    private static int medianNoteCount(List<ScoreDraft> drafts) {
        List<Integer> counts = new ArrayList<Integer>();
        for (ScoreDraft draft : drafts) {
            int count = noteOnCount(draft.eventsText);
            if (count > 0) {
                counts.add(count);
            }
        }
        if (counts.isEmpty()) {
            return 0;
        }
        Collections.sort(counts);
        return counts.get(counts.size() / 2);
    }

    /** 来源类别：ABC 谱库 0、TAB 1、MIDI 2、其它 3。 */
    private static int sourceCategory(ScoreDraft draft) {
        if (draft == null) {
            return 9;
        }
        String id = draft.sourceId == null ? "" : draft.sourceId.toLowerCase(Locale.ROOT);
        if (id.startsWith("abc-")) {
            return 0;
        }
        if (id.startsWith("tab-")) {
            return 1;
        }
        if (id.startsWith("midi-")) {
            return 2;
        }
        String name = draft.sourceName == null ? "" : draft.sourceName.toLowerCase(Locale.ROOT);
        if (name.contains("abcnotation") || name.contains("thesession")
                || name.contains("mutopia") || name.contains("tunearch")) {
            return 0;
        }
        if (name.contains("ukutabs") || name.contains("ukulele")) {
            return 1;
        }
        if (name.contains("midi")) {
            return 2;
        }
        return 3;
    }

    /** 站点优先级：ABC 谱库内部用于音数相同时的排序，TAB 4 / MIDI 5 / 其它 6。 */
    private static int domainPriority(ScoreDraft draft) {
        if (draft == null) {
            return 9;
        }
        String id = draft.sourceId == null ? "" : draft.sourceId.toLowerCase(Locale.ROOT);
        String name = draft.sourceName == null ? "" : draft.sourceName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < ABC_SOURCES.length; i++) {
            if (id.contains(ABC_SOURCES[i]) || name.contains(ABC_SOURCES[i])) {
                return i;
            }
        }
        if (id.startsWith("tab-") || name.contains("ukutabs") || name.contains("ukulele")) {
            return 4;
        }
        if (id.startsWith("midi-") || name.contains("midi")) {
            return 5;
        }
        return 6;
    }

    /**
     * 无候选时的可执行诊断文案：列出已尝试来源、跳过原因，并给出下一步建议。
     * 公开 static 便于 JVM 单测。
     */
    public static String buildNoCandidateMessage(List<String> triedSources,
                                                 List<String> skippedSources) {
        StringBuilder builder = new StringBuilder(240);
        builder.append("没有在已尝试的来源里找到可直接转换的谱面。");
        if (triedSources != null && !triedSources.isEmpty()) {
            builder.append("\n已尝试：").append(join(triedSources, "、"));
        }
        if (skippedSources != null && !skippedSources.isEmpty()) {
            builder.append("\n跳过：").append(join(skippedSources, "；"));
        }
        builder.append("\n建议：① 换英文名或换个写法再搜（如「送别」→ Dreaming of Home and Mother / "
                + "Song of Farewell，「小星星」→ Twinkle Twinkle Little Star）；")
                .append("② 用「相册识谱」直接拍谱；③ 粘贴简谱/ABC/TAB 文本；④ 选择本地 MIDI 文件导入。");
        return builder.toString();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static void addUnique(List<String> values, String value) {
        if (value == null || value.isEmpty() || values.contains(value)) {
            return;
        }
        values.add(value);
    }

    private static int noteOnCount(String eventsText) {
        if (eventsText == null || eventsText.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : eventsText.split("\n")) {
            String[] fields = line.trim().split(",");
            if (fields.length == 5) {
                try {
                    int mask = Integer.parseInt(fields[1].trim());
                    int level = Integer.parseInt(fields[3].trim());
                    if (mask > 0 && level > 0) {
                        count++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 来源筛选工具

    private static List<DeepSeekClient.SearchResult> abcPages(
            List<DeepSeekClient.SearchResult> results, int max) {
        List<DeepSeekClient.SearchResult> strict = new ArrayList<DeepSeekClient.SearchResult>();
        List<DeepSeekClient.SearchResult> fallback = new ArrayList<DeepSeekClient.SearchResult>();
        if (results == null) {
            return strict;
        }
        Set<String> seen = new HashSet<String>();
        for (DeepSeekClient.SearchResult result : results) {
            if (result == null || !isHttpUrl(result.url)) {
                continue;
            }
            String key = result.url.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            if (result.authorityRank() >= MIN_AUTHORITY) {
                strict.add(result);
            } else {
                fallback.add(result);
            }
        }
        List<DeepSeekClient.SearchResult> chosen = strict.isEmpty() ? fallback : strict;
        if (chosen.size() > max) {
            return new ArrayList<DeepSeekClient.SearchResult>(chosen.subList(0, max));
        }
        return chosen;
    }

    private static List<DeepSeekClient.SearchResult> midiPages(
            List<DeepSeekClient.SearchResult> results, boolean bitmidiOnly, int max) {
        List<DeepSeekClient.SearchResult> pages = new ArrayList<DeepSeekClient.SearchResult>();
        if (results == null) {
            return pages;
        }
        Set<String> seen = new HashSet<String>();
        for (DeepSeekClient.SearchResult result : results) {
            if (result == null || !isHttpUrl(result.url)) {
                continue;
            }
            String host = hostOf(result.url).toLowerCase(Locale.ROOT);
            boolean accepted = bitmidiOnly
                    ? (host.equals("bitmidi.com") || host.endsWith(".bitmidi.com"))
                    : !host.isEmpty();
            if (!accepted) {
                continue;
            }
            String key = result.url.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            pages.add(result);
            if (pages.size() >= max) {
                break;
            }
        }
        return pages;
    }

    private static boolean isKnownMidiHost(String host) {
        for (String known : MIDI_HOSTS) {
            if (host.contains(known)) {
                return true;
            }
        }
        return false;
    }

    private static List<DeepSeekClient.SearchResult> tabPages(
            List<DeepSeekClient.SearchResult> results, String domain, int max) {
        List<DeepSeekClient.SearchResult> pages = new ArrayList<DeepSeekClient.SearchResult>();
        if (results == null) {
            return pages;
        }
        Set<String> seen = new HashSet<String>();
        String domainLower = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
        for (DeepSeekClient.SearchResult result : results) {
            if (result == null || !isHttpUrl(result.url)) {
                continue;
            }
            String host = hostOf(result.url).toLowerCase(Locale.ROOT);
            boolean domainMatch = domainLower.isEmpty() || host.contains(domainLower);
            boolean hasTabHint = UkuleleTabParser.looksLikeTab(result.content);
            if (!domainMatch && !hasTabHint) {
                continue;
            }
            String key = result.url.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            pages.add(result);
            if (pages.size() >= max) {
                break;
            }
        }
        return pages;
    }

    private static AbcParser.ParseResult parseAbcContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        String abc = AbcParser.extractAbc(content);
        if (abc.isEmpty()) {
            return null;
        }
        AbcParser.ParseResult parsed = AbcParser.parse(abc);
        return parsed.ok() ? parsed : null;
    }

    private static boolean hasCopyrightOnly(String content) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("so no music is displayed")
                || lower.contains("no music is displayed here");
    }

    private static UkuleleTabParser.ParseResult parseTabContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        if (!UkuleleTabParser.looksLikeTab(content)) {
            return null;
        }
        UkuleleTabParser.ParseResult parsed = UkuleleTabParser.parse(content);
        return parsed.ok() ? parsed : null;
    }

    private static boolean looksLikeMidiUrl(String url) {
        if (url == null) {
            return false;
        }
        String clean = url.toLowerCase(Locale.ROOT);
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        return clean.endsWith(".mid") || clean.endsWith(".midi");
    }

    // ------------------------------------------------------------------ 曲名别名

    private interface AliasCallback {
        void onAliases(List<String> aliases);
    }

    private void requestAliases(final String songName, final AliasCallback callback) {
        List<DeepSeekClient.ChatMessage> history = new ArrayList<DeepSeekClient.ChatMessage>();
        history.add(DeepSeekClient.ChatMessage.user("歌名：" + songName));
        client.chat(ALIAS_SYSTEM_PROMPT, history, false, ALIAS_MAX_TOKENS, false,
                LOW_TEMPERATURE, new DeepSeekClient.ChatCallback() {
                    @Override
                    public void onSuccess(String text) {
                        callback.onAliases(parseAliases(text, songName));
                    }

                    @Override
                    public void onError(DeepSeekClient.AiError error) {
                        callback.onAliases(Collections.<String>emptyList());
                    }
                });
    }

    /** 解析别名输出（纯函数，可单测）。 */
    public static List<String> parseAliases(String raw, String originalName) {
        List<String> aliases = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return aliases;
        }
        String text = stripCodeFences(raw);
        String[] parts = text.split("[\\n\\r,，;；、]+|\\s+/\\s+");
        Set<String> seen = new HashSet<String>();
        String original = originalName == null ? "" : originalName.trim();
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) {
                continue;
            }
            value = value.replaceAll("^\\d+[.、)．]\\s*", "").trim();
            value = value.replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").trim();
            boolean hasAsciiLetter = false;
            boolean hasCjk = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    hasAsciiLetter = true;
                }
                if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF')) {
                    hasCjk = true;
                }
            }
            if (!hasAsciiLetter || hasCjk || value.length() > 80) {
                continue;
            }
            if (value.equalsIgnoreCase(original)) {
                continue;
            }
            if (value.toLowerCase(Locale.ROOT).matches("^(error|unknown|none|n/?a|无法确定|无)$")) {
                continue;
            }
            if (seen.add(value.toLowerCase(Locale.ROOT)) && aliases.size() < MAX_ALIASES) {
                aliases.add(value);
            }
        }
        return aliases;
    }

    private static List<String> buildNameList(String original, List<String> aliases) {
        List<String> names = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        boolean originalHasCjk = original != null && containsNonAscii(original);
        if (originalHasCjk && aliases != null && !aliases.isEmpty()) {
            // 中文原名在英文 ABC/MIDI/TAB 站点上搜索命中率极低，还会浪费 90s 预算；
            // 有别名时先搜别名，原名放最后兜底（命中率不变，速度快很多）。
            for (String alias : aliases) {
                addName(names, seen, alias);
            }
            addName(names, seen, original);
        } else {
            addName(names, seen, original);
            if (aliases != null) {
                for (String alias : aliases) {
                    addName(names, seen, alias);
                }
            }
        }
        return names;
    }

    private static void addName(List<String> names, Set<String> seen, String value) {
        if (value == null) {
            return;
        }
        String name = value.trim();
        if (name.isEmpty() || names.size() >= 1 + MAX_ALIASES) {
            return;
        }
        if (seen.add(name.toLowerCase(Locale.ROOT))) {
            names.add(name);
        }
    }

    // ------------------------------------------------------------------ 文本 / 图片的 AI 转写

    private void requestJianpuRewrite(final String titleHint, final String text,
                                      final boolean thinking, final DraftCallback out) {
        List<DeepSeekClient.ChatMessage> history = new ArrayList<DeepSeekClient.ChatMessage>();
        history.add(DeepSeekClient.ChatMessage.user(buildRewriteUserMessage(titleHint, text)));
        client.chat(JIANPU_REWRITE_SYSTEM_PROMPT, history, false, TRANSCRIBE_MAX_TOKENS, thinking,
                LOW_TEMPERATURE, new DeepSeekClient.ChatCallback() {
                    @Override
                    public void onSuccess(String raw) {
                        String answer = stripCodeFences(raw);
                        String errorReason = findErrorMessage(answer);
                        if (!errorReason.isEmpty()) {
                            out.onError(conversionError(errorReason));
                            return;
                        }
                        JianpuParser.ParseResult parsed = JianpuParser.parse(answer);
                        if (parsed.ok()) {
                            deliverArrangement(resolveTitle(titleHint, null), answer, parsed.notes,
                                    false, "AI 转写简谱（请校对）", "", null, out);
                            return;
                        }
                        if (thinking) {
                            requestJianpuRewrite(titleHint, text, false, out);
                            return;
                        }
                        out.onError(conversionError(parsed.error == null
                                ? "AI 转写结果无法解析成简谱，请手动校对转录内容"
                                : "AI 转写结果无法解析：" + parsed.error));
                    }

                    @Override
                    public void onError(DeepSeekClient.AiError error) {
                        if (thinking && "empty_response".equals(error == null ? null : error.code)) {
                            requestJianpuRewrite(titleHint, text, false, out);
                            return;
                        }
                        out.onError(error);
                    }
                });
    }

    private static final int IMAGE_STAGE_CLASSIFY = 0;
    private static final int IMAGE_STAGE_NORMAL = 1;
    private static final int IMAGE_STAGE_STRICT = 2;
    private static final int IMAGE_STAGE_CHORDS = 3;
    private static final int IMAGE_STAGE_STAFF = 4;
    private static final int IMAGE_STAGE_NUMERIC = 5;
    private static final int IMAGE_STAGE_NUMERIC_PART = 6;

    /**
     * 相册识谱统一入口：先做一次极便宜的「类型判定」，再按 jianpu / tab / chords / staff
     * 走对应提示词。这样弹唱谱不会被硬转成四线数字（实测会退化成重复 0 的假 TAB），
     * 五线谱也会明确进入 ABC 转写而不是伪装成简谱。
     */
    private void requestImageJianpu(final String titleHint, final byte[] image, final String mime,
                                    final boolean thinking, final DraftCallback out,
                                    final int stage) {
        final String userText = buildImageUserMessage(titleHint);
        String systemPrompt = imagePromptForStage(stage);
        if(stage!=IMAGE_STAGE_CLASSIFY)systemPrompt+="\n逐个保留全部可见音符、休止、八度和拍号，不概括、不补编。看不清的数字写?。"
            +"TAB若能明确读出符干/符尾时值，请另给durations数组：按全部小节的发音列顺序，每列一个四分音符拍数（1、0.5、0.25、1.5等），和弦同列仅算一次。"
            +"若只有数字横线而无可靠时值依据，durations留空数组，禁止按排版猜时值。和弦谱另给meter拍号，pattern可用D/U和-空拍。";

        if(stage!=IMAGE_STAGE_CLASSIFY)systemPrompt += "\n逐行读取这张谱面的全部可见内容，禁止用重复模式概括或补写看不清的音符。保留原调、拍号、休止、附点、八度点和小节顺序；无法辨认处用?，不要猜音。和弦谱返回meter拍号。";
        int maxTokens = stage == IMAGE_STAGE_CLASSIFY ? 200 : IMAGE_MAX_TOKENS;
        client.chatWithImage(systemPrompt, userText, image, mime, true, maxTokens, thinking,
                LOW_TEMPERATURE, new DeepSeekClient.ChatCallback() {
                    @Override
                    public void onSuccess(String raw) {
                        if (stage == IMAGE_STAGE_CLASSIFY) {
                            requestImageJianpu(titleHint, image, mime, true, out,
                                    routeImageStage(raw));
                            return;
                        }
                        int result = handleImageAnswer(raw, titleHint, out, stage);
                        if (result != IMAGE_RETRY) {
                            return;
                        }
                        if (thinking) {
                            if (stage == IMAGE_STAGE_NORMAL) {
                                requestImageJianpu(titleHint, image, mime, false, out,
                                        IMAGE_STAGE_STRICT);
                            } else if (stage == IMAGE_STAGE_STRICT) {
                                // 严格 TAB 也失败：很多弹唱谱会被误判成 TAB，最后再试和弦谱提示。
                                requestImageJianpu(titleHint, image, mime, false, out,
                                        IMAGE_STAGE_CHORDS);
                            } else {
                                requestImageJianpu(titleHint, image, mime, false, out, stage);
                            }
                            return;
                        }
                        out.onError(conversionError(imageStageError(stage)));
                    }

                    @Override
                    public void onError(DeepSeekClient.AiError error) {
                        if(error!=null&&(error.httpCode==401||error.httpCode==402||error.httpCode==403||"cancelled".equals(error.code))){out.onError(error);return;}
                        if (stage == IMAGE_STAGE_CLASSIFY) {
                            // 类型判定失败不阻塞：直接走通用提示词。
                            requestImageJianpu(titleHint, image, mime, true, out, IMAGE_STAGE_NORMAL);
                            return;
                        }
                        if (thinking && "empty_response".equals(error == null ? null : error.code)) {
                            requestImageJianpu(titleHint, image, mime, false, out, stage);
                            return;
                        }
                        out.onError(error);
                    }
                });
    }

    private static int routeImageStage(String raw) {
        String answer = stripCodeFences(raw);
        try {
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(answer));
            String type = MiniJson.text(root, "type", "").toLowerCase(Locale.ROOT);
            if (type.contains("staff") || type.contains("五线")) {
                return IMAGE_STAGE_STAFF;
            }
            if(type.contains("jianpu")||type.contains("简谱"))return IMAGE_STAGE_NUMERIC;
            if (type.contains("chord") || type.contains("弹唱") || type.contains("和弦")) {
                return IMAGE_STAGE_CHORDS;
            }
        } catch (Throwable ignored) {
            // 判定结果不是 JSON 时走通用提示词兜底。
        }
        return IMAGE_STAGE_NORMAL;
    }

    private static String imagePromptForStage(int stage) {
        switch (stage) {
            case IMAGE_STAGE_NUMERIC_PART:
            case IMAGE_STAGE_NUMERIC:
                return IMAGE_NUMERIC_PROMPT;
            case IMAGE_STAGE_CLASSIFY:
                return IMAGE_CLASSIFY_PROMPT;
            case IMAGE_STAGE_STRICT:
                return IMAGE_RETRY_SYSTEM_PROMPT;
            case IMAGE_STAGE_CHORDS:
                return IMAGE_CHORDS_SYSTEM_PROMPT;
            case IMAGE_STAGE_STAFF:
                return IMAGE_STAFF_SYSTEM_PROMPT;
            case IMAGE_STAGE_NORMAL:
            default:
                return IMAGE_SYSTEM_PROMPT;
        }
    }

    private static String imageStageError(int stage) {
        switch (stage) {
            case IMAGE_STAGE_STAFF:
                return "识别到五线谱，但没能转写成可解析的 ABC 谱。"
                        + "建议换一张清晰、正面拍摄的五线谱照片，或手动编辑转录后再解析。";
            case IMAGE_STAGE_CHORDS:
                return "图片识别到和弦图/弹唱谱，但没能读出可用的和弦进行。"
                        + "可以换一张更清晰的照片，或手动编辑转录后再解析。";
            default:
                return "图片识别不到可解析的简谱/四线 TAB/五线谱。"
                        + "请换一张更清晰、正面拍摄的谱面照片；也可以手动编辑转录内容后再解析。";
        }
    }

    private static final int IMAGE_HANDLED = 0;
    private static final int IMAGE_OK = 1;
    private static final int IMAGE_RETRY = 2;

    private int handleImageAnswer(String raw, String titleHint, DraftCallback out, int stage) {
        String answer = stripCodeFences(raw);
        Map<String, Object> root = null;
        try {
            root = MiniJson.asObject(MiniJson.parse(answer));
        } catch (IllegalArgumentException ignored) {
            // 模型偶尔无视 JSON 模式直接输出简谱/TAB，下面兜底。
        }
        if(root==null&&stage==IMAGE_STAGE_NUMERIC_PART){out.onSuccess(new ScoreDraft(titleHint,answer,"","逐行转录待合并"));return IMAGE_OK;}
        if (root != null) {
            String errorReason = MiniJson.text(root, "error", "").trim();
            if (!errorReason.isEmpty()) {
                out.onError(conversionError(errorReason));
                return IMAGE_HANDLED;
            }
            String title = resolveTitle(titleHint, MiniJson.text(root, "title", ""));
            int bpm = MiniJson.integer(root, "bpm", 96);
            String format = MiniJson.text(root, "format", "").trim().toLowerCase(Locale.ROOT);
            String abc = firstNonEmpty(
                    MiniJson.text(root, "abc", ""),
                    MiniJson.text(root, "abcText", ""),
                    MiniJson.text(root, "abcNotation", ""));
            if (stage == IMAGE_STAGE_STAFF || format.contains("staff") || !abc.trim().isEmpty()) {
                // 五线谱：视觉模型只负责转写成单声部 ABC，音高/时值仍由 AbcParser 确定性解析。
                if (!abc.trim().isEmpty()) {
                    String block = AbcParser.extractAbc(abc);
                    if (block.isEmpty()) {
                        block = abc.trim();
                    }
                    if(!java.util.regex.Pattern.compile("(?m)^Q:").matcher(block).find()){
                        String tempo="Q:1/4="+Math.max(20,Math.min(400,bpm));
                        if(java.util.regex.Pattern.compile("(?m)^X:").matcher(block).find())block=block.replaceFirst("(?m)^X:[^\\n]*", "$0\n"+tempo);
                        else block=tempo+"\n"+block;
                    }
                    AbcParser.ParseResult parsedAbc = AbcParser.parse(block);
                    if (parsedAbc.ok()) {
                        ScoreArranger.Arrangement arrangement =
                                ScoreArranger.arrange(parsedAbc.notes);
                        if (arrangement.ok()) {
                            out.onSuccess(new ScoreDraft(
                                    resolveTitle(title, parsedAbc.title), block,
                                    arrangement.eventsText, "五线谱 · ABC 转录（请校对）",
                                    null, false, "", arrangement.skipped));
                            return IMAGE_OK;
                        }
                    }
                }
                return IMAGE_RETRY;
            }
            String tab = firstNonEmpty(
                    MiniJson.text(root, "tab", ""),
                    MiniJson.text(root, "tabText", ""),
                    MiniJson.text(root, "tabs", ""));
            List<Object> durations=MiniJson.array(root,"durations");
            if(durations!=null&&!durations.isEmpty()){
                StringBuilder values=new StringBuilder();
                for(Object duration:durations){if(values.length()>0)values.append(',');values.append(String.valueOf(duration));}
                tab="DURATIONS:"+values+"\n"+tab;
            }
            String jianpu = firstNonEmpty(
                    MiniJson.text(root, "jianpu", ""),
                    MiniJson.text(root, "jianpuText", ""),
                    MiniJson.text(root, "notes", ""));
            String writtenKey=MiniJson.text(root,"key","").replace("1=","").trim();
            String writtenMeter=MiniJson.text(root,"meter","").trim();
            if(!jianpu.isEmpty()){
                if(writtenKey.matches("[A-Ga-g][#b]?")&&!jianpu.matches("(?s).*1\\s*=.*"))jianpu="1="+writtenKey+"\n"+jianpu;
                if(writtenMeter.matches("[1-9]/(?:2|4|8|16)")&&!java.util.regex.Pattern.compile("(?m)^\\d+/\\d+\\s*$").matcher(jianpu).find())jianpu=writtenMeter+"\n"+jianpu;
            }
            String recognized=firstNonEmpty(tab,jianpu);
            if(recognized.contains("?")||recognized.contains("…")){
                out.onSuccess(new ScoreDraft(title,recognized,"","待校对 · 存在未确定音符",null,false,""));return IMAGE_OK;
            }
            String chordsText = MiniJson.text(root, "chordsText", "");
            List<Object> chordsArray = MiniJson.array(root, "chords");
            boolean chordsMode = format.contains("chord")
                    || (chordsArray != null && !chordsArray.isEmpty())
                    || (chordsArray == null && !chordsText.trim().isEmpty());
            if (chordsMode) {
                // 弹唱谱：图和弦 + 节奏型 → 用 App 内置和弦库确定性合成扫弦事件，
                // 不要求模型硬转四线数字（实测数字转录很容易退化成重复 0 的假 TAB）。
                if (deliverImageChords(title, root, chordsArray, chordsText, bpm, out)) {
                    return IMAGE_OK;
                }
            }
            boolean tabPreferred = format.contains("tab")
                    || (!tab.trim().isEmpty() && (format.isEmpty() || jianpu.trim().isEmpty()));
            // 四线谱 TAB：物化为 "G|.../C|.../E|.../A|..." 文本后用确定性解析器。
            // 必带 BPM（图片左上角 ♩=60 之类），否则解析器会按默认 120 把时值算短。
            if (tabPreferred && !tab.trim().isEmpty()) {
                if (deliverImageTab(title, tab, bpm, out)) {
                    return IMAGE_OK;
                }
            }
            if (!jianpu.trim().isEmpty()) {
                String jianpuText = "BPM:" + Math.max(20, Math.min(400, bpm)) + "\n" + jianpu.trim();
                JianpuParser.ParseResult parsed = JianpuParser.parse(jianpuText);
                if(stage==IMAGE_STAGE_NUMERIC_PART){out.onSuccess(new ScoreDraft(title,jianpuText,"","简谱分段 · 待合并"));return IMAGE_OK;}
                if (parsed.ok()) {deliverJianpu(title,jianpuText,false,"相册识谱 · 简谱AI转录请校对",out);return IMAGE_OK;}
                out.onSuccess(new ScoreDraft(title,jianpuText,"","符号待校对："+parsed.error));return IMAGE_OK;
            }
            if (!tab.trim().isEmpty()) {
                if (deliverImageTab(title, tab, bpm, out)) {
                    return IMAGE_OK;
                }
            }
            return IMAGE_RETRY;
        }
        // 非 JSON：先看是否像四行 tab（TAB 数字/竖线多），再按简谱兜底。
        if (UkuleleTabParser.looksLikeTab(answer)) {
            if (deliverImageTab(resolveTitle(titleHint, null),
                    UkuleleTabParser.extractTabText(answer), 120, out)) {
                return IMAGE_OK;
            }
        }
        JianpuParser.ParseResult direct = JianpuParser.parse(answer);
        if (direct.ok()) {
            deliverArrangement(resolveTitle(titleHint, null), answer, direct.notes, false,
                    "相册识谱 · AI 转录（请校对）", "", null, out);
            return IMAGE_OK;
        }
        if (deliverImageTab(resolveTitle(titleHint, null),
                UkuleleTabParser.extractTabText(answer), 120, out)) {
            return IMAGE_OK;
        }
        return IMAGE_RETRY;
    }

    /** Preserve original strings; a short exercise is valid. Missing rhythm stays explicitly estimated. */
    private boolean deliverImageTab(String title, String tab, int bpm, DraftCallback out) {
        if(tab==null||tab.trim().isEmpty())return false;
        String transcription="BPM:"+Math.max(20,Math.min(400,bpm<=0?96:bpm))+"\n"+tab.trim();
        TabEventBuilder.Result result=TabEventBuilder.build(transcription);
        if(!result.ok())return false;
        out.onSuccess(new ScoreDraft(title,transcription,result.events,result.estimatedRhythm?"TAB原指法 · 等时值节奏草稿，需校对":"TAB原指法与时值 · 请校对",null,false,""));
        return true;
    }

    /**
     * 弹唱谱（和弦图 + 节奏型）→ 用 App 内置 {@link ChordLibrary} 确定性合成扫弦事件。
     *
     * <p>为什么需要这条路：用户拍的「送别 / 曹操」是弹唱谱（和弦图 + 扫弦节奏 + 四线指法），
     * 让视觉模型硬转四线数字会退化成「重复 0」的假 TAB（实测只出 4 秒）。改为让模型只识别
     * 和弦进行（C/G/Am/Em/F…）+ BPM/节奏型，音高与节奏由 App 的和弦库确定性生成，时长可到整首。
     */
    private boolean deliverImageChords(String title, Map<String, Object> root,
                                        List<Object> chordsArray, String chordsText, int bpm,
                                        DraftCallback out) {
        List<String> names = new ArrayList<String>();
        if (chordsArray != null) {
            for (Object item : chordsArray) {
                String text = MiniJson.text(item, "").trim();
                if (!text.isEmpty()) {
                    names.add(text);
                }
            }
        }
        if (names.isEmpty() && chordsText != null && !chordsText.trim().isEmpty()) {
            String[] parts = chordsText.trim().split("[\\s,，、|]+");
            for (String part : parts) {
                String value = part == null ? "" : part.trim();
                if (!value.isEmpty()) {
                    names.add(value);
                }
            }
        }
        if (names.isEmpty()) {
            return false;
        }
        int safeBpm=Math.max(20,Math.min(400,bpm<=0?96:bpm));
        String transcription=ChordScoreParser.canonical(names,safeBpm,
                MiniJson.text(root,"meter","4/4"),MiniJson.text(root,"pattern","D D D D"));
        ChordScoreParser.Result result=ChordScoreParser.parse(transcription);
        out.onSuccess(new ScoreDraft(title,transcription,result.events,
                result.ok()?"和弦谱转录（请校对）":"和弦谱待校对 · "+result.error,null,false,""));
        return true;
    }

    private static void appendEventLine(StringBuilder builder, long delta, int mask, int row,
                                        int level, int flags) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(delta).append(',').append(mask).append(',').append(row).append(',')
                .append(level).append(',').append(flags);
    }

    /** 节奏型里的扫弦次数：数「下/上/u/d」笔画，读不出按 4 拍默认。 */
    private static int countStrumStrokes(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return 4;
        }
        int strokes = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '下' || c == '上' || c == 'u' || c == 'U' || c == 'd' || c == 'D') {
                strokes++;
            }
        }
        if (strokes <= 0) {
            return 4;
        }
        return Math.max(1, Math.min(16, strokes));
    }

    private static Map<String, ChordLibrary.Chord> chordMap() {
        Map<String, ChordLibrary.Chord> map = new HashMap<String, ChordLibrary.Chord>();
        try {
            ChordLibrary.Chord[] all = ChordLibrary.all();
            if (all != null) {
                for (ChordLibrary.Chord chord : all) {
                    if (chord != null && chord.name != null) {
                        map.put(normalizeChordName(chord.name), chord);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 和弦库不可用时该模式会返回 false，走简谱/TAB 兜底，不影响主流程。
        }
        return map;
    }

    private static String normalizeChordName(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace(" ", "").replace("\t", "");
        int slash = value.indexOf('/');
        if (slash > 0) {
            value = value.substring(0, slash);
        }
        if (value.isEmpty()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private boolean deliverJianpu(String title,String text,boolean deterministic,String source,DraftCallback out){
        JianpuParser.ParseResult parsed=JianpuParser.parse(text);if(!parsed.ok()){out.onError(conversionError(parsed.error));return false;}
        String normalized=parsed.normalizedText.isEmpty()?text:parsed.normalizedText;
        String label=source+" · 1="+parsed.key+" · "+parsed.meter;
        if(parsed.barDraft)label+=" · 按小节均分的节奏草稿，不等于原谱时值";
        else if(parsed.measureIssues>0)label+=" · "+parsed.measureIssues+"个小节时值超拍，可能缺少减时线；可选小节均分草稿";
        if(parsed.visibleOrder)label+=" · 按可见顺序，不展开反复";
        ScoreArranger.Arrangement mapped=ScoreArranger.arrange(parsed.notes);
        if(mapped.skipped>0){out.onSuccess(new ScoreDraft(title,normalized,"",label+" · 有低音/高音超出乐器音域，请选择整首升八度或校对",null,false,"",mapped.skipped));return true;}
        return deliverArrangement(title,normalized,parsed.notes,deterministic&&!parsed.rhythmUncertain,label,"",null,out);
    }

    /** Known numeric layout: skip classification; each crop contains its original header. */
    public void convertNumberedImage(String title,byte[] image,DraftCallback callback){convertNumberedImage(title,image,"",callback);}
    public void convertNumberedImage(String title,byte[] image,String hint,DraftCallback callback){
        if(client==null){callback.onError(inputError("AI渠道不可用"));return;}
        DraftCallback checked=new OnceDraft(new DraftCallback(){
            public void onSuccess(ScoreDraft d){String text=hint.isEmpty()?d.transcription:NumberedHint.fuse(d.transcription,hint);
                if(!hint.isEmpty()&&text.contains("?")){repairNumberedImage(title,image,hint,text,callback);return;}
                callback.onSuccess(new ScoreDraft(title,text,"",hint.isEmpty()?"逐行简谱转录":"AI读音符 + 本机读取八度与节奏（请核对）"));}
            public void onError(DeepSeekClient.AiError e){callback.onError(e);}
        });
        String prompt=IMAGE_NUMERIC_PROMPT+"\n本机几何读取初稿如下（可能读错数字，请逐字对照图片修正；/2为半拍，/4四分之一拍；逗号为低八度，不是附点）。不要概括，不要补齐不在图里的内容。连线两端的印刷数字都必须逐一保留，绝对不能提前把相同音合并。密排的33是两个数字，561是三个数字。初稿共"+NumberedHint.count(hint)+"个印刷数字（不含谱头），请逐个核对是否有漏读。不要把小字装饰音当作主音；认不清请写?：\n"+hint;
        client.chatWithImage(prompt,buildImageUserMessage(title),image,"image/jpeg",true,4000,false,LOW_TEMPERATURE,new DeepSeekClient.ChatCallback(){
            public void onSuccess(String raw){if(handleImageAnswer(raw,title,checked,IMAGE_STAGE_NUMERIC_PART)==IMAGE_RETRY)checked.onError(conversionError("该段未返回可校对简谱"));}
            public void onError(DeepSeekClient.AiError e){checked.onError(e);}
        });
    }

    /** One bounded reread for dense repeated glyphs. A failed reread retains the editable original. */
    private void repairNumberedImage(String title,byte[] image,String hint,String fallback,DraftCallback out){
        int count=NumberedHint.count(hint);
        String prompt="逐字校对这张简谱的下方一行（上方只是谱头）。上次出现漏读或装饰音不明。只读正文大号的主旋律数字，含0休止。小字倚音不纳入主音。密排33、21、561必须分别保留每个印刷数字；延音连线的两端数字也分别保留，不提前合并。不要按歌名回忆，不要照抄候选。这里只核对级数，不输出节奏或八度。几何候选有"+count+"个主音槽；若实际不符，请按图片如实输出不同数量，不能凑数。不确定的位置写?。只输出JSON {\"degrees\":[\"5\",\"3\",\"3\"],\"ornaments\":\"可选备注\"}。候选：\n"+hint;
        client.chatWithImage(prompt,buildImageUserMessage(title),image,"image/jpeg",true,2000,false,LOW_TEMPERATURE,new DeepSeekClient.ChatCallback(){
            public void onSuccess(String raw){
                String text=fallback;
                try{Map<String,Object> root=MiniJson.asObject(MiniJson.parse(stripCodeFences(raw)));List<Object> values=MiniJson.array(root,"degrees");
                    if(values!=null&&values.size()==count){StringBuilder notes=new StringBuilder();boolean valid=true;
                        for(Object value:values){String n=value instanceof Number?String.valueOf(((Number)value).intValue()):String.valueOf(value);if(!n.matches("[#b]?[0-7][#b]?")){valid=false;break;}notes.append(n).append(' ');}
                        if(valid)text=NumberedHint.fuse(notes.toString(),hint);
                    }
                }catch(RuntimeException ignored){}
                out.onSuccess(new ScoreDraft(title,text,"","主旋律校读草稿 · 小字装饰音另行校对"));
            }
            public void onError(DeepSeekClient.AiError error){if(error.code.contains("cancel"))out.onError(error);else out.onSuccess(new ScoreDraft(title,fallback,"","局部重读未完成，请校对标记"));}
        });
    }

    // ------------------------------------------------------------------ 单结果交付

    private boolean deliverArrangement(String title, String transcription,
                                       List<AbcParser.ParsedNote> notes, boolean deterministic,
                                       String source, String sourceUrl, byte[] midiBytes,
                                       DraftCallback out) {
        ScoreArranger.Arrangement arrangement = ScoreArranger.arrange(notes);
        if (!arrangement.ok()) {
            out.onError(conversionError(arrangement.error == null
                    ? "无法把音符编配成可演奏的谱面" : arrangement.error));
            return false;
        }
        if(transcription.contains("?")||transcription.contains("…")){
            out.onSuccess(new ScoreDraft(title,transcription,"","转录有未确定音符，请补齐",null,false,sourceUrl));return false;
        }
        out.onSuccess(new ScoreDraft(title, transcription, arrangement.eventsText, source,
                midiBytes, deterministic, sourceUrl, arrangement.skipped));
        return true;
    }

    // ------------------------------------------------------------------ 旧静态 API（兼容）

    /** 去掉模型输出外层的 Markdown 代码块围栏。 */
    public static String stripCodeFences(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstNewline = value.indexOf('\n');
        if (firstNewline < 0) {
            String inner = value.substring(3);
            int languageEnd = 0;
            while (languageEnd < inner.length() && Character.isLetter(inner.charAt(languageEnd))) {
                languageEnd++;
            }
            inner = inner.substring(languageEnd).trim();
            if (inner.endsWith("```")) {
                inner = inner.substring(0, inner.length() - 3);
            }
            return inner.trim();
        }
        String body = value.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    /** 提取严格合法的旧版事件行（仅兼容旧调用；3.0 新流程不依赖自由事件）。 */
    public static String extractEvents(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String text = stripCodeFences(raw);
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String normalized = normalizeEventLine(line);
            if (normalized == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(normalized);
        }
        return builder.toString();
    }

    /** 旧搜索关键词生成（兼容旧引用）。 */
    public static String buildSearchQuery(String songName) {
        if (songName == null) {
            return "";
        }
        String name = songName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return "";
        }
        return name + " 尤克里里 简谱 四线谱 ukulele tab";
    }

    private static String normalizeEventLine(String line) {
        if (line == null) {
            return null;
        }
        String candidate = line.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.startsWith("-") || candidate.startsWith("*") || candidate.startsWith("•")) {
            candidate = candidate.substring(1).trim();
        }
        String[] parts = candidate.split(",", -1);
        if (parts.length != 5) {
            return null;
        }
        int[] values = new int[5];
        for (int i = 0; i < 5; i++) {
            try {
                values[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int delta = values[0];
        int mask = values[1];
        int row = values[2];
        int level = values[3];
        int flags = values[4];
        if (delta < 0 || delta > 3600000 || mask < 1 || mask > 15 || row < 0 || row > 17
                || level < 0 || level > 255 || flags < 0 || flags > 255) {
            return null;
        }
        return delta + "," + mask + "," + row + "," + level + "," + flags;
    }

    private static String findErrorMessage(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("ERROR:") || trimmed.startsWith("ERROR：")) {
                int colon = trimmed.indexOf(':');
                String reason = trimmed.substring(colon >= 0 ? colon + 1 : 6).trim();
                return reason.isEmpty() ? "无法把内容转换成演奏事件" : reason;
            }
            if (trimmed.startsWith("错误：")) {
                String reason = trimmed.substring(3).trim();
                return reason.isEmpty() ? "无法把内容转换成演奏事件" : reason;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 提示词 / 工具

    private static String buildRewriteUserMessage(String titleHint, String text) {
        String name = titleHint == null ? "" : titleHint.trim();
        return "曲名提示：" + (name.isEmpty() ? "（未知）" : name)
                + "\n请把下面的内容转写成规范简谱文本：\n\n" + text;
    }

    private static String buildImageUserMessage(String titleHint) {
        String name = titleHint == null ? "" : titleHint.trim();
        return "这是一张尤克里里曲谱照片" + (name.isEmpty() ? "" : "，歌名可能是「" + name + "」")
                + "。图片可能是数字简谱，也可能是四线谱 TAB（4 条横线 + 品位数字）；"
                + "请按系统要求识别并只输出 JSON（支持 format=jianpu 或 format=tab）。";
    }

    private static String resolveTitle(String titleHint, String parserTitle) {
        String fromParser = parserTitle == null ? "" : parserTitle.trim();
        if (!fromParser.isEmpty()) {
            return fromParser;
        }
        String hint = titleHint == null ? "" : titleHint.trim();
        return hint.isEmpty() ? "AI 导入曲谱" : hint;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static boolean containsNonAscii(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            java.net.URL parsed = new java.net.URL(url);
            String host = parsed.getHost();
            return host == null ? "" : host;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static DeepSeekClient.AiError inputError(String message) {
        return new DeepSeekClient.AiError(0, "invalid_input", message, false);
    }

    private static DeepSeekClient.AiError conversionError(String message) {
        return new DeepSeekClient.AiError(0, "conversion_failed",
                message == null || message.trim().isEmpty()
                        ? "AI 没能把内容转换成可导入的曲谱" : message.trim(), false);
    }

    private static DeepSeekClient.AiError notReadyError() {
        return new DeepSeekClient.AiError(0, "not_ready", "AI 客户端未初始化，请重新打开页面再试", false);
    }

    private static boolean budgetExceeded(SearchPipeline pipeline) {
        return pipeline != null
                && System.currentTimeMillis() - pipeline.startedAt > TOTAL_BUDGET_MS;
    }

    private static int earlyThreshold(SearchPipeline pipeline) {
        return Math.min(pipeline.maxCandidates, EARLY_FINISH_CANDIDATES);
    }

    // ------------------------------------------------------------------ 管线状态 / 一次性回调

    private static final class SearchPipeline {
        static final int PHASE_ABC = 0;
        static final int PHASE_MIDI = 1;
        static final int PHASE_TAB = 2;

        final String songName;
        final int maxCandidates;
        final CandidatesCallback out;
        final long startedAt = System.currentTimeMillis();
        final List<ScoreDraft> candidates = new ArrayList<ScoreDraft>();
        final List<String> triedSources = new ArrayList<String>();
        final List<String> skippedSources = new ArrayList<String>();
        final Set<String> seenKeys = new HashSet<String>();

        List<String> names = new ArrayList<String>();
        int nameIndex;
        int sourceIndex;
        int phase = PHASE_ABC;
        boolean finished;
        boolean midiAttempted;
        boolean tabAttempted;

        // 当前来源的处理状态
        String sourceName = "";
        String sourceId = "";
        int sourceStartCandidates;
        boolean sourceHadCandidate;
        String sourceSkipReason;
        List<DeepSeekClient.SearchResult> currentPages = new ArrayList<DeepSeekClient.SearchResult>();
        int pageIndex;
        String currentDomain = "";
        String currentName = "";

        SearchPipeline(String songName, int maxCandidates, CandidatesCallback out) {
            this.songName = songName;
            this.maxCandidates = maxCandidates;
            this.out = out;
        }
    }

    private static final class OnceDraft implements DraftCallback {
        private final DraftCallback target;
        private final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        OnceDraft(DraftCallback target) {
            this.target = target != null ? target : new DraftCallback() {
                @Override
                public void onSuccess(ScoreDraft draft) {
                }

                @Override
                public void onError(DeepSeekClient.AiError error) {
                }
            };
        }

        @Override
        public void onSuccess(ScoreDraft draft) {
            if (done.compareAndSet(false, true)) {
                target.onSuccess(draft);
            }
        }

        @Override
        public void onError(DeepSeekClient.AiError error) {
            if (done.compareAndSet(false, true)) {
                target.onError(error);
            }
        }
    }

    private static final class OnceCandidates implements CandidatesCallback {
        private final CandidatesCallback target;
        private final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        OnceCandidates(CandidatesCallback target) {
            this.target = target != null ? target : new CandidatesCallback() {
                @Override
                public void onSuccess(List<ScoreDraft> candidates, List<String> triedSources,
                                      List<String> skippedSources) {
                }

                @Override
                public void onError(DeepSeekClient.AiError error) {
                }
            };
        }

        @Override
        public void onSuccess(List<ScoreDraft> candidates, List<String> triedSources,
                              List<String> skippedSources) {
            if (done.compareAndSet(false, true)) {
                target.onSuccess(candidates, triedSources, skippedSources);
            }
        }

        @Override
        public void onError(DeepSeekClient.AiError error) {
            if (done.compareAndSet(false, true)) {
                target.onError(error);
            }
        }
    }

    // ------------------------------------------------------------------ 提示词

    private static final String IMAGE_NUMERIC_PROMPT=
        "你是数字简谱逐字转录助手，严禁依据歌名回忆或续写。图片上方是原谱头部，下方仅是待识别的若干行。只转录下方这几行，不补齐整首，不抄歌词。"
        +"先读1=调号和拍号。每个印刷数字只输出一次，密排的561要拆为5 6 1，不能看成品位或多位数。数字下方的八度点写逗号，数字上方点写撇号，右侧附点写.；一下划线写_，两条写__。"
        +"同音延音连线用~，不同音的圆弧是连奏，不合并音符。保留0休止和-延音，保留|:与:|反复。装饰小音或认不清的符号用?，不得丢弃。"
        +"只输出JSON：{\"format\":\"jianpu\",\"key\":\"D\",\"meter\":\"6/8\",\"bpm\":96,\"jianpu\":\"1=D\\n6/8\\n5. 5_ 3_ 5_ | 1_ 5,__ 6,__ 1_ |\"}。没写速度时bpm用96并保持可校对。";

    private static final String JIANPU_FORMAT_HELP =
            "规范简谱文本格式（程序会按此解析）：\n"
            + "- 可选头行：BPM:96、1=C、4/4（默认 96、1=C、4/4）；\n"
            + "- 音符用 1-7，休止用 0；用空格分隔，| 是小节线（可省略）；\n"
            + "- 升降号写在数字前或后均可：#1、1#、b3；\n"
            + "- 高八度后缀 '（可多个），低八度后缀 ,（可多个）；\n"
            + "- 时值：默认一拍；- 表示延长一拍（可多个）；_ 表示半拍、__四分之一拍；同音延音连线写~，反复用|:和:|；. 表示附点（×1.5，"
            + "可与 _ 组合）；\n"
            + "- 只输出简谱文本，不要解释、不要表头以外的文字、不要 Markdown 代码块；\n"
            + "- 无法可靠转写时只输出一行：ERROR: 中文原因。\n"
            + "示例：\nBPM:96\n1=C\n4/4\n1 1 5 5 | 6 6 5 - | 4 4 3 3 | 2 2 1 -";

    private static final String JIANPU_REWRITE_SYSTEM_PROMPT =
            "你是简谱转写助手。仅转录用户明确给出的谱面音符，分离歌词和说明，不能根据歌词或歌名编造旋律、节奏、八度。只有歌词没有音符应返回ERROR。将明确的简谱、ABC 谱、TAB 转成规范简谱文本，"
            + "供程序确定性解析和编配。\n" + JIANPU_FORMAT_HELP;

    /** 相册识谱第一步：判断曲谱类型（便宜、快，决定后续用哪套提示词）。 */
    private static final String IMAGE_CLASSIFY_PROMPT =
            "你是曲谱图片分类器。只判断图片属于哪种曲谱，输出 json："
            + "{\"type\":\"jianpu|tab|chords|staff\",\"bpm\":整数拍速}。\n"
            + "chords = 弹唱谱：图中有明显的和弦图（竖向方格按法）或成排和弦名 C/G/Am/Em/F，"
            + "配合扫弦节奏型/斜线/符干；**只要出现和弦图或成排和弦名就优先判定 chords，"
            + "无论是否同时画了四线谱**；\n"
            + "若同一旋律同时提供五线谱与TAB，优先选staff，按五线谱读音高和节奏，TAB仅供核对，不能混合两行重复读。\n"
            + "tab = 纯指弹/旋律四线谱（4 条横线 + 品位数字，没有和弦图）；\n"
            + "staff = 五线谱（5 条横线 + 音符符头）；jianpu = 数字简谱（1234567 数字）。\n"
            + "不确定时：有和弦图选 chords，有 5 条线选 staff，否则按是否有 4 条数字线选 tab/jianpu。"
            + "只输出 JSON，不要解释。";

    /** 弹唱谱：只识别和弦进行与节奏型，音高由 App 内置和弦库确定性生成。 */
    private static final String IMAGE_CHORDS_SYSTEM_PROMPT =
            "你是尤克里里弹唱谱识别助手。图片是「和弦图 + 扫弦节奏型」的弹唱谱，请只识别和弦进行，"
            + "不要尝试转写四线/五线数字。只输出 JSON：{\"title\":\"歌名\",\"format\":\"chords\","
            + "\"bpm\":整数,\"chords\":[\"C\",\"G\",\"Am\",...],\"pattern\":\"下 下上 上下上\"}。\n"
            + "要求：chords 按小节顺序逐小节列出页面上全部小节（例如 16 小节就 16 个和弦，"
            + "一个都不能少）；和弦名用标准写法 C/G/Am/Em/F/Dm/G7 等；pattern 为图上节奏型"
            + "（读不出留空串）；bpm 取 ♩=xx；读不出和弦时输出 {\"error\":\"中文原因\"}。";

    /** 五线谱：只转写成单声部 ABC，之后交给 AbcParser 做确定性解析。 */
    private static final String IMAGE_STAFF_SYSTEM_PROMPT =
            "你是五线谱转写助手。图片是标准五线谱，请把主旋律（最高声部/单声部旋律）转写成简单 ABC 谱。"
            + "只输出 JSON：{\"title\":\"歌名\",\"format\":\"staff\",\"bpm\":整数,\"abc\":\"ABC 文本\"}。\n"
            + "ABC 规范：必须含 X:1、T:标题、M:拍号、L:1/8 或 1/4、K:调号（C/G/D/F/Bb/Eb/Am/Em/Dm 等），"
            + "然后写音符；音符用 A-G/a-g（大写 C4 八度、小写 C5 八度，' 升八度、, 降八度），"
            + "时值后缀数字/斜杠，休止 z；只保留主旋律，不要和弦/伴奏/歌词；五线谱与TAB并排时只读五线谱一遍，TAB仅核对音高；从谱号、调号及第一音在线/间位置确定绝对音高，不要按熟悉曲名默写。Q:1/4=拍速必须给出。保留整页全部小节，不可省略。"
            + "看不清或无法可靠转写时输出 {\"error\":\"中文原因\"}。只输出 JSON，abc 里的换行用 \\n。";

    private static final String IMAGE_SYSTEM_PROMPT =
            "你是尤克里里曲谱识谱助手。图片可能是【数字简谱】或【四线谱 TAB】，请先判断类型：\n"
            + "1) 数字简谱：用 1234567 表示音高（带高低音点/时值线）。转写成规范简谱文本，"
            + "format=jianpu。\n"
            + "2) 四线谱 TAB：4 条横线上写品位数字（常见左侧标 G/C/E/A 或 A/E/C/G，有时只有 4 条线）。"
            + "请把 TAB 逐行转写成 4 行纯文本，format=tab：每行格式 «弦名|内容|»，弦名统一按 "
            + "G、C、E、A 四行顺序输出（若原图是 A/E/C/G，按弦名对应换算到 GCEA 顺序）；内容用原图的"
            + "数字 0-18、用 - 保持列对齐、x 表示不弹；不要翻译成简谱，也不要加和弦图内容。没有弦名字母时，常规尤克里里TAB从上到下是A/E/C/G，先确认原图行位置再转换成输出G/C/E/A；不能把第一条线当E弦。\n"
            + "【整页都要转写】谱面通常有多个小节（例如 1~16，甚至更多）：先数清图片里有几个小节，"
            + "然后逐小节全部转写，小节之间用空行分隔；每个小节固定 4 行（G/C/E/A 各一行）。"
            + "禁止用「重复模式/同上」概括，禁止只写前几个小节，数字与列位置照抄原图。\n"
            + "拍速 bpm 取图片左上角的 ♩=xx（例如 ♩=60 → 60）。\n"
            + "tab 字段示例（2 个小节，注意小节间空行）：\n"
            + "G|--0---2---3---2-|\nC|--0---0---0---0-|\nE|--0---1---0---1-|\n"
            + "A|--3---2---0---2-|\n\nG|--0---2---0---2-|\nC|--0---0---0---0-|\n"
            + "E|--0---1---0---1-|\nA|--3---2---0---2-|\n"
            + "4) 五线谱（5 条横线 + 音符符头）：把主旋律转写成简单 ABC 谱文本，format=\"staff\"，"
            + "键 \"abc\" 里放 ABC（含 X/T/M/L/K，不要和弦/伴奏）；读不出就输出 error。\n"
            + "5) 弹唱谱/和弦谱（一排和弦图如 C/G/Am/Em/F + 扫弦节奏、斜线、符干，四线数字不完整"
            + "或看不清）：输出 format=\"chords\"，键 \"chords\" 为按小节顺序的和弦名数组"
            + "（如 [\"C\",\"G\",\"Am\",\"Em\",\"F\",\"C\",\"G\",\"C\"]），\"pattern\" 为图上节奏型"
            + "（如 \"下 下上 上下上\"，读不出就留空）。绝对不要用重复数字硬凑 TAB，也不要把和弦谱"
            + "硬转成简谱。\n"
            + "只输出一个 JSON 对象（json），不要 Markdown 代码块、不要前后解释，键为：\n"
            + "{\"title\":\"歌名\",\"format\":\"jianpu / tab / chords / staff\",\"bpm\":整数拍速,"
            + "\"jianpu\":\"...\",\"tab\":\"...\",\"abc\":\"...\","
            + "\"chords\":[\"C\",\"G\",\"Am\"],\"pattern\":\"下 下上 上下上\"}\n"
            + JIANPU_FORMAT_HELP;

    /** 第一次识别不全（常见：只转写前几小节）时的严格重试提示词。 */
    private static final String IMAGE_RETRY_SYSTEM_PROMPT =
            "上一轮把曲谱照片识别成了过短或不完整的结果。请重新、严格地逐小节转写整页谱面：\n"
            + "1) 先数清图片里有多少小节（例如 1~16），输出同样数量的小节，不允许省略中间小节；\n"
            + "2) 四线谱 TAB 每个小节输出 4 行：G|...|、C|...|、E|...|、A|...|，小节之间空行分隔；"
            + "数字/列位置照抄，禁止「重复模式」「同上」概括，禁止翻译成简谱；\n"
            + "3) 弹唱谱（和弦图 + 扫弦节奏、没有完整数字）请改用 format=\"chords\" 输出和弦进行，"
            + "不要硬凑 TAB 数字；\n"
            + "4) 五线谱请把主旋律转写成 ABC，format=\"staff\"，键 \"abc\" 放 ABC 文本；\n"
            + "5) 数字谱则逐句转写成规范简谱文本；bpm 取图片上的 ♩=xx；\n"
            + "只输出 JSON：{\"title\":\"\",\"format\":\"jianpu|tab|chords|staff\",\"bpm\":整数,"
            + "\"jianpu\":\"...\",\"tab\":\"...\",\"abc\":\"...\","
            + "\"chords\":[\"C\",\"G\"],\"pattern\":\"下 下上 上下上\"}";



    private static final String ALIAS_SYSTEM_PROMPT =
            "你是曲名归一助手。用户给出一个中文（或非英文）歌名，请列出它对应的 1~3 个"
            + "英文/通用曲名，用于在 abcnotation/thesession/mutopia 等曲谱站与 MIDI/tab 站搜索。"
            + "只输出曲名本身，用逗号或换行分隔，不要编号、不要解释。优先给国际上最常用的英文标题，"
            + "不要直译（例如：小星星 → Twinkle Twinkle Little Star；两只老虎 → Frère Jacques；"
            + "铃儿响叮当 → Jingle Bells；欢乐颂 → Ode to Joy；生日快乐 → Happy Birthday to You；"
            + "送别 → Dreaming of Home and Mother, Song of Farewell；茉莉花 → Jasmine Flower；"
            + "月亮代表我的心 → The Moon Represents My Heart）。";
}
