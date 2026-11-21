package com.ukulele.monitor.ai;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.text.TextWatcher;
import android.text.Editable;
import android.graphics.BitmapFactory;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ukulele.monitor.ui.AppDialogs;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 曲谱导入全屏弹窗（3.0：多来源候选 + 试听选择）。
 *
 * <p>旧版把「网页正文 → 让模型自由编事件」当主路径，用户反馈结果不可信；2.0 起边界改为
 * <b>AI 只做搜索/识别，音高与节奏由 App 规则解析</b>。3.0 进一步把「搜曲谱」做成多来源
 * 候选列表：同时查 ABC 谱库（abcnotation/thesession/mutopia 等）、MIDI 站点与 Ukulele
 * 谱站，最多展示 6 个版本，每个候选都能先试听约 12 秒再决定用哪个，重名/不同编配不再靠猜。
 *
 * <p>结果区用绿/黄徽标区分可信度，并展示可点击来源；MIDI 草稿直接交给
 * {@link ImportHandler#onImportMidi} 用 App 内置 MIDI 解析器导入，不再经过模型生成事件。
 * 搜不到时展示诊断卡（已尝试/已跳过的来源 + 换英文名/相册/粘贴简谱/网页链接等可执行入口）。
 *
 * <p>相册选图需要 Activity 的 onActivityResult，弹窗自身收不到结果，因此通过
 * {@link ImportHandler#requestImage(ImageCallback)} 由 MainActivity 选图后回传 JPEG 字节。
 *
 * <p>取消/关闭：所有请求带递增 token，取消或关闭后迟到的网络回调会被丢弃；关闭时还会
 * 停止试听、shutdown 客户端，因此不会在 Activity 销毁后碰 UI。
 */
public final class AiScoreImportDialog {

    /** Activity 选图后回传给弹窗的图片数据。 */
    public interface ImageCallback {
        void onImage(byte[] jpegData, String mime, String name);
    }

    public interface DocumentCallback {void onDocument(ScoreImageDocument document);default void error(String message){} }
    public interface ImportResult {void completed(String error);}
    public interface PreviewObserver {void changed(boolean active,String message);}

    /** MainActivity 提供的曲库能力：事件文本摘要 + 导入；可选提供选图。 */
    public interface ImportHandler {
        /**
         * 计算事件文本的可演奏性摘要；返回 null 表示不可导入。
         */
        String describe(String eventsText);

        /** 用户点「导入到曲库」：MainActivity 负责落库并用 title 命名。 */
        void onImport(String title, String eventsText);

        /**
         * MIDI 草稿的导入入口：MainActivity 用既有 MIDI 解析器解析后落库。
         * 默认实现兜底走 onImport，保证未升级的宿主也不会崩溃。
         */
        default void onImportMidi(byte[] midiBytes, String title) {
            onImport(title, "");
        }

        /**
         * 请求 Activity 打开相册选一张谱面图片，并通过 callback 回传 JPEG 字节。
         * 默认未接入时抛 UnsupportedOperationException，弹窗会给出「请改用粘贴简谱」提示。
         */
        default void requestImage(ImageCallback callback) {
            throw new UnsupportedOperationException("requestImage() 未接入");
        }

        default void requestDocument(DocumentCallback callback){requestImage((bytes,mime,name)->callback.onDocument(new ScoreImageDocument(name,mime,bytes,java.util.Collections.singletonList(bytes),1,1)));}
        /** Opens the regular import picker; selected runs only after a file was chosen. */
        default void requestScoreFile(Runnable selected) {
            throw new UnsupportedOperationException("文件导入未接入");
        }
        default String describeMidi(byte[] bytes){return bytes!=null&&bytes.length>=14&&bytes[0]=='M'&&bytes[1]=='T'&&bytes[2]=='h'&&bytes[3]=='d'?"MIDI源文件，请试听核对":null;}
        default void importReviewed(AiScoreAssistant.ScoreDraft draft,ScoreImageDocument document,ImportResult result){
            try{if(draft.midiBytes!=null)onImportMidi(draft.midiBytes,draft.title);else onImport(draft.title,draft.eventsText);result.completed(null);}
            catch(Exception e){result.completed(e.getMessage()==null?"保存失败":e.getMessage());}
        }

        /** 试听一段文本草稿（宿主约 12 秒后自动停）；默认空实现，UI 仍显示按钮但不出声。 */
        default void previewText(String eventsText, String title) {
        }

        /** 试听一个 MIDI 候选（宿主用内置解析器合成约 12 秒）；默认空实现。 */
        default void previewMidi(byte[] midiBytes, String title) {
        }

        /** 停止当前试听：切换候选、取消或关闭弹窗时调用。 */
        default void setPreviewObserver(PreviewObserver observer){}
        default void stopPreview() {
        }
    }

    /** 四个模式的索引；顺序同时决定顶部 chip 的排列。 */
    private static final int MODE_SEARCH = 0;
    private static final int MODE_WEB_TEXT = 1;
    private static final int MODE_IMAGE = 2;
    private static final int MODE_PASTE = 3;

    /** 一次最多展示的候选数（核心契约上限 6）。 */
    private static final int MAX_CANDIDATES = 6;

    /** 宿主试听时长约 12 秒；UI 稍后一点复位按钮，避免与宿主的自动停止抢状态。 */

    private AiScoreImportDialog() {
    }

    /**
     * 打开导入弹窗。
     *
     * @param activity 宿主 Activity
     * @param creds    凭据；为 null 时内部新建
     * @param handler  曲库回调；为 null 时只能预览不能导入
     */
    public static void show(final Activity activity, final AiCredentials creds,
                            final ImportHandler handler) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            AiCredentials credentials = creds != null ? creds : new AiCredentials(activity);
            new Ui(activity, credentials, handler).open();
        } catch (Throwable ignored) {
        }
    }

    /** 内部转换器：把「怎么发起请求」与「统一的忙碌/结果/错误处理」解耦。 */
    private interface Converter {
        void start(AiScoreAssistant.DraftCallback callback);
    }

    // ------------------------------------------------------------------ 内部实现

    private static final class Ui {
        private final Activity activity;
        private final AiCredentials creds;
        private final ImportHandler handler;
        private final UiKit kit;
        private final AppTheme.Colors c;
        private final float scale;
        private final Dialog dialog;

        private boolean alive = true;
        private boolean busy;
        private int workToken;
        private DeepSeekClient client;
        private AiScoreAssistant assistant;

        private int mode;
        private final TextView[] modeChips = new TextView[4];
        private final LinearLayout[] panels = new LinearLayout[4];
        private LinearLayout panelHost;

        private LinearLayout banner;
        private TextView hintLine;
        private LinearLayout progressBox;
        private TextView progressText;
        private TextView progressSteps;
        private TextView cancelButton;

        // 搜曲谱
        private EditText searchInput;
        // 网页 / 文本
        private EditText urlInput;
        private EditText webTitleInput;
        private EditText webTextInput;
        // 粘贴简谱
        private EditText pasteTitleInput;
        private EditText pasteInput;
        // 相册
        private TextView imageInfo;

        private ScrollView contentScroll;
        private LinearLayout resultCard;
        private TextView credibilityBadge;
        private TextView sourceUrlView;
        private EditText titleInput;
        private LinearLayout transcriptionSection;
        private EditText transcriptionInput;
        private TextView transcriptionHint;
        private LinearLayout eventsSection;
        private TextView eventsPreview;
        private LinearLayout midiBox;
        private TextView midiInfo;
        private TextView summaryLabel;
        private TextView importButton;
        private TextView regenerateButton;
        private TextView detailBackButton;

        // 多来源候选（搜曲谱）
        private LinearLayout candidateCard;
        private LinearLayout candidateList;
        private TextView candidateHeader;
        private TextView nowPlayingLabel;
        private LinearLayout diagnosticCard;
        private TextView diagnosticTried;
        private TextView diagnosticSkipped;
        private TextView diagnosticAdvice;
        private final List<CandidateRow> candidateRows = new ArrayList<>();
        private int previewIndex = -1;
        private boolean fromCandidates;
        private String lastSearchName = "";

        private LinearLayout errorBox;
        private TextView errorText;
        private TextView errorSuggestion;
        private TextView retryButton;

        // 当前草稿状态
        private Dialog originalViewer;
        private ScoreImageDocument currentDocument;
        private boolean bindingDraft,draftDirty;
        private CheckBox reviewCheck;
        private boolean savingDraft;
        private TextView detailPreview,originalPreview,qualityLabel,detailPreviewState;
        private boolean detailPreviewActive;
        private TextView numericOctave,numericTiming,numericRepeat;
        private AiScoreAssistant.ScoreDraft referenceOriginal;private ScoreImageDocument referenceOriginalDocument;private TextView restoreReference;
        private String validatedTranscription="",currentSource="";
        private int currentOmittedNotes;
        private byte[] currentMidi;
        private String currentEvents = "";
        private boolean currentDeterministic;
        private String currentSourceUrl = "";
        private boolean importable;
        private Runnable lastRetry;

        /** 候选列表的一行；保存按钮引用，用于「试听/停止」状态切换。 */
        private static final class CandidateRow {
            final AiScoreAssistant.ScoreDraft draft;
            final TextView previewButton;

            CandidateRow(AiScoreAssistant.ScoreDraft draft, TextView previewButton) {
                this.draft = draft;
                this.previewButton = previewButton;
            }
        }

        Ui(Activity activity, AiCredentials creds, ImportHandler handler) {
            this.activity = activity;
            this.creds = creds;
            this.handler = handler;
            this.kit = UiKit.create(activity);
            this.c = AppTheme.colors();
            this.scale = AiSetupDialog.textScale(activity);
            this.dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        }

        void open() {
            LinearLayout root = kit.column();
            root.setBackgroundColor(c.bgRoot);
            root.setPadding(kit.dp(18f), kit.dp(12f), kit.dp(18f), kit.dp(10f));
            root.addView(buildTopBar());
            root.addView(buildSubtitle());
            root.addView(buildModeSelector());
            root.addView(buildBanner());
            root.addView(buildHintLine());
            root.addView(buildProgressBox());

            contentScroll = new ScrollView(activity);
            contentScroll.setFillViewport(false);
            contentScroll.setBackgroundColor(c.bgRoot);
            LinearLayout body = kit.column();
            body.setPadding(0, kit.dp(8f), 0, kit.dp(8f));
            panelHost = kit.column();
            panels[MODE_SEARCH] = buildSearchPanel();
            panels[MODE_WEB_TEXT] = buildWebTextPanel();
            panels[MODE_IMAGE] = buildImagePanel();
            panels[MODE_PASTE] = buildPastePanel();
            for (LinearLayout panel : panels) {
                panelHost.addView(panel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            body.addView(panelHost);
            body.addView(buildCandidateCard());
            body.addView(buildDiagnosticCard());
            body.addView(buildErrorBox());
            body.addView(buildResultCard());
            contentScroll.addView(body, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(contentScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            TextView footnote = kit.text(
                    "确定性解析（ABC/MIDI/规则简谱）由 App 本机完成，可放心导入；"
                            + "标注「AI 转录」的结果请核对来源与转录后再导入。",
                    11f * scale, c.textTertiary);
            footnote.setLineSpacing(kit.dp(2f), 1f);
            footnote.setPadding(0, kit.dp(6f), 0, kit.dp(2f));
            root.addView(footnote);

            dialog.setContentView(root);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> cleanup());
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(c.bgRoot));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            if(handler!=null)handler.setPreviewObserver((active,message)->activity.runOnUiThread(()->{
                if(!canTouch())return;
                detailPreviewActive=active;
                if(detailPreview!=null)detailPreview.setText(active?"停止试听":"试听当前稿");
                if(detailPreviewState!=null)detailPreviewState.setText(message==null?"":message);
                if(!active){previewIndex=-1;updatePreviewButtons();if(nowPlayingLabel!=null){nowPlayingLabel.setText("");nowPlayingLabel.setVisibility(View.GONE);}}
            }));
            rebuildClient();
            switchMode(MODE_SEARCH);
        }

        // ---------------------------------------------------------- 顶部

        private View buildTopBar() {
            LinearLayout bar = kit.row();
            TextView title = kit.textBold("AI 曲谱导入", 20f * scale, c.textPrimary);
            bar.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = compactButton("关闭", c.textSecondary);
            close.setOnClickListener(v -> dialog.dismiss());
            bar.addView(close);
            return bar;
        }

        private View buildSubtitle() {
            LinearLayout box = kit.column();
            TextView subtitle = kit.text(
                    "AI 只负责搜索/识别，音高与节奏由 App 规则解析：能直接用的谱面会被标成「确定性解析」。",
                    12f * scale, c.textSecondary);
            subtitle.setLineSpacing(kit.dp(3f), 1f);
            subtitle.setPadding(0, kit.dp(6f), 0, 0);
            box.addView(subtitle);
            TextView multiSource = kit.text(
                    "搜索结果来自多个来源；重名版本建议先试听 12 秒再选择。",
                    11f * scale, c.textTertiary);
            multiSource.setLineSpacing(kit.dp(2f), 1f);
            multiSource.setPadding(0, kit.dp(3f), 0, kit.dp(2f));
            box.addView(multiSource);
            return box;
        }

        private View buildModeSelector() {
            LinearLayout row = kit.row();
            String[] labels = {"搜曲谱（多来源）", "网页/文本", "照片 / PDF", "粘贴简谱"};
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                TextView chip = kit.chip(labels[i], false);
                chip.setOnClickListener(v -> {
                    if (!canTouch()) {
                        return;
                    }
                    switchMode(index);
                });
                modeChips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    lp.leftMargin = kit.dp(8f);
                }
                row.addView(chip, lp);
            }
            // 四个模式 chip 横排：必须用横向滚动容器，窄屏/大字号下不会把页面顶宽。
            android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(10f);
            LinearLayout holder = kit.column();
            holder.addView(scroller, lp);
            return holder;
        }

        private View buildBanner() {
            banner = kit.row();
            banner.setBackground(kit.bg(c.surface2, 12f));
            banner.setPadding(kit.dp(12f), kit.dp(8f), kit.dp(8f), kit.dp(8f));
            TextView text = kit.text("AI 尚未配置，导入功能不可用。", 12f * scale, c.warn);
            banner.addView(text, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = kit.textBold("去配置", 12f * scale, c.accent);
            go.setGravity(Gravity.CENTER);
            go.setPadding(kit.dp(8f), kit.dp(6f), kit.dp(6f), kit.dp(6f));
            go.setMinHeight(kit.dp(40f));
            go.setClickable(true);
            go.setFocusable(true);
            go.setOnClickListener(v -> AiSetupDialog.show(activity, () -> {
                if (!canTouch()) {
                    return;
                }
                rebuildClient();
                showHint(hasAi() ? "AI 已配置好，可以开始导入了" : "配置尚未完成");
            }));
            banner.addView(go);
            banner.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(8f);
            LinearLayout holder = kit.column();
            holder.addView(banner, lp);
            return holder;
        }

        private View buildHintLine() {
            hintLine = kit.text("", 12f * scale, c.textSecondary);
            hintLine.setPadding(0, kit.dp(6f), 0, 0);
            hintLine.setVisibility(View.GONE);
            return hintLine;
        }

        private View buildProgressBox() {
            progressBox = kit.column();
            progressBox.setBackground(kit.bg(c.surface2, 12f));
            progressBox.setPadding(kit.dp(12f), kit.dp(10f), kit.dp(12f), kit.dp(10f));

            LinearLayout top = kit.row();
            ProgressBar bar = new ProgressBar(activity, null,
                    android.R.attr.progressBarStyleSmall);
            bar.setIndeterminate(true);
            try {
                bar.setIndeterminateTintList(kit.tint(c.accent));
            } catch (Throwable ignored) {
            }
            top.addView(bar, new LinearLayout.LayoutParams(kit.dp(22f), kit.dp(22f)));

            progressText = kit.text("处理中…", 13f * scale, c.textPrimary);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.leftMargin = kit.dp(10f);
            top.addView(progressText, textLp);

            cancelButton = kit.textBold("取消", 12f * scale, c.danger);
            cancelButton.setGravity(Gravity.CENTER);
            cancelButton.setPadding(kit.dp(10f), kit.dp(8f), kit.dp(6f), kit.dp(8f));
            cancelButton.setMinHeight(kit.dp(40f));
            cancelButton.setClickable(true);
            cancelButton.setFocusable(true);
            cancelButton.setOnClickListener(v -> cancelWork());
            top.addView(cancelButton);
            progressBox.addView(top);

            progressSteps = kit.text("", 11f * scale, c.textSecondary);
            progressSteps.setLineSpacing(kit.dp(2f), 1f);
            LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stepsLp.topMargin = kit.dp(6f);
            progressSteps.setVisibility(View.GONE);
            progressBox.addView(progressSteps, stepsLp);

            progressBox.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(8f);
            LinearLayout holder = kit.column();
            holder.addView(progressBox, lp);
            return holder;
        }

        // ---------------------------------------------------------- 四个模式面板

        private LinearLayout buildSearchPanel() {
            LinearLayout panel = kit.column();
            panel.addView(kit.sectionHeader("全网找谱"));
            panel.addView(kit.text("搜索歌名与作者，找到谱页后粘贴链接，或导入下载的 MIDI、PDF、照片。", 12f * scale, c.textSecondary));
            searchInput = input("歌名与作者，例如：成都 赵雷", false);
            panel.addView(searchInput, matchWrap(kit.dp(10f)));
            TextView sources = kit.action("搜索谱页（不限站点）", c.accent, c.bgRoot);
            sources.setOnClickListener(v -> findOnlineReferences(searchInput.getText().toString()));
            panel.addView(sources, matchWrap(kit.dp(12f)));
            LinearLayout row = kit.row();
            TextView baidu = kit.actionOutline("百度找谱"), bing = kit.actionOutline("必应找谱");
            baidu.setOnClickListener(v -> searchInBrowser(searchInput.getText().toString(), false));
            bing.setOnClickListener(v -> searchInBrowser(searchInput.getText().toString(), true));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1f);
            left.rightMargin = kit.dp(10f);
            row.addView(baidu, left);
            row.addView(bing, new LinearLayout.LayoutParams(0, -2, 1f));
            panel.addView(row, matchWrap(kit.dp(12f)));
            TextView link = kit.actionOutline("粘贴找到的谱页链接");
            link.setOnClickListener(v -> {
                if (busy || !canTouch()) return;
                AppDialogs.prompt(activity, "粘贴谱页链接", "", value -> {
                    try {
                        String url = BrowserScoreSearch.pageUrl(value);
                        urlInput.setText(url);
                        switchMode(MODE_WEB_TEXT);
                        showHint("链接已填入，可读取网页；图片或PDF可下载后导入。");
                    } catch (IllegalArgumentException error) { toast(error.getMessage()); }
                });
            });
            panel.addView(link, matchWrap(kit.dp(10f)));
            TextView file = kit.actionOutline("导入已下载的谱");
            file.setOnClickListener(v -> {
                if (busy || !canTouch()) return;
                try { handler.requestScoreFile(() -> { if (canTouch()) dialog.dismiss(); }); }
                catch (Exception error) { toast("无法打开文件选择器，请从曲库导入"); }
            });
            panel.addView(file, matchWrap(kit.dp(10f)));
            TextView automatic = kit.actionOutline("App 内自动搜谱与解析");
            automatic.setOnClickListener(v -> startSearch());
            panel.addView(automatic, matchWrap(kit.dp(18f)));
            return panel;
        }

        private void searchInBrowser(String name, boolean bing) {
            if (busy || !canTouch()) return;
            try { stopCandidatePreview(); openUrl(BrowserScoreSearch.searchUrl(name, bing)); }
            catch (IllegalArgumentException error) { toast(error.getMessage()); }
        }

        private LinearLayout buildWebTextPanel() {
            LinearLayout panel = kit.column();
            panel.addView(kit.sectionHeader("网页 / 文本"));
            panel.addView(kit.text(
                            "网页正文或粘贴的文字会先按简谱/ABC/4 行 TAB 规则解析；解析不出才让 AI 规范化成简谱，"
                                    + "再由 App 解析。AI 规范化后的结果会标黄提醒校对。",
                    12f * scale, c.textSecondary));

            panel.addView(kit.textBold("网页链接", 12f * scale, c.textSecondary));
            urlInput = input("https://…", false);
            urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            panel.addView(urlInput, matchWrap(kit.dp(6f)));
            TextView fetchButton = kit.actionOutline("抓取网页并解析");
            fetchButton.setOnClickListener(v -> startUrl());
            panel.addView(fetchButton, matchWrap(kit.dp(10f)));

            View divider = kit.divider();
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(1f));
            dividerLp.topMargin = kit.dp(16f);
            panel.addView(divider, dividerLp);

            panel.addView(kit.textBold("直接粘贴文本", 12f * scale, c.textSecondary));
            webTitleInput = input("曲名（可选）", false);
            panel.addView(webTitleInput, matchWrap(kit.dp(6f)));
            webTextInput = input("粘贴简谱 / ABC / 4 行 TAB / 歌词+和弦 / 文字描述…", true);
            webTextInput.setMinLines(4);
            panel.addView(webTextInput, matchWrap(kit.dp(8f)));
            TextView textButton = kit.action("解析文本", c.accent, c.bgRoot);
            textButton.setOnClickListener(v -> startText(webTextInput, webTitleInput, "AI 文本导入"));
            panel.addView(textButton, matchWrap(kit.dp(10f)));
            return panel;
        }

        private LinearLayout buildImagePanel() {
            LinearLayout panel = kit.column();
            panel.addView(kit.sectionHeader("相册识谱（需校对）"));
            panel.addView(kit.text(
                            "AI 只把图片读成规范简谱文本，再由 App 规则解析；识别结果可能有误，"
                                    + "请对照原谱修改转录后点「重新解析文本」。图片越清晰、越正对谱面越准。",
                    12f * scale, c.textSecondary));
            imageInfo = kit.text("尚未选择图片", 12f * scale, c.textTertiary);
            imageInfo.setPadding(0, kit.dp(10f), 0, 0);
            panel.addView(imageInfo);
            TextView button = kit.action("选择照片或 PDF", c.accent, c.bgRoot);
            button.setOnClickListener(v -> pickImage());
            panel.addView(button, matchWrap(kit.dp(12f)));
            return panel;
        }

        private LinearLayout buildPastePanel() {
            LinearLayout panel = kit.column();
            panel.addView(kit.sectionHeader("粘贴简谱（推荐）"));
            panel.addView(kit.text(
                            "规范简谱示例：BPM:96、1=C、4/4 头行；正文用空格分隔 1-7/0，"
                                    + "'-' 延长一拍、'_' 半拍、'.' 附点、' 高八度、',' 低八度，'|' 小节线可忽略。"
                                    + "也可以粘贴 ABC 文本（含 X:/K:）或 4 行品格 TAB（G/C/E/A 四行）。",
                    12f * scale, c.textSecondary));
            pasteTitleInput = input("曲名（可选）", false);
            panel.addView(pasteTitleInput, matchWrap(kit.dp(10f)));
            pasteInput = input("BPM:96\n1=C\n4/4\n1 1 5 5 | 6 6 5 - | 4 4 3 3 | 2 2 1 -", true);
            pasteInput.setMinLines(5);
            panel.addView(pasteInput, matchWrap(kit.dp(8f)));
            TextView button = kit.action("解析简谱", c.accent, c.bgRoot);
            button.setOnClickListener(v -> startText(pasteInput, pasteTitleInput, "AI 简谱导入"));
            panel.addView(button, matchWrap(kit.dp(12f)));
            return panel;
        }

        // ---------------------------------------------------------- 多来源候选 / 诊断

        private View buildCandidateCard() {
            candidateCard = kit.column();
            LinearLayout card = kit.card(activity);
            candidateHeader = kit.textBold("多来源候选", 15f * scale, c.textPrimary);
            card.addView(candidateHeader);
            nowPlayingLabel = kit.text("", 12f * scale, c.accent);
            nowPlayingLabel.setLineSpacing(kit.dp(2f), 1f);
            LinearLayout.LayoutParams playingLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            playingLp.topMargin = kit.dp(6f);
            nowPlayingLabel.setVisibility(View.GONE);
            card.addView(nowPlayingLabel, playingLp);
            TextView hint = kit.text(
                    "试听只播放一小段（约 12 秒）；同一时间只会播放一条，点「停止」可结束。",
                    11f * scale, c.textTertiary);
            hint.setLineSpacing(kit.dp(2f), 1f);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = kit.dp(4f);
            card.addView(hint, hintLp);
            candidateList = kit.column();
            LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            listLp.topMargin = kit.dp(6f);
            card.addView(candidateList, listLp);
            candidateCard.addView(card);
            candidateCard.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(10f);
            LinearLayout holder = kit.column();
            holder.addView(candidateCard, lp);
            return holder;
        }

        private View buildDiagnosticCard() {
            diagnosticCard = kit.column();
            LinearLayout card = kit.card(activity);
            card.addView(kit.textBold("尚未取得可直接导入的音符数据", 15f * scale, c.textPrimary));
            TextView lead = kit.text(
                    "这不等于网上没有这首歌。可继续查看全网谱页，取得 MIDI、PDF 或谱图后导入。",
                    12f * scale, c.textSecondary);
            lead.setLineSpacing(kit.dp(3f), 1f);
            card.addView(lead, matchWrap(kit.dp(8f)));

            diagnosticAdvice = kit.text("", 12f * scale, c.accent);
            diagnosticAdvice.setLineSpacing(kit.dp(2f), 1f);
            diagnosticAdvice.setVisibility(View.GONE);
            card.addView(diagnosticAdvice, matchWrap(kit.dp(6f)));

            card.addView(kit.textBold("已尝试来源", 12f * scale, c.textSecondary));
            diagnosticTried = kit.text("", 12f * scale, c.textPrimary);
            diagnosticTried.setLineSpacing(kit.dp(2f), 1f);
            card.addView(diagnosticTried, matchWrap(kit.dp(4f)));

            card.addView(kit.textBold("跳过原因", 12f * scale, c.textSecondary));
            diagnosticSkipped = kit.text("", 12f * scale, c.textSecondary);
            diagnosticSkipped.setLineSpacing(kit.dp(2f), 1f);
            card.addView(diagnosticSkipped, matchWrap(kit.dp(4f)));

            LinearLayout row1 = kit.row();
            TextView rename = kit.action("全网继续找谱", c.accent, c.bgRoot);
            rename.setOnClickListener(v -> findOnlineReferences(searchInput.getText().toString()));
            row1.addView(rename, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView retry = kit.actionOutline("再试一次");
            retry.setOnClickListener(v -> startSearch(isEmpty(lastSearchName) ? null : lastSearchName));
            LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            retryLp.leftMargin = kit.dp(8f);
            row1.addView(retry, retryLp);
            card.addView(row1, matchWrap(kit.dp(14f)));

            LinearLayout row2 = kit.row();
            TextView image = kit.actionOutline("照片 / PDF");
            image.setOnClickListener(v -> {
                switchMode(MODE_IMAGE);
                showHint("选一张清晰、正对谱面的照片；识别结果可以对照原谱修改。");
            });
            row2.addView(image, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView paste = kit.actionOutline("粘贴简谱");
            paste.setOnClickListener(v -> switchMode(MODE_PASTE));
            LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pasteLp.leftMargin = kit.dp(8f);
            row2.addView(paste, pasteLp);
            card.addView(row2, matchWrap(kit.dp(8f)));

            TextView link = kit.actionOutline("粘贴网页链接（简谱/ABC 页面）");
            link.setOnClickListener(v -> {
                switchMode(MODE_WEB_TEXT);
                showHint("把包含简谱/ABC 的公开网页链接粘贴到上方，再点「抓取网页并解析」。");
            });
            card.addView(link, matchWrap(kit.dp(8f)));

            diagnosticCard.addView(card);
            diagnosticCard.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(10f);
            LinearLayout holder = kit.column();
            holder.addView(diagnosticCard, lp);
            return holder;
        }

        private TextView badgeView(String label, int color) {
            TextView badge = kit.textBold(label, 11f * scale, color);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(kit.dp(8f), kit.dp(3f), kit.dp(8f), kit.dp(3f));
            badge.setBackground(kit.bg(AppTheme.withAlpha(color, 0.16f), 999f));
            return badge;
        }

        private TextView smallButton(String label, int textColor, int bgColor) {
            TextView button = kit.textBold(label, 12f * scale, textColor);
            button.setGravity(Gravity.CENTER);
            button.setPadding(kit.dp(14f), kit.dp(8f), kit.dp(14f), kit.dp(8f));
            button.setMinHeight(kit.dp(44f));
            button.setMinWidth(kit.dp(64f));
            button.setBackground(kit.ripple(bgColor, 12f));
            button.setClickable(true);
            button.setFocusable(true);
            return button;
        }

        private View buildCandidateRow(final AiScoreAssistant.ScoreDraft draft, final int index) {
            LinearLayout item = kit.column();
            item.setBackground(kit.bg(c.surface2, 14f));
            item.setPadding(kit.dp(12f), kit.dp(10f), kit.dp(12f), kit.dp(10f));

            LinearLayout titleRow = kit.row();
            TextView title = kit.textBold(titleOf(draft), 14f * scale, c.textPrimary);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleRow.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView badge = badgeView(draft.deterministic ? "确定性" : "AI 转录",
                    draft.deterministic ? c.success : c.warn);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.leftMargin = kit.dp(8f);
            titleRow.addView(badge, badgeLp);
            item.addView(titleRow);

            LinearLayout metaRow = kit.row();
            TextView source = kit.text(candidateSourceName(draft), 11f * scale, c.textSecondary);
            source.setMaxLines(1);
            source.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metaRow.addView(source, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView meta = kit.text(candidateMeta(draft), 11f * scale, c.textTertiary);
            meta.setMaxLines(1);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaLp.leftMargin = kit.dp(8f);
            metaRow.addView(meta, metaLp);
            item.addView(metaRow, matchWrap(kit.dp(3f)));

            if (!isEmpty(draft.sourceUrl)) {
                TextView sourceUrl = kit.text("来源链接：" + draft.sourceUrl, 11f * scale, c.accent);
                sourceUrl.setMaxLines(1);
                sourceUrl.setEllipsize(android.text.TextUtils.TruncateAt.END);
                sourceUrl.setClickable(true);
                sourceUrl.setFocusable(true);
                sourceUrl.setOnClickListener(v -> openUrl(draft.sourceUrl));
                item.addView(sourceUrl, matchWrap(kit.dp(4f)));
            }

            LinearLayout buttonRow = kit.row();
            buttonRow.setGravity(Gravity.END);
            final TextView preview = smallButton("试听", c.accent, c.surface3);
            preview.setOnClickListener(v -> togglePreview(index));
            TextView use = smallButton("用这个", c.bgRoot, c.accent);
            use.setOnClickListener(v -> useCandidate(index));
            buttonRow.addView(preview, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            useLp.leftMargin = kit.dp(8f);
            buttonRow.addView(use, useLp);
            item.addView(buttonRow, matchWrap(kit.dp(8f)));

            candidateRows.add(new CandidateRow(draft, preview));
            return item;
        }

        private void togglePreview(int index) {
            if (!canTouch() || index < 0 || index >= candidateRows.size()) {
                return;
            }
            if (previewIndex == index) {
                stopCandidatePreview();
                showHint("已停止试听");
                return;
            }
            stopCandidatePreview();
            if (handler == null) {
                showHint("当前界面没有接入试听");
                return;
            }
            AiScoreAssistant.ScoreDraft draft = candidateRows.get(index).draft;
            String title = titleOf(draft);
            try {
                if (draft.midiBytes != null && draft.midiBytes.length > 0) {
                    handler.previewMidi(draft.midiBytes, title);
                } else if (!isEmpty(draft.eventsText)) {
                    handler.previewText(draft.eventsText, title);
                } else {
                    showHint("这个候选没有可试听的内容");
                    return;
                }
            } catch (Throwable error) {
                showHint("试听启动失败，请换个候选");
                return;
            }
            if(!detailPreviewActive)return;
            previewIndex = index;
            if (nowPlayingLabel != null) {
                nowPlayingLabel.setText("正在试听：" + title + "（12 秒，再点「停止」可结束）");
                nowPlayingLabel.setVisibility(View.VISIBLE);
            }
            updatePreviewButtons();

        }

        private void stopCandidatePreview() {
            if (handler != null) {
                try {
                    handler.stopPreview();
                } catch (Throwable ignored) {
                }
            }
            previewIndex = -1;
            if (nowPlayingLabel != null) {
                nowPlayingLabel.setText("");
                nowPlayingLabel.setVisibility(View.GONE);
            }
            updatePreviewButtons();
        }

        private void updatePreviewButtons() {
            for (int i = 0; i < candidateRows.size(); i++) {
                CandidateRow row = candidateRows.get(i);
                if (row == null || row.previewButton == null) {
                    continue;
                }
                boolean playing = i == previewIndex;
                row.previewButton.setText(playing ? "停止" : "试听");
                row.previewButton.setTextColor(playing ? c.danger : c.accent);
            }
        }

        private void useCandidate(int index) {
            if (!canTouch() || index < 0 || index >= candidateRows.size()) {
                return;
            }
            stopCandidatePreview();
            AiScoreAssistant.ScoreDraft draft = candidateRows.get(index).draft;
            fromCandidates = true;
            if (candidateCard != null) {
                candidateCard.setVisibility(View.GONE);
            }
            if (diagnosticCard != null) {
                diagnosticCard.setVisibility(View.GONE);
            }
            showDraft(draft, isEmpty(draft.title) ? lastSearchName : draft.title);
        }

        private void backToCandidates() {
            if (!canTouch() || candidateRows.isEmpty()) {
                return;
            }
            fromCandidates = false;
            if (resultCard != null) {
                resultCard.setVisibility(View.GONE);
            }
            if (diagnosticCard != null) {
                diagnosticCard.setVisibility(View.GONE);
            }
            if (candidateCard != null) {
                candidateCard.setVisibility(View.VISIBLE);
            }
            showHint("重新选择候选：可先试听，再点「用这个」");
            scrollToCandidates();
        }

        private void onCandidates(List<AiScoreAssistant.ScoreDraft> candidates,
                                  List<String> triedSources, List<String> skippedSources) {
            if (!canTouch()) {
                return;
            }
            // 搜索期间用户可能切到别的模式；结果回来时回到「搜曲谱」并展示候选，避免结果被隐藏
            if (mode != MODE_SEARCH) {
                switchMode(MODE_SEARCH);
            }
            stopCandidatePreview();
            fromCandidates = false;
            candidateRows.clear();
            if (candidateList != null) {
                candidateList.removeAllViews();
            }
            if (resultCard != null) {
                resultCard.setVisibility(View.GONE);
            }
            hideError();
            List<AiScoreAssistant.ScoreDraft> valid = new ArrayList<>();
            if (candidates != null) {
                for (AiScoreAssistant.ScoreDraft draft : candidates) {
                    if (draft != null && valid.size() < MAX_CANDIDATES) {
                        valid.add(draft);
                    }
                }
            }
            if (valid.isEmpty()) {
                if (candidateCard != null) {
                    candidateCard.setVisibility(View.GONE);
                }
                showDiagnostic(triedSources, skippedSources);
                return;
            }
            if (diagnosticCard != null) {
                diagnosticCard.setVisibility(View.GONE);
            }
            if (candidateCard != null) {
                candidateCard.setVisibility(View.VISIBLE);
            }
            if (candidateHeader != null) {
                candidateHeader.setText("找到 " + valid.size() + " 个来源版本 · 选择前可先试听");
            }
            for (int i = 0; i < valid.size(); i++) {
                View row = buildCandidateRow(valid.get(i), i);
                if (i > 0) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = kit.dp(8f);
                    candidateList.addView(row, lp);
                } else {
                    candidateList.addView(row);
                }
            }
            updatePreviewButtons();
            showHint("共 " + valid.size() + " 个候选：试听 12 秒，觉得对味再点「用这个」");
            scrollToCandidates();
        }

        private void showDiagnostic(List<String> triedSources, List<String> skippedSources) {
            if (diagnosticCard == null) {
                return;
            }
            if (diagnosticTried != null) {
                diagnosticTried.setText(joinSources(triedSources, "（本轮没有记录到具体来源）"));
            }
            if (diagnosticSkipped != null) {
                diagnosticSkipped.setText(joinSkipped(skippedSources));
            }
            if (diagnosticAdvice != null) {
                diagnosticAdvice.setVisibility(View.GONE);
            }
            diagnosticCard.setVisibility(View.VISIBLE);
            showHint("没有找到可直接解析的谱面，试试下面的办法");
            scrollToCandidates();
        }

        /**
         * 核心契约里「0 候选」走 onError(code=no_candidates)，message 里已经带
         * 「已尝试/跳过/建议」三段文本；这里解析回结构化诊断卡，避免用户只看到一大段失败文字。
         */
        private void showNoCandidateDiagnostic(String message) {
            if (diagnosticCard == null) {
                return;
            }
            if (mode != MODE_SEARCH) {
                switchMode(MODE_SEARCH);
            }
            stopCandidatePreview();
            String tried = "";
            String skipped = "";
            String advice = "";
            if (message != null) {
                for (String rawLine : message.split("\\r?\\n")) {
                    String line = rawLine == null ? "" : rawLine.trim();
                    if (line.startsWith("已尝试：")) {
                        tried = line.substring("已尝试：".length()).trim();
                    } else if (line.startsWith("跳过：")) {
                        skipped = line.substring("跳过：".length()).trim();
                    } else if (line.startsWith("建议：")) {
                        advice = line.substring("建议：".length()).trim();
                    }
                }
            }
            if (candidateCard != null) {
                candidateCard.setVisibility(View.GONE);
            }
            if (resultCard != null) {
                resultCard.setVisibility(View.GONE);
            }
            hideError();
            if (diagnosticTried != null) {
                diagnosticTried.setText(isEmpty(tried) ? "（核心未记录具体来源）" : tried);
            }
            if (diagnosticSkipped != null) {
                diagnosticSkipped.setText(isEmpty(skipped) ? "（无）" : skipped);
            }
            if (diagnosticAdvice != null) {
                if (isEmpty(advice)) {
                    diagnosticAdvice.setVisibility(View.GONE);
                } else {
                    diagnosticAdvice.setText("可查看原网页或导入已下载的原谱；需要登录、购买的来源，请在原站完成。");
                    diagnosticAdvice.setVisibility(View.VISIBLE);
                }
            }
            diagnosticCard.setVisibility(View.VISIBLE);
            showHint("本轮没有直接可用的音符，仍可继续搜索谱页");
            scrollToCandidates();
        }

        private void promptRenameSearch() {
            if (!canTouch()) {
                return;
            }
            String initial = isEmpty(lastSearchName)
                    ? searchInput.getText().toString().trim() : lastSearchName;
            try {
                AppDialogs.prompt(activity, "换一个歌名再搜", initial,
                        new AppDialogs.OnText() {
                            @Override
                            public void onText(String value) {
                                if (!canTouch() || value == null) {
                                    return;
                                }
                                String name = value.trim();
                                if (name.isEmpty()) {
                                    showHint("请输入歌名再搜索");
                                    return;
                                }
                                searchInput.setText(name);
                                startSearch(name);
                            }
                        });
            } catch (Throwable error) {
                showHint("无法打开输入框，请在搜索框里改名后重试");
            }
        }

        private void scrollToCandidates() {
            if (contentScroll == null) {
                return;
            }
            contentScroll.post(() -> {
                if (!canTouch()) {
                    return;
                }
                View target = candidateCard != null && candidateCard.getVisibility() == View.VISIBLE
                        ? candidateCard
                        : (diagnosticCard != null && diagnosticCard.getVisibility() == View.VISIBLE
                        ? diagnosticCard : null);
                if (target != null) {
                    contentScroll.smoothScrollTo(0, Math.max(0, offsetInScroll(target)));
                } else {
                    contentScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }

        /** 计算控件在 ScrollView 内容坐标里的纵向偏移（用于滚到候选/诊断区）。 */
        private int offsetInScroll(View view) {
            int top = 0;
            View current = view;
            for (int guard = 0; current != null && guard < 16; guard++) {
                Object parent = current.getParent();
                if (!(parent instanceof View)) {
                    break;
                }
                View parentView = (View) parent;
                if (parentView == contentScroll) {
                    break;
                }
                top += current.getTop();
                current = parentView;
            }
            return top;
        }

        private void prepareSingleFlow() {
            referenceOriginal=null;referenceOriginalDocument=null;if(restoreReference!=null)restoreReference.setVisibility(View.GONE);
            currentDocument=null;currentEvents="";currentMidi=null;importable=false;
            if(resultCard!=null)resultCard.setVisibility(View.GONE);
            setImportEnabled(false);
            stopCandidatePreview();
            fromCandidates = false;
            candidateRows.clear();
            if (candidateList != null) {
                candidateList.removeAllViews();
            }
            if (candidateCard != null) {
                candidateCard.setVisibility(View.GONE);
            }
            if (diagnosticCard != null) {
                diagnosticCard.setVisibility(View.GONE);
            }
        }

        private void openUrl(String url) {
            if (!canTouch() || isEmpty(url)) {
                return;
            }
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable error) {
                toast("无法打开来源链接");
            }
        }

        private String titleOf(AiScoreAssistant.ScoreDraft draft) {
            if (draft != null && !isEmpty(draft.title)) {
                return draft.title;
            }
            return isEmpty(lastSearchName) ? "未命名曲谱" : lastSearchName;
        }

        private String candidateSourceName(AiScoreAssistant.ScoreDraft draft) {
            if (draft == null) {
                return "未知来源";
            }
            if (!isEmpty(draft.sourceName)) {
                return draft.sourceName;
            }
            if (!isEmpty(draft.source)) {
                return draft.source;
            }
            String host = hostOf(draft.sourceUrl);
            return host.isEmpty() ? "未知来源" : host;
        }

        private String candidateMeta(AiScoreAssistant.ScoreDraft draft) {
            if (draft == null) {
                return "";
            }
            if (draft.midiBytes != null && draft.midiBytes.length > 0) {
                return "MIDI · " + formatSize(draft.midiBytes.length);
            }
            String summary = describe(draft.eventsText);
            if (!isEmpty(summary)) {
                return summary;
            }
            if (!isEmpty(draft.eventsText)) {
                return "事件文本 · 导入前需校验";
            }
            return "暂无摘要";
        }

        private static String joinSources(List<String> list, String fallback) {
            if (list == null || list.isEmpty()) {
                return fallback;
            }
            StringBuilder builder = new StringBuilder();
            for (String item : list) {
                if (isEmpty(item)) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('、');
                }
                builder.append(item);
            }
            return builder.length() == 0 ? fallback : builder.toString();
        }

        private static String joinSkipped(List<String> list) {
            if (list == null || list.isEmpty()) {
                return "（无）";
            }
            StringBuilder builder = new StringBuilder();
            for (String item : list) {
                if (isEmpty(item)) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append("· ").append(item);
            }
            return builder.length() == 0 ? "（无）" : builder.toString();
        }

        // ---------------------------------------------------------- 结果 / 错误卡片

        private View buildErrorBox() {
            errorBox = kit.column();
            errorBox.setBackground(kit.bg(c.surface2, 14f));
            errorBox.setPadding(kit.dp(14f), kit.dp(12f), kit.dp(14f), kit.dp(12f));
            errorText = kit.text("", 13f * scale, c.danger);
            errorText.setLineSpacing(kit.dp(3f), 1f);
            errorBox.addView(errorText);
            errorSuggestion = kit.text("", 12f * scale, c.textSecondary);
            errorSuggestion.setLineSpacing(kit.dp(3f), 1f);
            LinearLayout.LayoutParams sugLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sugLp.topMargin = kit.dp(8f);
            errorSuggestion.setVisibility(View.GONE);
            errorBox.addView(errorSuggestion, sugLp);
            retryButton = kit.actionOutline("重试");
            retryButton.setOnClickListener(v -> {
                if (!canTouch() || lastRetry == null) {
                    return;
                }
                hideError();
                lastRetry.run();
            });
            errorBox.addView(retryButton, matchWrap(kit.dp(10f)));
            errorBox.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(10f);
            LinearLayout holder = kit.column();
            holder.addView(errorBox, lp);
            return holder;
        }

        private View buildResultCard() {
            LinearLayout card = kit.card(activity);
            card.setVisibility(View.GONE);
            resultCard = card;

            // 可信度徽标 + 来源
            credibilityBadge = kit.textBold("", 12f * scale, c.success);
            credibilityBadge.setGravity(Gravity.CENTER);
            credibilityBadge.setPadding(kit.dp(10f), kit.dp(5f), kit.dp(10f), kit.dp(5f));
            card.addView(credibilityBadge);

            sourceUrlView = kit.text("", 11f * scale, c.accent);
            sourceUrlView.setMaxLines(2);
            sourceUrlView.setTextIsSelectable(false);
            sourceUrlView.setClickable(true);
            sourceUrlView.setFocusable(true);
            LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sourceLp.topMargin = kit.dp(6f);
            card.addView(sourceUrlView, sourceLp);

            card.addView(kit.textBold("曲名", 12f * scale, c.textSecondary));
            titleInput = input("曲名", false);
            card.addView(titleInput, matchWrap(kit.dp(6f)));

            // 转录（文本/图片草稿可编辑；MIDI 草稿隐藏）
            transcriptionSection = kit.column();
            transcriptionSection.addView(kit.textBold("转录内容（可编辑）", 12f * scale, c.textSecondary));
            transcriptionHint = kit.text(
                    "先改转录再点「重新解析文本」：App 会先按简谱/ABC 规则解析，失败才让 AI 规范化。",
                    11f * scale, c.textTertiary);
            transcriptionHint.setLineSpacing(kit.dp(2f), 1f);
            transcriptionSection.addView(transcriptionHint);
            transcriptionInput = input("转录内容", true);
            transcriptionInput.setMinLines(4);
            transcriptionInput.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence text,int start,int before,int count){
                    if(bindingDraft)return;
                    draftDirty=!text.toString().equals(validatedTranscription);
                    if(reviewCheck!=null)reviewCheck.setChecked(false);
                    if(draftDirty){stopCandidatePreview();summaryLabel.setText("转录已修改，请重新校验后试听或保存");}
                    setImportEnabled(importable);
                }
                public void afterTextChanged(Editable e){}
            });
            transcriptionSection.addView(transcriptionInput, matchWrap(kit.dp(6f)));
            card.addView(transcriptionSection, matchWrap(kit.dp(10f)));

            // 事件预览（MIDI 草稿隐藏）
            eventsSection = kit.column();
            eventsSection.addView(kit.textBold("事件预览", 12f * scale, c.textSecondary));
            eventsPreview = kit.text("", 11f * scale, c.textPrimary);
            eventsPreview.setTypeface(Typeface.MONOSPACE);
            eventsPreview.setLineSpacing(kit.dp(2f), 1f);
            eventsPreview.setTextIsSelectable(true);
            ScrollView eventScroll = new ScrollView(activity);
            eventScroll.setBackground(kit.bg(c.surface2, 10f));
            eventScroll.setPadding(kit.dp(10f), kit.dp(8f), kit.dp(10f), kit.dp(8f));
            eventScroll.addView(eventsPreview);
            LinearLayout.LayoutParams eventLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(132f));
            eventLp.topMargin = kit.dp(6f);
            eventsSection.addView(eventScroll, eventLp);
            card.addView(eventsSection, matchWrap(kit.dp(10f)));

            // MIDI 草稿说明
            midiBox = kit.column();
            midiBox.setBackground(kit.bg(c.surface2, 12f));
            midiBox.setPadding(kit.dp(12f), kit.dp(10f), kit.dp(12f), kit.dp(10f));
            TextView midiTitle = kit.textBold("MIDI 文件已就绪", 12f * scale, c.textPrimary);
            midiBox.addView(midiTitle);
            midiInfo = kit.text("", 12f * scale, c.textSecondary);
            midiInfo.setLineSpacing(kit.dp(2f), 1f);
            midiInfo.setPadding(0, kit.dp(4f), 0, 0);
            midiBox.addView(midiInfo);
            midiBox.setVisibility(View.GONE);
            card.addView(midiBox, matchWrap(kit.dp(10f)));

            summaryLabel = kit.text("", 12f * scale, c.textSecondary);
            summaryLabel.setLineSpacing(kit.dp(3f), 1f);
            LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryLp.topMargin = kit.dp(10f);
            card.addView(summaryLabel, summaryLp);

            LinearLayout actions = kit.row();
            detailBackButton = kit.actionOutline("重新选");
            detailBackButton.setOnClickListener(v -> backToCandidates());
            detailBackButton.setVisibility(View.GONE);
            actions.addView(detailBackButton, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            regenerateButton = kit.actionOutline("重新校验转录");
            regenerateButton.setOnClickListener(v -> reparseFromTranscription());
            LinearLayout.LayoutParams regenLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            regenLp.leftMargin = kit.dp(8f);
            actions.addView(regenerateButton, regenLp);
            card.addView(actions, matchWrap(kit.dp(14f)));

            qualityLabel=kit.text("",12f*scale,c.textSecondary);card.addView(qualityLabel,matchWrap(kit.dp(8f)));
            LinearLayout tools=kit.row();
            detailPreview=kit.actionOutline("试听当前稿");detailPreview.setOnClickListener(v->{
                if(handler==null||draftDirty||busy)return;
                if(detailPreviewActive){stopCandidatePreview();return;}
                stopCandidatePreview();
                if(currentMidi!=null)handler.previewMidi(currentMidi,titleInput.getText().toString());
                else if(importable)handler.previewText(currentEvents,titleInput.getText().toString());
            });
            originalPreview=kit.actionOutline("对照原谱");originalPreview.setOnClickListener(v->showOriginal());
            LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1);a.rightMargin=kit.dp(8f);
            tools.addView(detailPreview,a);tools.addView(originalPreview,new LinearLayout.LayoutParams(0,-2,1));card.addView(tools,matchWrap(kit.dp(8f)));
            detailPreviewState=kit.text("点击试听，可随时停止",12f*scale,c.textSecondary);card.addView(detailPreviewState,matchWrap(kit.dp(6f)));
            LinearLayout numericOptions=kit.row();
            numericOctave=kit.actionOutline("整首升八度");numericTiming=kit.actionOutline("小节均分草稿");
            numericOctave.setOnClickListener(v->setNumericOption("OCTAVE","1"));numericTiming.setOnClickListener(v->setNumericOption("TIMING","BAR_DRAFT"));
            LinearLayout.LayoutParams no=new LinearLayout.LayoutParams(0,-2,1);no.rightMargin=kit.dp(8f);numericOptions.addView(numericOctave,no);numericOptions.addView(numericTiming,new LinearLayout.LayoutParams(0,-2,1));card.addView(numericOptions,matchWrap(kit.dp(8f)));
            numericRepeat=kit.actionOutline("反复：展开演奏");numericRepeat.setOnClickListener(v->setNumericOption("REPEAT","VISIBLE"));card.addView(numericRepeat,matchWrap(kit.dp(8f)));
            TextView onlineReference=kit.actionOutline("浏览器找完整谱 / 核对时值");onlineReference.setOnClickListener(v->{if(!busy&&canTouch())AppDialogs.prompt(activity,"填写歌名与作者",titleInput.getText().toString(),name -> searchInBrowser(name, false));});card.addView(onlineReference,matchWrap(kit.dp(8f)));
            restoreReference=kit.actionOutline("恢复联网前原稿");restoreReference.setVisibility(View.GONE);restoreReference.setOnClickListener(v->{if(!busy&&referenceOriginal!=null){currentDocument=referenceOriginalDocument;showDraft(referenceOriginal,referenceOriginal.title);referenceOriginal=null;referenceOriginalDocument=null;restoreReference.setVisibility(View.GONE);}});card.addView(restoreReference,matchWrap(kit.dp(8f)));
            reviewCheck=new CheckBox(activity);reviewCheck.setText("我已核对原谱（也可在点击应用时确认）");reviewCheck.setTextColor(c.textSecondary);reviewCheck.setTextSize(12f*scale);
            reviewCheck.setOnCheckedChangeListener((button,checked)->setImportEnabled(importable));card.addView(reviewCheck,matchWrap(kit.dp(6f)));
            importButton = kit.action("导入到曲库", c.accent, c.bgRoot);
            importButton.setOnClickListener(v -> importDraft());
            card.addView(importButton, matchWrap(kit.dp(8f)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(12f);
            LinearLayout holder = kit.column();
            holder.addView(card, lp);
            return holder;
        }

        // ---------------------------------------------------------- 模式 / 客户端

        private void switchMode(int index) {
            if (index != MODE_SEARCH) {
                // 离开搜曲谱时收起候选/明细/诊断并停止试听，避免声音继续或旧卡片盖住新模式
                stopCandidatePreview();
                fromCandidates = false;
                if (candidateCard != null) {
                    candidateCard.setVisibility(View.GONE);
                }
                if (diagnosticCard != null) {
                    diagnosticCard.setVisibility(View.GONE);
                }
                if (resultCard != null) {
                    resultCard.setVisibility(View.GONE);
                }
            }
            mode = index;
            for (int i = 0; i < panels.length; i++) {
                if (panels[i] != null) {
                    panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < modeChips.length; i++) {
                if (modeChips[i] != null) {
                    kit.styleChip(modeChips[i], i == index);
                }
            }
        }

        private void rebuildClient() {
            try {
                if (client != null) {
                    // 弹窗独占客户端：配置变化时连线程池一起释放，之后重建新实例
                    client.shutdown();
                }
            } catch (Throwable ignored) {
            }
            try {
                String key = creds.activeKey();
                client = new DeepSeekClient(key, creds.baseUrl(), creds.model());
                assistant = new AiScoreAssistant(client,activity);
                if (banner != null) {
                    banner.setVisibility(isEmpty(key) ? View.VISIBLE : View.GONE);
                }
            } catch (Throwable error) {
                client = null;
                assistant = null;
                if (banner != null) {
                    banner.setVisibility(View.VISIBLE);
                }
            }
        }

        private boolean hasAi() {
            return client != null && client.hasKey() && assistant != null;
        }

        private boolean requireAi() {
            if (hasAi()) {
                return true;
            }
            showHint("AI 尚未配置，请先点上方「去配置」填写 Key 或使用官方通道");
            if (banner != null) {
                banner.setVisibility(View.VISIBLE);
            }
            return false;
        }

        // ---------------------------------------------------------- 四种流程

        private void startSearch() {
            startSearch(null);
        }

        /**
         * 多来源搜索：核心一次返回最多 6 个候选与来源诊断，UI 只负责展示与试听。
         *
         * @param overrideName 重试传入的歌名；null 表示用搜索框内容
         */
        private void startSearch(String overrideName) {
            if (busy) {
                return;
            }
            String typed = overrideName == null
                    ? searchInput.getText().toString().trim() : overrideName.trim();
            if (typed.isEmpty()) {
                showHint("先输入歌名再搜索");
                return;
            }
            lastSearchName = typed;
            if (!typed.equals(searchInput.getText().toString().trim())) {
                searchInput.setText(typed);
            }
            prepareSingleFlow();
            hideError();
            final int token = ++workToken;
            lastRetry = () -> startSearch(typed);
            setBusy("正在查找可演奏曲谱…",
                    "① 先查内置离线曲库，支持拼音和别名\n"
                            + "② 本地无匹配时联网搜索 MIDI / ABC / 四线谱\n"
                            + "③ 试听对照后直接加入练习库，无需导出再导入");
            try {
                if(assistant==null)assistant=new AiScoreAssistant(client,activity);
                assistant.searchCandidates(typed, MAX_CANDIDATES,
                        new AiScoreAssistant.CandidatesCallback() {
                            @Override
                            public void onSuccess(List<AiScoreAssistant.ScoreDraft> candidates,
                                                  List<String> triedSources,
                                                  List<String> skippedSources) {
                                if (!canTouch() || token != workToken) {
                                    return;
                                }
                                setIdle();
                                onCandidates(candidates, triedSources, skippedSources);
                            }

                            @Override
                            public void onError(DeepSeekClient.AiError e) {
                                if (!canTouch() || token != workToken) {
                                    return;
                                }
                                setIdle();
                                if (mode != MODE_SEARCH) {
                                    switchMode(MODE_SEARCH);
                                }
                                if (e != null && "no_candidates".equals(e.code)) {
                                    // 0 候选是「正常但没找到」，用诊断卡而不是红色失败条
                                    showNoCandidateDiagnostic(e.message);
                                    findOnlineReferences(typed);
                                    return;
                                }
                                showError("搜索失败：" + AiSetupDialog.errorText(e),
                                        "可用浏览器继续找谱，或导入已有的 MIDI、PDF、照片。",
                                        () -> startSearch(typed));
                            }
                        });
            } catch (Throwable error) {
                if (canTouch() && token == workToken) {
                    setIdle();
                    showError("搜索启动失败，请稍后重试。",
                            "可用浏览器继续找谱，或导入已有的 MIDI、PDF、照片。", lastRetry);
                }
            }
        }

        private void findOnlineReferences(String name){
            if(busy||!canTouch()||!requireAi())return;String query=name==null?"":name.trim();if(query.isEmpty()){showHint("请填写歌名与作者");return;}
            stopCandidatePreview();final int token=++workToken;setBusy("正在全网搜索谱页…","不限定网站；网页结果与可直接演奏的音符分开显示，当前稿保持不变。");
            client.search(query+" 曲谱",6,new DeepSeekClient.SearchCallback(){
                public void onSuccess(List<DeepSeekClient.SearchResult> found){if(!canTouch()||token!=workToken)return;setIdle();LinearLayout list=kit.column();ScrollView scroll=new ScrollView(activity);scroll.addView(list);final android.app.AlertDialog[] sheet={null};int count=0;
                    if(found!=null)for(DeepSeekClient.SearchResult item:found){if(item==null||count>=6)continue;
                        try{BrowserScoreSearch.pageUrl(item.url);}catch(IllegalArgumentException invalid){continue;}
                        count++;
                        TextView title=kit.text(item.title,15f*scale,c.textPrimary);title.setMaxLines(3);title.setEllipsize(android.text.TextUtils.TruncateAt.END);list.addView(title,matchWrap(kit.dp(16f)));
                        TextView source=kit.text(Uri.parse(item.url).getHost()+" · 网页来源",12f*scale,c.textSecondary);list.addView(source,matchWrap(kit.dp(5f)));
                        String excerpt=item.snippet==null?"":item.snippet.replaceAll("\\s+"," ").trim();if(excerpt.length()>160)excerpt=excerpt.substring(0,160)+"…";
                        if(!excerpt.isEmpty()){TextView snippet=kit.text(excerpt,12f*scale,c.textSecondary);snippet.setMaxLines(3);snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);list.addView(snippet,matchWrap(kit.dp(5f)));}
                        list.addView(kit.text("尚未转换为演奏音符",12f*scale,c.accent),matchWrap(kit.dp(5f)));
                        LinearLayout row=kit.row();TextView open=kit.actionOutline("查看原网页"),read=kit.actionOutline("读取文本谱核对");open.setOnClickListener(v->openUrl(item.url));read.setOnClickListener(v->{if(sheet[0]!=null)sheet[0].dismiss();readOnlineReference(item);});LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1);a.rightMargin=kit.dp(8f);row.addView(open,a);row.addView(read,new LinearLayout.LayoutParams(0,-2,1));list.addView(row,matchWrap(kit.dp(6f)));
                    }
                    if(count==0){showHint("本次检索未返回可打开的谱页，可用浏览器继续搜索；当前稿保持不变。");return;}
                    list.addView(kit.text("图片/PDF请从原网页取得后导入；MIDI可下载后导入。部分来源需要登录或购买。标题、摘要与介绍不作为演奏音符。",12f*scale,c.textSecondary),matchWrap(kit.dp(12f)));
                    sheet[0]=AppDialogs.form(activity,"全网谱页 · "+count+"个来源",scroll,null,18);
                }
                public void onError(DeepSeekClient.AiError e){if(!canTouch()||token!=workToken)return;setIdle();showError("联网检索失败："+AiSetupDialog.errorText(e),"当前原稿保留，可稍后重试。",()->findOnlineReferences(query));}
            });
        }

        private void readOnlineReference(DeepSeekClient.SearchResult item){
            if(busy||!canTouch()||!requireAi())return;final int token=++workToken;final String original=transcriptionInput.getText().toString();setBusy("正在读取所选来源…","仅解析网页实际提供的音符，不根据标题续写旋律。");
            client.fetchPage(item.url,new DeepSeekClient.FetchCallback(){
                public void onSuccess(DeepSeekClient.PageContent page){if(!canTouch()||token!=workToken)return;
                    if(page==null||!ScoreReferenceCheck.hasWrittenNotation(page.content)){setIdle();AppDialogs.message(activity,"这个页面不是可读取的文本谱","网页可能只提供图片、PDF或需要登录。当前稿保留；可查看原网页，取得公开谱图后用相册识谱导入。","查看原网页",(d,w)->openUrl(item.url),"保留当前稿",null,null,null);return;}
                    assistant.convertPlainText(item.title,page.content,new AiScoreAssistant.DraftCallback(){
                        public void onSuccess(AiScoreAssistant.ScoreDraft d){if(!canTouch()||token!=workToken)return;setIdle();if(d.eventsText.isEmpty()&&d.midiBytes==null){showHint("网页转录仍需校对，当前稿保留；请查看原网页。");return;}
                            String comparison=ScoreReferenceCheck.compare(original,d.transcription);
                            AppDialogs.message(activity,"采用这个网页版本？",comparison+"\n\n来源："+item.url+"\n采用后可试听、校对，也可恢复联网前原稿。","采用这个版本",(dialog,which)->{
                                if(!canTouch()||token!=workToken)return;stopCandidatePreview();if(referenceOriginal==null){referenceOriginal=new AiScoreAssistant.ScoreDraft(titleInput.getText().toString(),original,draftDirty?"":currentEvents,currentSource,draftDirty?null:currentMidi,!draftDirty&&currentDeterministic,currentSourceUrl,"","",currentOmittedNotes);referenceOriginalDocument=currentDocument;}
                                currentDocument=null;showDraft(new AiScoreAssistant.ScoreDraft(d.title,d.transcription,d.eventsText,d.source+" · 网页参考版，请试听核对",d.midiBytes,false,item.url,d.omittedNotes),d.title);restoreReference.setVisibility(View.VISIBLE);
                            },"保留当前稿",null,null,null);
                        }
                        public void onError(DeepSeekClient.AiError e){if(!canTouch()||token!=workToken)return;setIdle();showError("来源解析失败："+AiSetupDialog.errorText(e),"当前稿保留，请对照原网页。",()->readOnlineReference(item));}
                    });
                }
                public void onError(DeepSeekClient.AiError e){if(!canTouch()||token!=workToken)return;setIdle();showError("来源读取失败："+AiSetupDialog.errorText(e),"当前稿保留，可打开来源查看。",()->readOnlineReference(item));}
            });
        }

        private void startUrl() {
            if (busy || !requireAi()) {
                return;
            }
            prepareSingleFlow();
            final String url = urlInput.getText().toString().trim();
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                showHint("请输入 http:// 或 https:// 开头的网页地址");
                return;
            }
            final int token = ++workToken;
            // URL 是「抓取 + 解析」两步流程，统一登记重试入口，避免解析阶段失败时重试指错模式。
            lastRetry = () -> startUrl();
            setBusy("正在抓取网页…", "① 抓取网页 → ② 规则解析简谱/ABC（必要时 AI 规范化后再解析）");
            try {
                client.fetchPage(url, new DeepSeekClient.FetchCallback() {
                    @Override
                    public void onSuccess(DeepSeekClient.PageContent page) {
                        if (!canTouch() || token != workToken) {
                            return;
                        }
                        if (page == null || isEmpty(page.content)) {
                            setIdle();
                            showError("网页没有可读的正文。",
                                    "确认链接是公开网页，或改用「粘贴简谱」。", () -> startUrl());
                            return;
                        }
                        String title = isEmpty(page.title) ? url : page.title;
                        if(!ScoreReferenceCheck.hasWrittenNotation(page.content)){setIdle();showError("网页正文没有可解析的实际谱面音符。","若谱面为图片/PDF，请从原网页取得后用相册识谱导入；不能用页面介绍补成旋律。",()->openUrl(url));return;}
                        setBusy("正在解析网页文本…", "规则解析简谱/ABC；解析不出才会让 AI 规范化");
                        try {
                            assistant.convertPlainText(title, page.content,
                                    draftCallback(token, title,
                                            "确认链接是公开曲谱网页，或改用「粘贴简谱」把文本直接粘进来。"));
                        } catch (Throwable error) {
                            setIdle();
                            showError("解析失败，请稍后重试。",
                                    "可以改用「粘贴简谱」把谱面文字直接粘进来。", () -> startUrl());
                        }
                    }

                    @Override
                    public void onError(DeepSeekClient.AiError e) {
                        if (!canTouch() || token != workToken) {
                            return;
                        }
                        setIdle();
                        showError("网页抓取失败：" + AiSetupDialog.errorText(e),
                                "确认链接能公开访问，或改用「粘贴简谱」。", () -> startUrl());
                    }
                });
            } catch (Throwable error) {
                setIdle();
                showError("网页抓取失败，请稍后重试。",
                        "确认链接能公开访问，或改用「粘贴简谱」。", () -> startUrl());
            }
        }

        private void startText(EditText textInput, EditText titleInputView, String fallbackTitle) {
            if (busy || !requireAi()) {
                return;
            }
            prepareSingleFlow();
            final String raw = textInput == null ? "" : textInput.getText().toString().trim();
            if (raw.isEmpty()) {
                showHint("先粘贴简谱/ABC 文本再解析");
                return;
            }
            String title = titleInputView == null ? "" : titleInputView.getText().toString().trim();
            final String titleHint = title.isEmpty() ? fallbackTitle : title;
            runConversion(
                    "正在解析文本…",
                    "规则解析简谱/ABC；解析不出才会请 AI 转写成规范简谱后再解析",
                    titleHint,
                    "规范写法示例：BPM:96 换行 1=C 换行 4/4 换行「1 1 5 5 | 6 6 5 -」；"
                            + "也可以直接粘贴含 X:/K: 的 ABC 文本。",
                    cb -> assistant.convertPlainText(titleHint, raw, cb));
        }

        private void pickImage() {
            if(busy||!canTouch()||!requireAi())return;
            prepareSingleFlow();if(handler==null)return;
            final int selection=++workToken;
            try{handler.requestDocument(new DocumentCallback(){
                public void onDocument(ScoreImageDocument document){
                    if(!canTouch()||selection!=workToken||document==null)return;
                    currentDocument=document;
                    imageInfo.setText(document.name+" · "+document.pageLabel());
                    String title=stripExtension(document.name,"AI识谱");
                    runConversion("正在逐页识别…","按页读取 → 校验指法与节奏 → 对照原谱",title,
                        "可保留原谱并修改转录，再重新校验。",
                        cb->assistant.convertDocument(title,document,(page,total)->{if(canTouch()&&busy)progressText.setText("正在识别第"+page+" / "+total+"段");},cb));
                }
                public void error(String message){if(canTouch()&&selection==workToken)showHint(message);}
            });}catch(Exception e){showHint("无法选择谱面，请重试");}
        }

        /** 文本/图片草稿的「重新解析文本」：先规则解析，必要时才让 AI 规范化。 */
        private void reparseFromTranscription() {
            if (busy || !requireAi() || currentMidi != null) {
                return;
            }
            final String transcription = transcriptionInput.getText().toString().trim();
            if (transcription.isEmpty()) {
                showHint("转录内容为空，无法重新解析");
                return;
            }
            if(currentDocument!=null){
                try{if(ScorePageComposer.split(transcription).size()!=currentDocument.editableSections()){showHint("请保留全部 %%PAGE: 分页标记，避免漏页");return;}}
                catch(Exception e){showHint(e.getMessage());return;}
            }
            String rawTitle = titleInput.getText().toString().trim();
            final String titleHint = rawTitle.isEmpty() ? "AI 导入曲谱" : rawTitle;
            runConversion(
                    "正在重新解析文本…",
                    "规则解析简谱/ABC；解析不出才会请 AI 规范化为简谱后再解析",
                    titleHint,
                    "可先修改上方转录再解析；规范简谱示例见「粘贴简谱」页。",
                    cb -> assistant.convertPlainText(titleHint, transcription, cb));
        }

        /** 统一处理「忙碌 → 成功展示 / 失败重试」；converter 内部决定调哪个 AI 接口。 */
        private void runConversion(final String busyText, final String stepsText,
                                   final String fallbackTitle, final String suggestion,
                                   final Converter converter) {
            if (!requireAi()) {
                return;
            }
            final int token = ++workToken;
            lastRetry = () -> runConversion(busyText, stepsText, fallbackTitle, suggestion, converter);
            setBusy(busyText, stepsText);
            try {
                converter.start(draftCallback(token, fallbackTitle, suggestion));
            } catch (Throwable error) {
                if (canTouch() && token == workToken) {
                    setIdle();
                    showError("请求失败，请稍后重试。", suggestion, lastRetry);
                }
            }
        }

        private AiScoreAssistant.DraftCallback draftCallback(final int token,
                                                             final String fallbackTitle,
                                                             final String suggestion) {
            return new AiScoreAssistant.DraftCallback() {
                @Override
                public void onSuccess(AiScoreAssistant.ScoreDraft draft) {
                    if (!canTouch() || token != workToken) {
                        return;
                    }
                    setIdle();
                    hideError();
                    showDraft(draft, fallbackTitle);
                }

                @Override
                public void onError(DeepSeekClient.AiError e) {
                    if (!canTouch() || token != workToken) {
                        return;
                    }
                    setIdle();
                    showError("转换失败：" + AiSetupDialog.errorText(e), suggestion, lastRetry);
                }
            };
        }

        private void cancelWork() {
            if(savingDraft){showHint("正在保存，请稍候");return;}
            workToken++; // 让仍在路上的回调失效
            busy = false;
            stopCandidatePreview();
            try {
                if (client != null) {
                    client.shutdown();
                    client=null;assistant=null;
                }
            } catch (Throwable ignored) {
            }
            setIdle();
            rebuildClient();
            showHint("已取消本次请求");
        }

        // ---------------------------------------------------------- 结果处理

        private void showDraft(AiScoreAssistant.ScoreDraft draft, String fallbackTitle) {
            bindingDraft=true;draftDirty=false;
            currentSource=draft==null?"":draft.source;
            currentOmittedNotes=draft==null?0:draft.omittedNotes;
            byte[] midi = null;
            String events = "";
            String transcription = "";
            String title = "";
            boolean deterministic = false;
            String sourceUrl = "";
            if (draft != null) {
                midi = draft.midiBytes;
                deterministic = draft.deterministic;
                sourceUrl = draft.sourceUrl == null ? "" : draft.sourceUrl.trim();
                events = stripCodeFences(draft.eventsText);
                transcription = draft.transcription == null ? "" : draft.transcription;
                title = draft.title == null ? "" : draft.title;
            }
            if (isEmpty(title)) {
                title = isEmpty(fallbackTitle) ? "AI 导入曲谱" : fallbackTitle;
            }
            currentMidi = midi != null && midi.length > 0 ? midi : null;
            currentEvents = events;
            deterministic=deterministic&&currentDocument==null;
            currentDeterministic = deterministic;
            currentSourceUrl = sourceUrl;
            titleInput.setText(title);
            applyCredibility();
            if (detailBackButton != null) {
                detailBackButton.setVisibility(fromCandidates ? View.VISIBLE : View.GONE);
            }
            if (candidateCard != null) {
                candidateCard.setVisibility(View.GONE);
            }
            if (diagnosticCard != null) {
                diagnosticCard.setVisibility(View.GONE);
            }

            String summary;
            if (currentMidi != null) {
                // MIDI 草稿：直接用内置解析器导入，不展示/不依赖事件文本
                transcriptionSection.setVisibility(View.GONE);
                eventsSection.setVisibility(View.GONE);
                regenerateButton.setVisibility(View.GONE);
                midiBox.setVisibility(View.VISIBLE);
                midiInfo.setText("已下载 " + formatSize(currentMidi.length)
                        + " · 导入时由 App 内置 MIDI 解析器提取音符；如解析失败会保留原文件。");
                summaryLabel.setText("确定性源文件：无需 AI 转换，可直接导入。");
                summaryLabel.setTextColor(c.success);
                String inspected=handler==null?null:handler.describeMidi(currentMidi);
                importable=inspected!=null;
                summaryLabel.setText(inspected==null?"MIDI没有通过音符解析，请重新选谱":inspected);
                importButton.setText("保存 MIDI 并练习");
                if (fromCandidates) {
                    showHint("已选择 MIDI 候选：导入时用内置 MIDI 解析器；可点「重新选」返回候选");
                } else {
                    showHint("");
                }
            } else {
                // 文本/图片草稿：展示可编辑转录和事件预览
                midiBox.setVisibility(View.GONE);
                transcriptionSection.setVisibility(View.VISIBLE);
                eventsSection.setVisibility(View.VISIBLE);
                regenerateButton.setVisibility(View.VISIBLE);
                transcriptionInput.setText(transcription);
                transcriptionHint.setText(mode == MODE_IMAGE
                        ? "图片识别可能有误：先对照原谱修改转录，再点「重新解析文本」。"
                        : "先改转录再点「重新解析文本」：App 会先规则解析，失败才让 AI 规范化。");
                eventsPreview.setText(isEmpty(events)
                        ? "（还没有可导入的事件；请修改转录后点「重新解析文本」）" : events);
                importButton.setText("保存并用于练习");
                summary = describe(events);
                if (isEmpty(events)) {
                    importable = false;
                    summaryLabel.setText("⚠ 没有生成可演奏的事件，请修改转录后重新解析。");
                    summaryLabel.setTextColor(c.danger);
                } else if (summary == null) {
                    importable = false;
                    summaryLabel.setText("⚠ 事件未通过曲库校验（可能超出 C4—D#6 音域或格式不符），"
                            + "请修改转录后重新解析。");
                    summaryLabel.setTextColor(c.danger);
                } else {
                    importable = true;
                    summaryLabel.setText(summary);
                    summaryLabel.setTextColor(c.success);
                }
                // 图片草稿且非确定性：明确提示需要校对；候选明细则提示可返回重选
                if (fromCandidates) {
                    showHint("已选择该候选：可编辑/导入，或点「重新选」返回候选列表");
                } else if (mode == MODE_IMAGE && !deterministic) {
                    showHint("识别完成：请对照原谱核对音符、时值和速度后保存。");
                } else {
                    showHint("");
                }
            }
            validatedTranscription=transcriptionInput.getText().toString();bindingDraft=false;
            boolean numeric=currentMidi==null&&!UkuleleTabParser.looksLikeTab(validatedTranscription)&&!validatedTranscription.matches("(?s).*\\bX:.*")&&JianpuParser.looksLikeJianpu(validatedTranscription);
            numericOctave.setVisibility(numeric?View.VISIBLE:View.GONE);numericTiming.setVisibility(numeric?View.VISIBLE:View.GONE);
            numericRepeat.setVisibility(numeric?View.VISIBLE:View.GONE);
            numericOctave.setText(validatedTranscription.contains("OCTAVE:1")||validatedTranscription.contains("OCTAVE:+1")?"恢复原八度":"整首升八度");
            numericTiming.setText(validatedTranscription.contains("TIMING:BAR_DRAFT")?"恢复谱面时值":"小节均分草稿");
            numericRepeat.setText(validatedTranscription.contains("REPEAT:VISIBLE")?"反复：按可见顺序（点击展开）":"反复：展开演奏（点击按可见顺序）");
            reviewCheck.setVisibility(currentDeterministic?View.GONE:View.VISIBLE);reviewCheck.setChecked(false);
            originalPreview.setVisibility(currentDocument==null?View.GONE:View.VISIBLE);
            eventsSection.setVisibility(View.GONE);
            qualityLabel.setText(currentSource+(currentOmittedNotes>0?(currentEvents.isEmpty()?"\n超出音域 "+currentOmittedNotes+"音；请整首升八度或校对后生成指引":"\n超出音域 "+currentOmittedNotes+"音，保留时值并标记缺失"):"")+(currentDocument==null?"":"\n原文件将与指引一起保存 · "+currentDocument.pageLabel()));
            setImportEnabled(importable);
            resultCard.setVisibility(View.VISIBLE);
            scrollToResult();
        }

        /** 结果区可信度：绿色=确定性解析，黄色=AI 转录需校对；同时展示可点击来源。 */
        private void applyCredibility() {
            String sourceLabel;
            if (currentMidi != null) {
                sourceLabel = "MIDI";
            } else {
                String host = hostOf(currentSourceUrl);
                sourceLabel = host.isEmpty() ? "ABC/简谱规则" : host;
            }
            if (currentDeterministic) {
                credibilityBadge.setText("✔ 规则解析 · 来源: " + sourceLabel);
                credibilityBadge.setTextColor(c.success);
                credibilityBadge.setBackground(kit.bg(AppTheme.withAlpha(c.success, 0.16f), 999f));
            } else if (currentMidi != null) {
                // 契约里 MIDI 草稿都是确定性下载；万一来源标记缺失也不要误标成 AI 转录
                credibilityBadge.setText("⚠ MIDI 文件，请核对来源");
                credibilityBadge.setTextColor(c.warn);
                credibilityBadge.setBackground(kit.bg(AppTheme.withAlpha(c.warn, 0.16f), 999f));
            } else {
                credibilityBadge.setText("⚠ 识谱草稿，请校对");
                credibilityBadge.setTextColor(c.warn);
                credibilityBadge.setBackground(kit.bg(AppTheme.withAlpha(c.warn, 0.16f), 999f));
            }
            if (!isEmpty(currentSourceUrl)) {
                sourceUrlView.setVisibility(View.VISIBLE);
                sourceUrlView.setText("来源：" + currentSourceUrl);
                sourceUrlView.setOnClickListener(v -> openSourceUrl());
            } else if (currentMidi != null) {
                sourceUrlView.setVisibility(View.VISIBLE);
                sourceUrlView.setText("来源：下载到的 MIDI 文件（已通过本机 MIDI 解析校验）");
                sourceUrlView.setOnClickListener(null);
                sourceUrlView.setClickable(false);
            } else if (currentDeterministic) {
                sourceUrlView.setVisibility(View.VISIBLE);
                sourceUrlView.setText("来源：App 本机规则解析（无外部链接）");
                sourceUrlView.setOnClickListener(null);
                sourceUrlView.setClickable(false);
            } else {
                sourceUrlView.setVisibility(View.GONE);
            }
        }

        private void openSourceUrl() {
            if (!canTouch() || isEmpty(currentSourceUrl)) {
                return;
            }
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentSourceUrl)));
            } catch (Throwable error) {
                toast("无法打开来源链接");
            }
        }

        private String describe(String events) {
            if (handler == null) {
                return null;
            }
            try {
                return handler.describe(events);
            } catch (Throwable error) {
                return null;
            }
        }

        private void setNumericOption(String key,String value){
            if(busy||savingDraft||currentMidi!=null)return;
            stopCandidatePreview();String raw=transcriptionInput.getText().toString();
            java.util.regex.Pattern option=java.util.regex.Pattern.compile("(?m)^"+key+":\\+?"+value+"\\s*$");boolean remove=option.matcher(raw).find();
            try{List<String> pieces=ScorePageComposer.split(raw);boolean sections=!pieces.isEmpty();if(!sections)pieces=java.util.Collections.singletonList(raw);StringBuilder updated=new StringBuilder();
                for(int i=0;i<pieces.size();i++){if(sections)updated.append("%%PAGE:").append(i+1).append('\n');String body=pieces.get(i).replaceAll("(?m)^"+key+":.*$","");if(!remove)updated.append(key).append(':').append(value).append('\n');updated.append(body.trim()).append('\n');}
                transcriptionInput.setText(updated.toString().trim());reparseFromTranscription();
            }catch(Exception e){showHint(e.getMessage());}
        }

        private void importDraft() {
            if(!canTouch()||busy||!importable||draftDirty)return;
            stopCandidatePreview();
            if(!currentDeterministic&&!reviewCheck.isChecked()){
                AppDialogs.message(activity,"核对并应用曲谱","请确认已对照原谱检查音符、节奏和完整性。确认后保存原谱与指引，并打开练习。试听不是保存的前置条件。","确认并应用",(d,w)->{if(canTouch()){reviewCheck.setChecked(true);importDraft();}},"返回校对",null,null,null);
                return;
            }
            if(handler==null)return;
            String title=titleInput.getText().toString().trim();if(title.isEmpty())title="AI导入曲谱";
            final AiScoreAssistant.ScoreDraft draft=new AiScoreAssistant.ScoreDraft(title,transcriptionInput.getText().toString(),currentEvents,currentSource,currentMidi,currentDeterministic,currentSourceUrl,"","",currentOmittedNotes);
            final int token=++workToken;
            savingDraft=true;
            setBusy("正在保存原谱与指引…","");
            cancelButton.setEnabled(false);
            handler.importReviewed(draft,currentDocument,error->{activity.runOnUiThread(()->{
                if(!canTouch()||token!=workToken)return;
                savingDraft=false;cancelButton.setEnabled(true);setIdle();
                if(error!=null){showError("保存失败："+error,"原稿仍在，请重试。",null);return;}
                toast("曲谱和指引已保存");dialog.dismiss();
            });});
        }
        private void showOriginal(){
            if(currentDocument==null||!canTouch())return;
            final ScoreImageDocument doc=currentDocument;
            Dialog viewer=new Dialog(activity);originalViewer=viewer;viewer.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout body=kit.column();body.setPadding(kit.dp(14),kit.dp(14),kit.dp(14),kit.dp(14));body.setBackgroundColor(c.bgRoot);
            TextView label=kit.textBold("",14f*scale,c.textPrimary);body.addView(label);
            ScrollView scroll=new ScrollView(activity);ImageView image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);scroll.addView(image);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            int[] page={0};Runnable render=()->{label.setText(doc.name+" · 第"+(doc.firstPage+page[0])+"页");byte[] b=doc.pages.get(page[0]);image.setImageBitmap(BitmapFactory.decodeByteArray(b,0,b.length));scroll.scrollTo(0,0);};
            LinearLayout row=kit.row();TextView prev=kit.actionOutline("上一页"),next=kit.actionOutline("下一页"),close=kit.actionOutline("返回校对");
            prev.setOnClickListener(v->{if(page[0]>0){page[0]--;render.run();}});next.setOnClickListener(v->{if(page[0]+1<doc.pages.size()){page[0]++;render.run();}});close.setOnClickListener(v->viewer.dismiss());
            for(TextView b:new TextView[]{prev,next,close}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(kit.dp(3),0,kit.dp(3),0);row.addView(b,lp);}body.addView(row);viewer.setOnDismissListener(d->{image.setImageDrawable(null);if(originalViewer==viewer)originalViewer=null;});viewer.setContentView(body);viewer.show();if(viewer.getWindow()!=null)viewer.getWindow().setLayout(-1,-1);render.run();
        }

        private void setImportEnabled(boolean enabled) {
            if (importButton == null) {
                return;
            }
            enabled=enabled&&!busy&&!draftDirty;
            boolean reviewed=currentDeterministic||reviewCheck!=null&&reviewCheck.isChecked();
            importButton.setText(currentMidi!=null?"应用 MIDI 到曲库":reviewed?"应用到曲库并练习":"核对并应用到曲库");
            if(detailPreview!=null)detailPreview.setEnabled(importable&&!busy&&!draftDirty);
            importButton.setEnabled(enabled);
            importButton.setAlpha(enabled ? 1f : 0.5f);
        }

        private void scrollToResult() {
            if (contentScroll == null) {
                return;
            }
            contentScroll.post(() -> {
                if (canTouch()) {
                    contentScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }

        // ---------------------------------------------------------- 状态展示

        private void setBusy(String text, String steps) {
            busy = true;
            if(transcriptionInput!=null)transcriptionInput.setEnabled(false);
            if (progressBox != null) {
                progressBox.setVisibility(View.VISIBLE);
            }
            if (progressText != null) {
                progressText.setText(isEmpty(text) ? "处理中…" : text);
            }
            if (progressSteps != null) {
                if (isEmpty(steps)) {
                    progressSteps.setVisibility(View.GONE);
                } else {
                    progressSteps.setText(steps);
                    progressSteps.setVisibility(View.VISIBLE);
                }
            }
            hideError();
            setImportEnabled(false);
            hideKeyboard();
        }

        private void setIdle() {
            busy = false;
            if(transcriptionInput!=null)transcriptionInput.setEnabled(true);
            if (progressBox != null) {
                progressBox.setVisibility(View.GONE);
            }
            // 取消/失败后恢复上一次成功结果的可导入状态，不能因为一次新请求就永久禁用导入
            setImportEnabled(importable);
        }

        private void showError(String text, String suggestion, Runnable retry) {
            if (errorBox == null) {
                return;
            }
            lastRetry = retry;
            errorText.setText(isEmpty(text) ? "请求失败，请稍后重试" : text);
            if (errorSuggestion != null) {
                if (isEmpty(suggestion)) {
                    errorSuggestion.setVisibility(View.GONE);
                } else {
                    errorSuggestion.setText("建议：" + suggestion);
                    errorSuggestion.setVisibility(View.VISIBLE);
                }
            }
            if (retryButton != null) {
                retryButton.setVisibility(retry == null ? View.GONE : View.VISIBLE);
            }
            errorBox.setVisibility(View.VISIBLE);
            scrollToResult();
        }

        private void hideError() {
            if (errorBox != null) {
                errorBox.setVisibility(View.GONE);
            }
        }

        private void showHint(String text) {
            if (hintLine == null) {
                return;
            }
            if (isEmpty(text)) {
                hintLine.setVisibility(View.GONE);
            } else {
                hintLine.setText(text);
                hintLine.setVisibility(View.VISIBLE);
            }
        }

        // ---------------------------------------------------------- 小工具

        private EditText input(String hint, boolean multiLine) {
            EditText edit = new EditText(activity);
            edit.setHint(hint);
            edit.setSingleLine(!multiLine);
            if (multiLine) {
                edit.setGravity(Gravity.TOP);
                edit.setMinLines(3);
            }
            AppDialogs.themeInput(edit, 15f, multiLine);
            edit.setMinHeight(kit.dp(48f));
            return edit;
        }

        private LinearLayout.LayoutParams matchWrap(int topMargin) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topMargin;
            return lp;
        }

        private TextView compactButton(String label, int color) {
            TextView button = kit.textBold(label, 13f * scale, color);
            button.setGravity(Gravity.CENTER);
            button.setPadding(kit.dp(10f), kit.dp(8f), kit.dp(10f), kit.dp(8f));
            button.setMinHeight(kit.dp(40f));
            button.setBackground(kit.ripple(c.surface2, 12f));
            button.setClickable(true);
            button.setFocusable(true);
            return button;
        }

        private void toast(String text) {
            try {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        private void hideKeyboard() {
            try {
                InputMethodManager imm = (InputMethodManager)
                        activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                View focus = activity.getCurrentFocus();
                if (imm != null && focus != null) {
                    imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                }
            } catch (Throwable ignored) {
            }
        }

        /** 异步回调落回主线程时 Activity 可能已经 finish/destroy；此时一律不再碰 UI。 */
        private boolean canTouch() {
            if (!alive || activity == null) {
                return false;
            }
            try {
                return !activity.isFinishing() && !activity.isDestroyed();
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void cleanup() {
            if(originalViewer!=null){originalViewer.dismiss();originalViewer=null;}
            currentDocument=null;
            alive = false;
            if(handler!=null)handler.setPreviewObserver(null);
            workToken++;
            busy = false;
            if (handler != null) {
                try {
                    handler.stopPreview();
                } catch (Throwable ignored) {
                }
            }
            if (client != null) {
                try {
                    client.shutdown();
                } catch (Throwable ignored) {
                }
                client = null;
            }
            assistant = null;
            hideKeyboard();
        }
    }

    // ------------------------------------------------------------------ 静态工具

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String stripCodeFences(String value) {
        if (isEmpty(value)) {
            return "";
        }
        try {
            String stripped = AiScoreAssistant.stripCodeFences(value);
            return isEmpty(stripped) ? value.trim() : stripped.trim();
        } catch (Throwable ignored) {
            return value.trim();
        }
    }

    private static String stripExtension(String name, String fallback) {
        if (isEmpty(name)) {
            return fallback;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return isEmpty(base) ? fallback : base;
    }

    /** 来源 URL 的域名，用来在徽标里显示「来源: abcnotation.com / MIDI」。 */
    private static String hostOf(String url) {
        if (isEmpty(url)) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
