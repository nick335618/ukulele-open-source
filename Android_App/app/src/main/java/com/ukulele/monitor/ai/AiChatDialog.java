package com.ukulele.monitor.ai;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ukulele.monitor.ui.AppDialogs;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 音乐搭子 v2 全屏聊天弹窗。
 *
 * <p>v2 与 v1 的差别（都是用户反馈）：
 * <ul>
 *   <li>顶栏只留「头像+名字+口头禅」和「切换 / 语音 / 更多」三个紧凑入口，
 *       人格编辑、清空、新建/删除助手、关闭都收进「更多」菜单，窄屏不再一行挤四个大按钮；</li>
 *   <li>最多 4 个搭子，每个有独立人格与记忆；点「切换」弹出底部切换器，点选即换人，
 *       新建后直接进入编辑，满 4 个时新建入口变灰并给出提示；</li>
 *   <li>聊天不限音乐话题，空了有引导建议，助手气泡带头像、「思考中」是三点动画，
 *       失败气泡可在原地重试；</li>
 *   <li>语音支持连续对话：开启后搭子朗读完会自动重新开麦，界面上始终有
 *       「正在听… / 正在说… / 连续对话中」的状态提示；</li>
 *   <li>输入区多行自适应（最多 4 行），320dp 窄屏和大字号下不裁字。</li>
 * </ul>
 *
 * <p>线程与生命周期：DeepSeekClient/VoiceChatManager 的回调都在主线程，但这里每个回调仍会
 * 先过 {@link #canTouch()}（alive + Activity 未 finish/destroy）再碰 UI；弹窗关闭时停止识别、
 * release 语音、cancel 并关闭客户端、保存当前人格，迟到回调一律丢弃。
 *
 * <p>多助手隔离：切换/新建/删除期间禁止网络请求在途（{@link #requesting}），避免上一轮回复
 * 被 CompanionStore 记到新激活的搭子名下；切换后重载该槽历史并同步 engine 人格与语音情感档案。
 */
public final class AiChatDialog {

    /** MainActivity 执行 AI 回复附带的行为标签（PRACTICE/LED/METRONOME/SCORE/PLAY/TIP）。 */
    public interface ActionHandler {
        void onAction(String type, String label, String param);
    }

    private static final int REQ_MIC = 0x5A11;
    private static final String RECORD_AUDIO = Manifest.permission.RECORD_AUDIO;
    /** 「思考中」三点动画的步进间隔。 */
    private static final long TYPING_TICK_MS = 300L;
    /** 连续对话：朗读结束后稍等一下再自动开麦，避免尾音被识别进去。 */
    private static final long AUTO_LISTEN_DELAY_MS = 420L;
    private static final String[] TYPING_DOTS = {"●", "●●", "●●●"};

    private AiChatDialog() {
    }

    /**
     * 打开聊天弹窗。
     *
     * @param activity    宿主 Activity
     * @param credentials 凭据存储（可为 null，内部新建）
     * @param store       搭子持久化（可为 null，内部新建）
     * @param appContext  提供给 system prompt 的 App 上下文摘要（练习分数/当前曲目等），可为空
     * @param handler     行为标签执行者，可为 null（此时 actions 只显示不执行）
     */
    public static void show(final Activity activity, final AiCredentials credentials,
                            final CompanionStore store, final String appContext,
                            final ActionHandler handler) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            AiCredentials creds = credentials != null ? credentials : new AiCredentials(activity);
            CompanionStore companionStore = store != null ? store : new CompanionStore(activity);
            new Ui(activity, creds, companionStore, appContext, handler).open();
        } catch (Throwable ignored) {
            // 入口点击不能因为主题/资源异常变成崩溃
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private static final class Ui {
        private final Activity activity;
        private final AiCredentials creds;
        private final CompanionStore store;
        private final String appContext;
        private final ActionHandler handler;
        private final UiKit kit;
        private final AppTheme.Colors c;
        private final float scale;
        private final Dialog dialog;
        private final Handler uiHandler = new Handler(Looper.getMainLooper());
        private final int screenWidth;

        private boolean alive = true;
        private DeepSeekClient client;
        private CompanionEngine engine;
        private CompanionPersona persona;
        private VoiceChatManager voice;
        private int currentSlot;

        private boolean voiceOn;
        private boolean continuous;
        /** 正在等 AI 回复（含首次问候）；期间切换搭子/清空/编辑会被拦住。 */
        private boolean requesting;
        private boolean recognizing;
        private boolean speakingNow;
        private boolean autoListenPending;
        private boolean permissionPending;
        private String preparingVoice;
        private String voiceDraftPrefix = "";

        private TextView avatarView;
        private TextView nameView;
        private TextView taglineView;
        private TextView switchButton;
        private TextView voiceButton;
        private TextView moreButton;
        private LinearLayout banner;
        private TextView bannerText;
        private TextView hintLine;
        private ScrollView messageScroll;
        private LinearLayout messages;
        private View emptyState;
        private LinearLayout typingRow;
        private TextView typingBubble;
        private TextView voiceStatus;
        private TextView continuousChip;
        private EditText input;
        private TextView micButton;
        private TextView sendButton;

        private Dialog switcherDialog;
        private Dialog editorDialog;
        private CompanionPersona editorPersona;
        private EditText editorAvatar;
        private EditText editorName;
        private EditText editorTagline;
        private EditText editorPersonality;
        private EditText editorCatchphrase;
        private SeekBar editorPitch;
        private SeekBar editorRate;
        private TextView editorPitchLabel;
        private TextView editorRateLabel;
        private TextView[] editorPresetChips;
        private String[] editorPresetIds;

        private int typingStep;
        private final Runnable typingTicker = new Runnable() {
            @Override
            public void run() {
                if (!alive || typingBubble == null) {
                    return;
                }
                typingStep = (typingStep + 1) % TYPING_DOTS.length;
                typingBubble.setText(TYPING_DOTS[typingStep]);
                uiHandler.postDelayed(this, TYPING_TICK_MS);
            }
        };

        Ui(Activity activity, AiCredentials creds, CompanionStore store,
           String appContext, ActionHandler handler) {
            this.activity = activity;
            this.creds = creds;
            this.store = store;
            this.appContext = appContext == null ? "" : appContext;
            this.handler = handler;
            this.kit = UiKit.create(activity);
            this.c = AppTheme.colors();
            this.scale = AiSetupDialog.textScale(activity);
            this.dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar) {
                @Override public void onWindowFocusChanged(boolean hasFocus) {
                    super.onWindowFocusChanged(hasFocus);
                    if (!hasFocus && recognizing) {
                        continuous = false;
                        stopRecognition();
                    }
                }
            };
            this.screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            this.currentSlot = resolveActiveSlot();
            this.persona = resolvePersona();
            this.voiceOn = safeVoiceEnabled();
        }

        @SuppressWarnings("deprecation")
        void open() {
            LinearLayout root = kit.column();
            root.setBackgroundColor(c.bgRoot);
            root.setFocusableInTouchMode(true);
            root.setPadding(kit.dp(14f), kit.dp(8f), kit.dp(14f), kit.dp(8f));
            root.addView(buildTopBar());
            root.addView(buildBanner());
            root.addView(buildQuickChips());
            root.addView(buildMessageArea(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(buildVoiceRow());
            root.addView(buildInputArea());

            dialog.setContentView(root);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> cleanup());
            dialog.show();
            root.requestFocus();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(c.bgRoot));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
            rebuildClient();
            refreshPersonaHeader();
            syncVoiceUi();
            reloadMessages(true);
        }

        // ---------------------------------------------------------- 顶栏

        private View buildTopBar() {
            LinearLayout bar = kit.row();
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(0, kit.dp(2f), 0, kit.dp(4f));

            avatarView = kit.textBold(avatar(), 24f * scale, c.textPrimary);
            avatarView.setGravity(Gravity.CENTER);
            avatarView.setMinWidth(kit.dp(38f));
            bar.addView(avatarView);

            LinearLayout names = kit.column();
            nameView = kit.textBold(name(), 16.5f * scale, c.textPrimary);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(nameView);
            taglineView = kit.text(tagline(), 11f * scale, c.textSecondary);
            taglineView.setMaxLines(1);
            taglineView.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(taglineView);
            LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            namesLp.leftMargin = kit.dp(9f);
            namesLp.rightMargin = kit.dp(4f);
            bar.addView(names, namesLp);

            switchButton = compactLabel("切换", c.textSecondary);
            switchButton.setContentDescription("切换 AI 搭子");
            switchButton.setOnClickListener(v -> openAssistantSwitcher());
            bar.addView(switchButton);

            voiceButton = compactLabel("语音", voiceOn ? c.accent : c.textTertiary);
            voiceButton.setContentDescription(voiceOn ? "语音朗读已开启" : "语音朗读已关闭");
            voiceButton.setOnClickListener(v -> toggleVoice());
            LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            voiceLp.leftMargin = kit.dp(3f);
            bar.addView(voiceButton, voiceLp);

            moreButton = compactLabel("更多", c.textSecondary);
            moreButton.setContentDescription("更多操作");
            moreButton.setOnClickListener(v -> openMoreMenu());
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            moreLp.leftMargin = kit.dp(3f);
            bar.addView(moreButton, moreLp);
            return bar;
        }

        private View buildBanner() {
            banner = kit.row();
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setBackground(kit.bg(c.surface2, 12f));
            banner.setPadding(kit.dp(11f), kit.dp(7f), kit.dp(7f), kit.dp(7f));
            bannerText = kit.text("", 11.5f * scale, c.warn);
            bannerText.setMaxLines(2);
            banner.addView(bannerText, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = kit.textBold("去配置", 12f * scale, c.accent);
            go.setGravity(Gravity.CENTER);
            go.setPadding(kit.dp(9f), kit.dp(6f), kit.dp(6f), kit.dp(6f));
            go.setMinHeight(kit.dp(34f));
            go.setClickable(true);
            go.setFocusable(true);
            go.setOnClickListener(v -> openSetupDialog());
            banner.addView(go);
            banner.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(4f);
            LinearLayout holder = kit.column();
            holder.addView(banner, lp);
            return holder;
        }

        private View buildQuickChips() {
            final String[] labels = {"推荐好听的歌", "帮我规划今天练琴", "随便聊聊今天", "给我一个灯效灵感", "换个搭子"};
            final Runnable[] actions = new Runnable[]{
                    () -> send("随便推荐几首适合我最近水平的尤克里里歌，说说为什么好听、适合谁。"),
                    () -> send("帮我规划今天 20 分钟的练琴安排，具体到先练什么、再练什么。"),
                    () -> send("今天有点累，随便陪我聊两句吧，不聊练琴也行。"),
                    () -> send("给我一个今晚弹琴的灯效氛围灵感，描述一下颜色和变化。"),
                    this::openAssistantSwitcher
            };
            LinearLayout row = kit.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < labels.length; i++) {
                final Runnable action = actions[i];
                TextView chip = kit.chip(labels[i], false);
                chip.setOnClickListener(v -> {
                    if (!canTouch()) {
                        return;
                    }
                    tap();
                    action.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    lp.leftMargin = kit.dp(7f);
                }
                row.addView(chip, lp);
            }
            HorizontalScrollView scroller = new HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setFillViewport(false);
            scroller.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(4f);
            lp.bottomMargin = kit.dp(2f);
            LinearLayout holder = kit.column();
            holder.addView(scroller, lp);
            return holder;
        }

        // ---------------------------------------------------------- 消息区

        private View buildMessageArea() {
            messageScroll = new ScrollView(activity);
            messageScroll.setFillViewport(false);
            messageScroll.setBackgroundColor(c.bgRoot);
            messageScroll.setClipToPadding(false);
            messages = kit.column();
            messages.setPadding(0, kit.dp(2f), 0, kit.dp(6f));
            messageScroll.addView(messages, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return messageScroll;
        }

        private void reloadMessages(boolean greetWhenEmpty) {
            if (messages == null) {
                return;
            }
            hideTyping();
            removeEmptyState();
            messages.removeAllViews();
            boolean hasHistory = false;
            List<String[]> history = safeHistory(currentSlot);
            for (String[] entry : history) {
                if (entry == null || entry.length < 2 || isEmpty(entry[1])) {
                    continue;
                }
                addBubble(entry[0], entry[1], null);
                hasHistory = true;
            }
            if (!hasHistory) {
                showEmptyState();
                if (greetWhenEmpty) {
                    greeting();
                }
            } else {
                scrollToBottom();
            }
        }

        /** 空历史时的引导：一句话说明 + 三条可直接点的建议。 */
        private void showEmptyState() {
            if (messages == null || emptyState != null) {
                return;
            }
            LinearLayout card = kit.column();
            card.setBackground(kit.bg(c.surface, 18f));
            card.setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(12f));

            TextView bigAvatar = kit.textBold(avatar(), 30f * scale, c.textPrimary);
            bigAvatar.setGravity(Gravity.CENTER);
            card.addView(bigAvatar, matchWrap(0));

            TextView title = kit.textBold("聊音乐、聊生活、聊灵感都行", 14.5f * scale, c.textPrimary);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, kit.dp(6f), 0, 0);
            card.addView(title);

            TextView sub = kit.text("我是" + name() + "，想聊什么都可以，不用只聊练琴。",
                    11.5f * scale, c.textSecondary);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, kit.dp(3f), 0, kit.dp(8f));
            card.addView(sub);

            card.addView(emptySuggestion("今天适合练点什么？",
                    "今天适合练点什么？给我一个 20 分钟的练琴安排。"));
            card.addView(emptySuggestion("推荐一首好听的歌",
                    "推荐一首适合我最近水平的尤克里里歌，讲讲为什么好听。"));
            card.addView(emptySuggestion("陪我随便聊聊",
                    "今天有点累，陪我随便聊聊吧，不聊练琴也行。"), matchWrap(kit.dp(6f)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(10f);
            messages.addView(card, lp);
            emptyState = card;
        }

        private View emptySuggestion(String label, final String prompt) {
            TextView row = kit.text(label, 12.5f * scale, c.textSecondary);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(kit.dp(12f), kit.dp(10f), kit.dp(12f), kit.dp(10f));
            row.setBackground(kit.ripple(c.surface2, 12f));
            row.setMinHeight(kit.dp(40f));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                if (!canTouch()) {
                    return;
                }
                tap();
                send(prompt);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(6f);
            row.setLayoutParams(lp);
            return row;
        }

        private void removeEmptyState() {
            if (emptyState == null) {
                return;
            }
            if (messages != null) {
                messages.removeView(emptyState);
            }
            emptyState = null;
        }

        // ---------------------------------------------------------- 消息气泡

        private void addBubble(String role, String text, List<CompanionEngine.Action> actions) {
            if (isEmpty(text) && (actions == null || actions.isEmpty())) {
                return;
            }
            if (messages == null) {
                return;
            }
            removeEmptyState();
            final boolean user = "user".equals(role);
            LinearLayout row = kit.row();
            row.setGravity(Gravity.TOP | (user ? Gravity.END : Gravity.START));
            if (!user) {
                row.addView(assistantAvatar(16f));
            }

            LinearLayout bubble = kit.column();
            if (!isEmpty(text)) {
                TextView body = kit.text(text, 14f * scale, c.textPrimary);
                body.setLineSpacing(kit.dp(3f), 1.05f);
                body.setTextIsSelectable(true);
                body.setMaxWidth((int) (screenWidth * (user ? 0.80f : 0.70f)));
                body.setPadding(kit.dp(13f), kit.dp(10f), kit.dp(13f), kit.dp(10f));
                body.setBackground(kit.bg(user ? c.accentDim : c.surface2, 16f));
                bubble.addView(body);
            }

            if (actions != null && !actions.isEmpty()) {
                for (CompanionEngine.Action action : actions) {
                    if (action == null) {
                        continue;
                    }
                    String label = isEmpty(action.label)
                            ? (isEmpty(action.type) ? "执行" : action.type) : action.label;
                    TextView button = kit.textBold(label, 12f * scale, c.accent);
                    button.setGravity(Gravity.CENTER);
                    button.setPadding(kit.dp(12f), kit.dp(8f), kit.dp(12f), kit.dp(8f));
                    button.setMinHeight(kit.dp(36f));
                    button.setMaxWidth((int) (screenWidth * 0.70f));
                    button.setBackground(kit.ripple(c.surface3, 12f));
                    button.setClickable(true);
                    button.setFocusable(true);
                    button.setOnClickListener(v -> fireAction(action));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = kit.dp(6f);
                    bubble.addView(button, lp);
                }
            }

            LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!user) {
                bubbleLp.leftMargin = kit.dp(6f);
            }
            row.addView(bubble, bubbleLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = kit.dp(9f);
            messages.addView(row, rowLp);
            scrollToBottom();
        }

        private void addError(String text, final Runnable retry) {
            if (messages == null) {
                return;
            }
            LinearLayout row = kit.row();
            row.setGravity(Gravity.TOP | Gravity.START);
            row.addView(assistantAvatar(16f));
            LinearLayout bubble = kit.column();
            TextView body = kit.text("⚠ " + text, 12.5f * scale, c.danger);
            body.setLineSpacing(kit.dp(3f), 1.05f);
            body.setMaxWidth((int) (screenWidth * 0.70f));
            body.setPadding(kit.dp(12f), kit.dp(9f), kit.dp(12f), kit.dp(9f));
            body.setBackground(kit.bg(c.surface2, 16f));
            bubble.addView(body);
            if (retry != null) {
                TextView retryButton = kit.textBold("重试", 12f * scale, c.accent);
                retryButton.setGravity(Gravity.CENTER);
                retryButton.setPadding(kit.dp(12f), kit.dp(8f), kit.dp(12f), kit.dp(8f));
                retryButton.setMinHeight(kit.dp(34f));
                retryButton.setBackground(kit.ripple(c.surface3, 12f));
                retryButton.setClickable(true);
                retryButton.setFocusable(true);
                retryButton.setOnClickListener(v -> {
                    if (!canTouch()) {
                        return;
                    }
                    tap();
                    retry.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = kit.dp(6f);
                bubble.addView(retryButton, lp);
            }
            LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleLp.leftMargin = kit.dp(6f);
            row.addView(bubble, bubbleLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = kit.dp(9f);
            messages.addView(row, rowLp);
            scrollToBottom();
        }

        private void showTyping() {
            hideTyping();
            if (messages == null) {
                return;
            }
            typingRow = kit.row();
            typingRow.setGravity(Gravity.TOP | Gravity.START);
            typingRow.setContentDescription("搭子正在思考");
            typingRow.addView(assistantAvatar(16f));
            typingBubble = kit.text(TYPING_DOTS[0], 13f * scale, c.textSecondary);
            typingBubble.setGravity(Gravity.CENTER);
            typingBubble.setPadding(kit.dp(14f), kit.dp(10f), kit.dp(14f), kit.dp(10f));
            typingBubble.setBackground(kit.bg(c.surface2, 16f));
            typingBubble.setMinWidth(kit.dp(56f));
            LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleLp.leftMargin = kit.dp(6f);
            typingRow.addView(typingBubble, bubbleLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = kit.dp(9f);
            messages.addView(typingRow, rowLp);
            typingStep = 0;
            uiHandler.removeCallbacks(typingTicker);
            uiHandler.postDelayed(typingTicker, TYPING_TICK_MS);
            scrollToBottom();
        }

        private void hideTyping() {
            uiHandler.removeCallbacks(typingTicker);
            if (typingRow != null && messages != null) {
                messages.removeView(typingRow);
            }
            typingRow = null;
            typingBubble = null;
        }

        private void fireAction(CompanionEngine.Action action) {
            if (handler != null) {
                try {
                    handler.onAction(action.type, action.label, action.param);
                } catch (Throwable ignored) {
                    // 行为执行失败不阻断聊天
                }
            }
            dialog.dismiss();
        }

        // ---------------------------------------------------------- 语音状态区

        private View buildVoiceRow() {
            LinearLayout row = kit.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, kit.dp(3f), 0, kit.dp(1f));
            voiceStatus = kit.text("", 11f * scale, c.textSecondary);
            voiceStatus.setMaxLines(1);
            voiceStatus.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(voiceStatus, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            continuousChip = kit.chip("连续对话", continuous);
            continuousChip.setContentDescription("连续对话开关");
            continuousChip.setOnClickListener(v -> toggleContinuous());
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.leftMargin = kit.dp(6f);
            row.addView(continuousChip, chipLp);
            return row;
        }

        private void refreshVoiceUi() {
            updateVoiceButton();
            updateContinuousChip();
            updateMicButton();
            updateVoiceStatus();
        }

        private void updateVoiceButton() {
            if (voiceButton == null) {
                return;
            }
            voiceButton.setText("语音");
            voiceButton.setTextColor(voiceOn ? c.accent : c.textTertiary);
            voiceButton.setContentDescription(voiceOn ? "语音朗读已开启" : "语音朗读已关闭");
        }

        private void updateContinuousChip() {
            if (continuousChip == null) {
                return;
            }
            try {
                kit.styleChip(continuousChip, continuous);
            } catch (Throwable ignored) {
            }
            continuousChip.setContentDescription(continuous ? "连续对话已开启" : "连续对话已关闭");
        }

        private void updateMicButton() {
            if (micButton == null) {
                return;
            }
            micButton.setText(recognizing ? "■" : "🎤");
            micButton.setTextColor(recognizing ? c.accent : c.textSecondary);
            micButton.setContentDescription(recognizing ? "停止语音输入" : "语音输入");
        }

        private void updateVoiceStatus() {
            if (voiceStatus == null) {
                return;
            }
            String text = "";
            int color = c.textSecondary;
            if (recognizing) {
                text = isEmpty(preparingVoice) ? "正在听…说完会自动发送" : preparingVoice;
                color = c.accent;
            } else if (speakingNow) {
                text = "正在说…点麦克风可以打断";
                color = c.accent;
            } else if (continuous) {
                text = "连续对话中：搭子说完会自动继续听";
                color = c.textSecondary;
            }
            if (isEmpty(text)) {
                voiceStatus.setVisibility(View.GONE);
            } else {
                voiceStatus.setText(text);
                voiceStatus.setTextColor(color);
                voiceStatus.setVisibility(View.VISIBLE);
            }
        }

        // ---------------------------------------------------------- 输入区

        private View buildInputArea() {
            LinearLayout box = kit.column();
            hintLine = kit.text("", 11f * scale, c.textTertiary);
            hintLine.setPadding(0, kit.dp(1f), 0, kit.dp(3f));
            hintLine.setVisibility(View.GONE);
            box.addView(hintLine);

            LinearLayout row = kit.row();
            row.setGravity(Gravity.CENTER_VERTICAL);

            input = new EditText(activity);
            input.setHint("聊音乐、聊生活、聊灵感…");
            input.setContentDescription("音乐搭子消息输入框");
            input.setFocusableInTouchMode(true);
            input.setSelectAllOnFocus(false);
            input.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) enterTypingMode();
                return false;
            });
            input.setSingleLine(false);
            input.setMinLines(1);
            input.setMaxLines(4);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setVerticalScrollBarEnabled(true);
            input.setImeOptions(EditorInfo.IME_ACTION_SEND);
            AppDialogs.themeInput(input, 15f, true);
            input.setMinHeight(kit.dp(46f));
            input.setPadding(kit.dp(12f), kit.dp(10f), kit.dp(12f), kit.dp(10f));
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send(input.getText().toString());
                    return true;
                }
                return false;
            });
            input.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    scrollToBottom();
                }
            });
            row.addView(input, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            micButton = roundButton("🎤", c.surface2, c.textSecondary, 15f);
            micButton.setMinWidth(kit.dp(44f));
            micButton.setContentDescription("语音输入");
            micButton.setOnClickListener(v -> onMicClick());
            LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            micLp.leftMargin = kit.dp(5f);
            row.addView(micButton, micLp);

            sendButton = roundButton("发送", c.accent, c.bgRoot, 13f);
            sendButton.setMinWidth(kit.dp(56f));
            sendButton.setOnClickListener(v -> send(input.getText().toString()));
            LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sendLp.leftMargin = kit.dp(5f);
            row.addView(sendButton, sendLp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(5f);
            box.addView(row, lp);
            return box;
        }

        // ---------------------------------------------------------- AI 客户端 / 历史

        private int resolveActiveSlot() {
            try {
                int slot = store.activeSlot();
                return clamp(slot, 0, CompanionStore.MAX_ASSISTANTS - 1);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private CompanionPersona resolvePersona() {
            try {
                CompanionPersona p = store.persona(currentSlot);
                if (p != null) {
                    return p;
                }
            } catch (Throwable ignored) {
            }
            try {
                String id = creds.companionPersonaId();
                return CompanionPersona.preset(isEmpty(id) ? "sunny" : id);
            } catch (Throwable ignored) {
                return CompanionPersona.preset("sunny");
            }
        }

        /** 重建客户端与引擎（首次打开 / 配置变化 / 切换后需要时）。返回是否已就绪。 */
        private boolean rebuildClient() {
            try {
                if (client != null) {
                    try {
                        client.cancel();
                    } catch (Throwable ignored) {
                    }
                    try {
                        client.shutdown();
                    } catch (Throwable ignored) {
                    }
                }
                String key = creds.activeKey();
                client = new DeepSeekClient(key, creds.baseUrl(), creds.model());
                engine = new CompanionEngine(client, persona, store);
                boolean ready = !isEmpty(key) && client.hasKey();
                updateBanner(ready);
                return ready;
            } catch (Throwable error) {
                client = null;
                engine = null;
                updateBanner(false);
                return false;
            }
        }

        private boolean ensureEngine() {
            if (engine != null && client != null) {
                try {
                    if (client.hasKey()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return rebuildClient();
        }

        private void updateBanner(boolean ready) {
            if (banner == null) {
                return;
            }
            banner.setVisibility(ready ? View.GONE : View.VISIBLE);
            if (ready || bannerText == null) {
                return;
            }
            int mode;
            try {
                mode = creds.mode();
            } catch (Throwable ignored) {
                mode = AiCredentials.MODE_OFF;
            }
            if (mode == AiCredentials.MODE_OFF) {
                bannerText.setText("AI 功能已关闭。开启后就能和搭子聊天。");
            } else {
                bannerText.setText("还没有可用的 API Key。配置后就能和搭子聊天。");
            }
        }

        private void openSetupDialog() {
            AiSetupDialog.show(activity, () -> {
                if (!canTouch()) {
                    return;
                }
                boolean ready = rebuildClient();
                if (!ready) {
                    showHint("配置还没完成，稍后再试");
                    return;
                }
                showHint("AI 已配置好，可以开始聊天了");
                if (emptyState != null && !requesting) {
                    greeting();
                }
            });
        }

        private void greeting() {
            if (!alive || messages == null || requesting) {
                return;
            }
            if (!ensureEngine()) {
                return;
            }
            requesting = true;
            showTyping();
            try {
                engine.greeting(appContext, new CompanionEngine.ReplyCallback() {
                    @Override
                    public void onSuccess(CompanionEngine.Reply reply) {
                        if (!canTouch()) {
                            return;
                        }
                        requesting = false;
                        hideTyping();
                        if (reply == null || isEmpty(reply.text)) {
                            addError("搭子这次没吭声，点重试再来一次。", this::retryGreeting);
                            return;
                        }
                        addBubble("assistant", reply.text, reply.actions);
                        speak(reply.text);
                    }

                    @Override
                    public void onError(DeepSeekClient.AiError e) {
                        if (!canTouch()) {
                            return;
                        }
                        requesting = false;
                        hideTyping();
                        addError("搭子暂时没连上：" + errorText(e), this::retryGreeting);
                    }

                    private void retryGreeting() {
                        greeting();
                    }
                });
            } catch (Throwable error) {
                requesting = false;
                hideTyping();
                addError("打招呼失败，请稍后重试。", this::greeting);
            }
        }

        // ---------------------------------------------------------- 发送 / 清空

        private void send(final String raw) {
            if (!alive) {
                return;
            }
            if (requesting) {
                showHint("等搭子说完这一句再发吧");
                return;
            }
            final String text = raw == null ? "" : raw.trim();
            if (text.isEmpty()) {
                showHint("先打一句话再发送吧");
                return;
            }
            if (recognizing) {
                stopRecognition();
            }
            if (!ensureEngine()) {
                showHint("AI 还没配置，先点上方「去配置」");
                return;
            }
            silenceVoice();
            requesting = true;
            addBubble("user", text, null);
            if (input != null) {
                input.setText("");
            }
            showTyping();
            try {
                engine.send(text, appContext, new CompanionEngine.ReplyCallback() {
                    @Override
                    public void onSuccess(CompanionEngine.Reply reply) {
                        if (!canTouch()) {
                            return;
                        }
                        requesting = false;
                        hideTyping();
                        if (reply == null || isEmpty(reply.text)) {
                            addError("搭子这次没有返回内容，再试一次吧。", () -> send(text));
                            return;
                        }
                        addBubble("assistant", reply.text, reply.actions);
                        speak(reply.text);
                    }

                    @Override
                    public void onError(DeepSeekClient.AiError e) {
                        if (!canTouch()) {
                            return;
                        }
                        requesting = false;
                        hideTyping();
                        addError("搭子暂时没连上：" + errorText(e), () -> send(text));
                    }
                });
            } catch (Throwable error) {
                requesting = false;
                hideTyping();
                addError("发送失败，请稍后重试。", () -> send(text));
            }
        }

        private void clearConversation() {
            if (!alive) {
                return;
            }
            if (requesting) {
                showHint("等搭子回完这句再清空吧");
                return;
            }
            try {
                store.clearHistory(currentSlot);
            } catch (Throwable ignored) {
            }
            if (engine != null) {
                try {
                    engine.clearHistory();
                } catch (Throwable ignored) {
                }
            }
            hideTyping();
            reloadMessages(true);
            showHint("对话已清空，重新认识一下");
        }

        // ---------------------------------------------------------- 语音

        private void onMicClick() {
            if (!alive) {
                return;
            }
            if (isSpeakingSafe()) {
                silenceVoice();
            }
            if (recognizing) {
                if (voice != null) voice.finishListening();
                preparingVoice = "正在整理刚才的话…";
                syncVoiceUi();
                return;
            }
            if (permissionPending) return;
            if (!hasRecordPermission()) {
                permissionPending = true;
                showHint("请允许麦克风权限，才能使用语音输入");
                MicrophonePermission.request(activity, granted -> {
                    permissionPending = false;
                    if (!canTouch()) return;
                    if (granted) startRecognition();
                    else showHint("未获得麦克风权限，仍可打字聊天；可在系统设置中开启");
                });
                return;
            }
            startRecognition();
        }

        private void enterTypingMode() {
            continuous = false;
            autoListenPending = false;
            if (recognizing) stopRecognition();
            if (speakingNow) silenceVoice();
            preparingVoice = null;
            syncVoiceUi();
        }

        private void startRecognition() {
            if (!alive || recognizing) {
                return;
            }
            ensureVoice();
            if (voice == null) {
                showHint("语音功能初始化失败");
                return;
            }
            try {
                voice.silence();
                voice.stop();
                voice.setVoice(pitch(), rate());
                voice.setEmotionProfile(personaId());
                voice.setContinuousMode(continuous);
            } catch (Throwable ignored) {
            }
            voiceDraftPrefix = input == null ? "" : input.getText().toString().trim();
            hideKeyboard();
            input.clearFocus();
            preparingVoice = "正在准备语音…";
            recognizing = true;
            autoListenPending = false;
            speakingNow = false;
            syncVoiceUi();
            showHint("正在听…再点一次麦克风可停止");
            try {
                voice.start();
            } catch (Throwable error) {
                recognizing = false;
                syncVoiceUi();
                showHint("语音识别启动失败，请稍后重试");
            }
        }

        private void stopRecognition() {
            try {
                if (voice != null) {
                    voice.stop();
                }
            } catch (Throwable ignored) {
            }
            recognizing = false;
            preparingVoice = null;
            autoListenPending = false;
            syncVoiceUi();
            showHint("已停止聆听");
        }

        /** 连续对话：搭子朗读结束后自动重新开麦（权限/存活/不重入都检查）。 */
        private void maybeAutoListen() {
            if (!alive || !continuous || recognizing || requesting || autoListenPending) {
                return;
            }
            if (!canTouch() || !hasRecordPermission()) {
                return;
            }
            if (editorDialog != null || switcherDialog != null || !chatWindowFocused()) {
                // 聊天窗不在前台（例如打开了人格编辑/切换器/配置弹窗）时不自动开麦，
                // 避免用户正在别的弹窗里操作却偷偷录音
                return;
            }
            autoListenPending = true;
            uiHandler.postDelayed(() -> {
                autoListenPending = false;
                if (!alive || !continuous || recognizing || requesting || !canTouch()) {
                    return;
                }
                if (editorDialog != null || switcherDialog != null || !chatWindowFocused()) {
                    return;
                }
                if (!hasRecordPermission()) {
                    showHint("连续对话需要麦克风权限");
                    return;
                }
                startRecognition();
            }, AUTO_LISTEN_DELAY_MS);
        }

        /** 聊天弹窗自己的 window 是否仍持有焦点（被其它 Dialog 盖住时为 false）。 */
        private boolean chatWindowFocused() {
            try {
                Window window = dialog.getWindow();
                return dialog.isShowing() && window != null
                        && window.getDecorView().hasWindowFocus();
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void toggleContinuous() {
            continuous = !continuous;
            ensureVoice();
            if (voice != null) {
                try {
                    voice.setContinuousMode(continuous);
                } catch (Throwable ignored) {
                }
            }
            syncVoiceUi();
            if (continuous) {
                if (!voiceOn && VoiceChatManager.canSpeak(activity)) {
                    voiceOn = true;
                    creds.setVoiceEnabled(true);
                }
                if (!requesting && !isSpeakingSafe()) onMicClick();
                else showHint("连续对话已开启，回复朗读结束后会自动继续听");
            } else {
                if (recognizing) stopRecognition();
                showHint("连续对话已关闭");
            }
        }

        private void toggleVoice() {
            if (voiceOn) {
                voiceOn = false;
                try {
                    creds.setVoiceEnabled(false);
                } catch (Throwable ignored) {
                }
                silenceVoice();
                showHint("语音朗读已关闭");
            } else {
                boolean canSpeak;
                try {
                    canSpeak = VoiceChatManager.canSpeak(activity);
                } catch (Throwable ignored) {
                    canSpeak = false;
                }
                if (!canSpeak) {
                    showHint("当前设备缺少中文语音合成，暂时无法朗读");
                    return;
                }
                voiceOn = true;
                try {
                    creds.setVoiceEnabled(true);
                } catch (Throwable ignored) {
                }
                ensureVoice();
                showHint("语音朗读已开启，搭子的回复会自动读出来");
            }
            syncVoiceUi();
        }

        private VoiceChatManager.Listener voiceListener() {
            return new VoiceChatManager.Listener() {
                    @Override public void onPreparing(String message) {
                        if (!canTouch()) return;
                        preparingVoice = message;
                        syncVoiceUi();
                        showHint("");
                    }
                    @Override public void onPlaybackError(String message) {
                        if (!canTouch()) return;
                        speakingNow = false;
                        continuous = false;
                        autoListenPending = false;
                        syncVoiceUi();
                        showHint(message);
                    }
                    @Override
                    public void onListening() {
                        if (!canTouch()) {
                            return;
                        }
                        preparingVoice = null;
                        recognizing = true;
                        syncVoiceUi();
                        showHint("");
                    }

                    @Override
                    public void onPartial(String text) {
                        if (!canTouch() || isEmpty(text) || input == null) {
                            return;
                        }
                        input.setText(voiceDraftPrefix.isEmpty() ? text : voiceDraftPrefix + " " + text);
                        input.setSelection(input.getText().length());
                    }

                    @Override
                    public void onFinal(String text) {
                        if (!canTouch()) {
                            return;
                        }
                        recognizing = false;
                        syncVoiceUi();
                        showHint("");
                        String said = isEmpty(text)
                                ? (input == null ? "" : input.getText().toString().trim())
                                : (voiceDraftPrefix.isEmpty() ? text.trim() : voiceDraftPrefix + " " + text.trim());
                        if (said.isEmpty()) {
                            showHint("没听清，再点一次麦克风试试");
                            return;
                        }
                        if (input != null) {
                            input.setText(said);
                            input.setSelection(input.getText().length());
                        }
                        send(said);
                    }

                    @Override
                    public void onSpeechError(String message) {
                        if (!canTouch()) {
                            return;
                        }
                        recognizing = false;
                        preparingVoice = null;
                        continuous = false;
                        autoListenPending = false;
                        syncVoiceUi();
                        showHint(isEmpty(message) ? "语音识别失败，请再试一次" : message);
                    }

                    @Override
                    public void onSpeaking(boolean speaking) {
                        if (!canTouch()) {
                            return;
                        }
                        speakingNow = speaking;
                        syncVoiceUi();
                    }

                    @Override
                    public void onSpeechFinished() {
                        if (!canTouch()) {
                            return;
                        }
                        speakingNow = false;
                        syncVoiceUi();
                        maybeAutoListen();
                    }
                };
        }

        private void ensureVoice() {
            if (voice == null) {
                try {
                    voice = new VoiceChatManager(activity);
                    voice.setListener(voiceListener());
                } catch (Throwable ignored) {
                    voice = null;
                    return;
                }
            }
            try {
                voice.setEmotionProfile(personaId());
                voice.setVoice(pitch(), rate());
                voice.setContinuousMode(continuous);
            } catch (Throwable ignored) {
            }
        }

        private void speak(String text) {
            if (!voiceOn || !canTouch() || isEmpty(text)) {
                return;
            }
            ensureVoice();
            if (voice == null) {
                return;
            }
            try {
                voice.setEmotionProfile(personaId());
                voice.setVoice(pitch(), rate());
                // v2 的 speak 内部会清洗 Markdown/行为标签并按句分块，这里直接给原始回复
                voice.speak(text);
                speakingNow = voice.isSpeaking();
                syncVoiceUi();
                // 兜底：少数 TTS 引擎 speak 失败也不回调 onSpeaking(false)，稍后按真实状态纠正
                uiHandler.postDelayed(() -> {
                    if (!alive || !canTouch()) {
                        return;
                    }
                    if (!isSpeakingSafe()) {
                        speakingNow = false;
                        updateVoiceStatus();
                    }
                }, 900L);
            } catch (Throwable ignored) {
            }
        }

        private void silenceVoice() {
            if (voice != null) {
                try {
                    voice.silence();
                } catch (Throwable ignored) {
                }
            }
            speakingNow = false;
            syncVoiceUi();
        }

        private boolean isSpeakingSafe() {
            if (voice == null) {
                return false;
            }
            try {
                return voice.isSpeaking();
            } catch (Throwable ignored) {
                return speakingNow;
            }
        }

        private void syncVoiceUi() {
            refreshVoiceUi();
        }

        private boolean hasRecordPermission() {
            try {
                return activity.checkSelfPermission(RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
                return false;
            }
        }

        // ---------------------------------------------------------- 助手切换 / 新建 / 删除

        private void openAssistantSwitcher() {
            if (!alive) {
                return;
            }
            if (requesting) {
                showHint("等搭子回完这句再换吧");
                return;
            }
            if (switcherDialog != null) {
                try {
                    switcherDialog.dismiss();
                } catch (Throwable ignored) {
                }
                switcherDialog = null;
            }
            final int count = safeAssistantCount();
            final Dialog sheet = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
            switcherDialog = sheet;

            LinearLayout root = kit.column();
            root.setBackground(kit.bg(c.surface, 20f));
            root.setPadding(kit.dp(16f), kit.dp(14f), kit.dp(16f), kit.dp(12f));

            LinearLayout head = kit.row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(kit.textBold("换个搭子", 18f * scale, c.textPrimary),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView countView = kit.text(count + " / " + CompanionStore.MAX_ASSISTANTS,
                    12f * scale, c.textTertiary);
            head.addView(countView);
            TextView close = compactLabel("✕", c.textSecondary);
            close.setOnClickListener(v -> sheet.dismiss());
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeLp.leftMargin = kit.dp(8f);
            head.addView(close, closeLp);
            root.addView(head);

            TextView desc = kit.text("每个搭子有独立的人格、记忆和声音，最多保留 4 个。",
                    11.5f * scale, c.textSecondary);
            desc.setPadding(0, kit.dp(4f), 0, kit.dp(6f));
            root.addView(desc);
            boolean full = count >= CompanionStore.MAX_ASSISTANTS;
            if (full) {
                TextView fullHint = kit.text("已满 4 个搭子，先删掉一个再新建。", 11.5f * scale, c.warn);
                fullHint.setPadding(0, 0, 0, kit.dp(4f));
                root.addView(fullHint);
            }

            // 槽位可能不连续（删掉中间某个助手后新建会填补第一个空位），必须遍历全部槽位
            for (int slot = 0; slot < CompanionStore.MAX_ASSISTANTS; slot++) {
                final int targetSlot = slot;
                CompanionPersona p = safePersona(slot);
                if (p == null) {
                    continue;
                }
                boolean active = slot == currentSlot;
                LinearLayout row = kit.row();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(kit.dp(10f), kit.dp(10f), kit.dp(10f), kit.dp(10f));
                row.setBackground(active
                        ? kit.bg(c.surface2, 14f, c.accent, kit.dpf(1.2f))
                        : kit.ripple(c.surface2, 14f));
                row.setClickable(true);
                row.setFocusable(true);

                TextView av = kit.textBold(emptySafe(p.avatar, "🎵"), 22f * scale, c.textPrimary);
                av.setGravity(Gravity.CENTER);
                av.setMinWidth(kit.dp(34f));
                row.addView(av);

                LinearLayout texts = kit.column();
                TextView rowName = kit.textBold(emptySafe(p.name, "音乐搭子"), 15f * scale, c.textPrimary);
                rowName.setMaxLines(1);
                rowName.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(rowName);
                String rowTagline = !isEmpty(p.tagline) ? p.tagline
                        : (!isEmpty(p.catchphrase) ? p.catchphrase : "");
                TextView rowSub = kit.text(rowTagline, 11.5f * scale, c.textSecondary);
                rowSub.setMaxLines(1);
                rowSub.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(rowSub);
                LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textsLp.leftMargin = kit.dp(10f);
                row.addView(texts, textsLp);

                if (active) {
                    row.addView(kit.badge("当前", c.accent, c.bgRoot));
                }
                TextView edit = kit.textBold("编辑", 11.5f * scale, c.accent);
                edit.setGravity(Gravity.CENTER);
                edit.setPadding(kit.dp(8f), kit.dp(7f), kit.dp(8f), kit.dp(7f));
                edit.setMinHeight(kit.dp(34f));
                edit.setContentDescription("编辑" + emptySafe(p.name, "这个搭子"));
                edit.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (targetSlot != currentSlot) {
                        if (!safeSetActiveSlot(targetSlot)) {
                            showHint("切换失败，请重试");
                            return;
                        }
                        activateSlot(targetSlot, null);
                    }
                    uiHandler.post(() -> {
                        if (canTouch() && currentSlot == targetSlot) {
                            showPersonaEditor();
                        }
                    });
                });
                LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                editLp.leftMargin = kit.dp(6f);
                row.addView(edit, editLp);

                row.setOnClickListener(v -> {
                    sheet.dismiss();
                    switchToSlot(targetSlot);
                });
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = kit.dp(6f);
                root.addView(row, rowLp);
            }

            LinearLayout footer = kit.row();
            footer.setGravity(Gravity.CENTER_VERTICAL);
            TextView add = kit.textBold(full ? "已满 4 个搭子" : "新建助手（" + count + "/4）",
                    13f * scale, full ? c.textTertiary : c.accent);
            add.setGravity(Gravity.CENTER);
            add.setPadding(kit.dp(12f), kit.dp(12f), kit.dp(12f), kit.dp(12f));
            add.setMinHeight(kit.dp(46f));
            if (full) {
                // 满 4 个时新建入口禁用并变灰；提示放在说明区，点不进新建流程
                add.setBackground(kit.bg(c.surface2, 14f));
                add.setClickable(false);
                add.setFocusable(false);
            } else {
                add.setBackground(kit.ripple(c.accentDim, 14f));
                add.setOnClickListener(v -> {
                    sheet.dismiss();
                    createAssistantAndEdit();
                });
            }
            footer.addView(add, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView closeBottom = kit.textBold("关闭", 13f * scale, c.textSecondary);
            closeBottom.setGravity(Gravity.CENTER);
            closeBottom.setPadding(kit.dp(14f), kit.dp(12f), kit.dp(14f), kit.dp(12f));
            closeBottom.setMinHeight(kit.dp(46f));
            closeBottom.setBackground(kit.ripple(c.surface2, 14f));
            closeBottom.setOnClickListener(v -> sheet.dismiss());
            LinearLayout.LayoutParams closeBottomLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeBottomLp.leftMargin = kit.dp(8f);
            footer.addView(closeBottom, closeBottomLp);

            LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerLp.topMargin = kit.dp(12f);
            root.addView(footer, footerLp);

            sheet.setContentView(root);
            sheet.setCanceledOnTouchOutside(true);
            sheet.setOnDismissListener(d -> {
                if (switcherDialog == sheet) {
                    switcherDialog = null;
                }
            });
            sheet.show();
            Window window = sheet.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout((int) (screenWidth * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.BOTTOM);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.y = kit.dp(26f);
                window.setAttributes(lp);
            }
        }

        private void switchToSlot(int slot) {
            if (!alive || requesting) {
                return;
            }
            if (slot < 0 || slot >= CompanionStore.MAX_ASSISTANTS) {
                return;
            }
            if (slot == currentSlot) {
                showHint("现在陪你的是「" + name() + "」");
                return;
            }
            if (!safeSetActiveSlot(slot)) {
                showHint("切换失败，请重试");
                return;
            }
            activateSlot(slot, "现在陪你的是「" + nameOf(safePersona(slot)) + "」");
        }

        /** 把某个 slot 设为当前工作槽：同步人格/语音/历史并重绘。调用方负责先 setActiveSlot。 */
        private void activateSlot(int slot, String hint) {
            currentSlot = clamp(slot, 0, CompanionStore.MAX_ASSISTANTS - 1);
            persona = resolvePersona();
            safeStopVoice();
            attachPersona();
            refreshPersonaHeader();
            hideTyping();
            requesting = false;
            reloadMessages(true);
            if (!isEmpty(hint)) {
                showHint(hint);
            }
        }

        private void createAssistantAndEdit() {
            if (!alive) {
                return;
            }
            if (requesting) {
                showHint("等搭子回完这句再新建吧");
                return;
            }
            int count = safeAssistantCount();
            if (count >= CompanionStore.MAX_ASSISTANTS) {
                showHint("最多保留 4 个搭子，先删掉一个再新建");
                return;
            }
            CompanionPersona copy = copyPersona(persona != null ? persona : resolvePersona());
            if (copy == null) {
                copy = CompanionPersona.preset("sunny");
            }
            copy.name = uniqueAssistantName(count);
            if (isEmpty(copy.tagline)) {
                copy.tagline = "新搭子，还在磨合中";
            }
            if (!safeAddAssistant(copy)) {
                showHint("新建失败，可能已经满 4 个了");
                return;
            }
            int slot = safeActiveSlot();
            activateSlot(slot, "新搭子已就位，改个名字吧");
            final int target = currentSlot;
            uiHandler.post(() -> {
                if (canTouch() && currentSlot == target) {
                    showPersonaEditor();
                }
            });
        }

        private void deleteCurrentAssistant() {
            if (!alive) {
                return;
            }
            if (requesting) {
                showHint("等搭子回完这句再删吧");
                return;
            }
            if (safeAssistantCount() <= 1) {
                showHint("至少保留一个搭子，不能删除");
                return;
            }
            AppDialogs.message(activity, "删除「" + name() + "」？",
                    "人格和聊天记录会一起删除，无法恢复。",
                    "删除", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            performDeleteCurrent();
                        }
                    },
                    "取消", null, null, null);
        }

        private void performDeleteCurrent() {
            if (!alive) {
                return;
            }
            int removed = currentSlot;
            if (!safeDeleteAssistant(removed)) {
                showHint("删除失败，请重试");
                return;
            }
            int slot = safeActiveSlot();
            activateSlot(slot, "已删除，现在陪你的是「" + nameOf(safePersona(slot)) + "」");
        }

        private void openMoreMenu() {
            if (!alive) {
                return;
            }
            final int count = safeAssistantCount();
            List<AppDialogs.Item> items = new ArrayList<>();
            items.add(new AppDialogs.Item("编辑人格", "名字、头像、性格和语音", false));
            items.add(new AppDialogs.Item("清空对话", "只清空「" + name() + "」的聊天记录", false));
            items.add(new AppDialogs.Item("新建助手",
                    count >= CompanionStore.MAX_ASSISTANTS
                            ? "已满 4 个，先删掉一个"
                            : "当前 " + count + "/4，每个搭子独立记忆",
                    false));
            items.add(new AppDialogs.Item("删除当前助手",
                    count <= 1 ? "至少保留一个搭子" : "人格与记录一起删除",
                    count > 1));
            items.add(new AppDialogs.Item("关闭", null, false));
            AppDialogs.list(activity, "更多", items, new AppDialogs.OnPick() {
                @Override
                public void picked(int index) {
                    if (!canTouch()) {
                        return;
                    }
                    switch (index) {
                        case 0:
                            openPersonaEditor();
                            break;
                        case 1:
                            clearConversation();
                            break;
                        case 2:
                            createAssistantAndEdit();
                            break;
                        case 3:
                            deleteCurrentAssistant();
                            break;
                        default:
                            dialog.dismiss();
                            break;
                    }
                }
            }, "取消", null, null, null);
        }

        private void refreshPersonaHeader() {
            if (avatarView != null) {
                avatarView.setText(avatar());
            }
            if (nameView != null) {
                nameView.setText(name());
            }
            if (taglineView != null) {
                taglineView.setText(tagline());
            }
        }

        /** engine 与语音情感档案都跟着人格走。 */
        private void attachPersona() {
            if (engine != null) {
                try {
                    engine.setPersona(persona);
                } catch (Throwable ignored) {
                }
            }
            if (voice != null) {
                try {
                    voice.setEmotionProfile(personaId());
                    voice.setVoice(pitch(), rate());
                } catch (Throwable ignored) {
                }
            }
        }

        // ---------------------------------------------------------- 人格编辑

        private void openPersonaEditor() {
            if (!alive || editorDialog != null) {
                return;
            }
            if (requesting) {
                showHint("等搭子回完这句再改吧");
                return;
            }
            showPersonaEditor();
        }

        /**
         * 不做 requesting 拦截的编辑入口：新建助手后会立刻编辑，此时首次问候可能正在路上；
         * 当前槽已经确定，编辑保存不会影响槽位归属，因此可以并行。
         */
        @SuppressWarnings("deprecation")
        private void showPersonaEditor() {
            if (!alive || editorDialog != null) {
                return;
            }
            CompanionPersona base = persona != null ? persona : resolvePersona();
            editorPersona = copyPersona(base);
            if (editorPersona == null) {
                showHint("搭子资料暂不可用");
                return;
            }
            final Dialog editor = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
            editorDialog = editor;

            ScrollView scroller = new ScrollView(activity);
            scroller.setBackgroundColor(c.bgRoot);
            LinearLayout root = kit.column();
            root.setBackgroundColor(c.bgRoot);
            root.setPadding(kit.dp(18f), kit.dp(12f), kit.dp(18f), kit.dp(16f));

            LinearLayout head = kit.row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(kit.textBold("搭子换装", 19f * scale, c.textPrimary),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = compactLabel("关闭", c.textSecondary);
            close.setOnClickListener(v -> editor.dismiss());
            head.addView(close);
            root.addView(head);

            TextView presetHint = kit.text("预设人格（点一下套用，可继续修改）", 12f * scale, c.textSecondary);
            presetHint.setPadding(0, kit.dp(10f), 0, kit.dp(6f));
            root.addView(presetHint);
            root.addView(buildPresetRow());

            root.addView(editorLabel("头像 emoji"));
            editorAvatar = editorInput("🎸", false);
            root.addView(editorAvatar, matchWrap(kit.dp(6f)));

            root.addView(editorLabel("名字"));
            editorName = editorInput("给搭子起个名字", false);
            root.addView(editorName, matchWrap(kit.dp(6f)));

            root.addView(editorLabel("一句话介绍"));
            editorTagline = editorInput("例如：每天陪你练十分钟", false);
            root.addView(editorTagline, matchWrap(kit.dp(6f)));

            root.addView(editorLabel("性格与表达方式（也会影响说话语气）"));
            editorPersonality = editorInput("例如：温柔耐心，先夸再指出一个可以改进的地方", true);
            root.addView(editorPersonality, matchWrap(kit.dp(6f)));

            root.addView(editorLabel("口头禅"));
            editorCatchphrase = editorInput("例如：慢慢来，比较快", false);
            root.addView(editorCatchphrase, matchWrap(kit.dp(6f)));

            root.addView(editorLabel("语音音调（0.5× 低沉 — 2.0× 清亮）"));
            editorPitchLabel = kit.text("", 12f * scale, c.accent);
            root.addView(editorPitchLabel);
            editorPitch = kit.seekBar(c.accent);
            root.addView(editorPitch);
            editorPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (editorPersona == null) {
                        return;
                    }
                    editorPersona.voicePitch = 0.5f + progress * 0.015f;
                    if (editorPitchLabel != null) {
                        editorPitchLabel.setText(String.format(Locale.US,
                                "音调 %.2f×", editorPersona.voicePitch));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            root.addView(editorLabel("语音语速（0.5× 舒缓 — 2.0× 轻快）"));
            editorRateLabel = kit.text("", 12f * scale, c.accent);
            root.addView(editorRateLabel);
            editorRate = kit.seekBar(c.accent);
            root.addView(editorRate);
            editorRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (editorPersona == null) {
                        return;
                    }
                    editorPersona.voiceRate = 0.5f + progress * 0.015f;
                    if (editorRateLabel != null) {
                        editorRateLabel.setText(String.format(Locale.US,
                                "语速 %.2f×", editorPersona.voiceRate));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            TextView emotionHint = kit.text(
                    "搭子会根据人格自动调整朗读情绪；音调/语速在这里作为它的基础声音。",
                    11f * scale, c.textTertiary);
            emotionHint.setPadding(0, kit.dp(8f), 0, 0);
            root.addView(emotionHint);

            LinearLayout actions = kit.row();
            TextView preview = kit.actionOutline("试听音色");
            preview.setOnClickListener(v -> previewVoice());
            actions.addView(preview, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView save = kit.action("保存搭子", c.accent, c.bgRoot);
            save.setOnClickListener(v -> savePersonaEditor());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            saveLp.leftMargin = kit.dp(8f);
            actions.addView(save, saveLp);
            root.addView(actions, matchWrap(kit.dp(16f)));

            scroller.addView(root, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            editor.setContentView(scroller);
            editor.setCanceledOnTouchOutside(true);
            editor.setOnDismissListener(d -> {
                if (editorDialog == editor) {
                    editorDialog = null;
                    editorPersona = null;
                }
            });
            refreshEditorFields();
            editor.show();
            Window window = editor.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(c.bgRoot));
                android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                window.setLayout((int) (dm.widthPixels * 0.94f), (int) (dm.heightPixels * 0.90f));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }

        private View buildPresetRow() {
            LinearLayout row = kit.row();
            String[] ids;
            try {
                ids = CompanionPersona.presetIds();
            } catch (Throwable error) {
                ids = null;
            }
            if (ids == null) {
                ids = new String[0];
            }
            editorPresetIds = ids;
            editorPresetChips = new TextView[ids.length];
            for (int i = 0; i < ids.length; i++) {
                final String id = ids[i];
                String label;
                try {
                    label = CompanionPersona.presetName(id);
                } catch (Throwable error) {
                    label = null;
                }
                if (isEmpty(label)) {
                    label = id;
                }
                final TextView chip = kit.chip(label, false);
                chip.setOnClickListener(v -> applyPreset(id));
                editorPresetChips[i] = chip;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    lp.leftMargin = kit.dp(7f);
                }
                row.addView(chip, lp);
            }
            HorizontalScrollView scroller = new HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(2f);
            LinearLayout holder = kit.column();
            holder.addView(scroller, lp);
            return holder;
        }

        private void applyPreset(String id) {
            CompanionPersona preset;
            try {
                preset = CompanionPersona.preset(id);
            } catch (Throwable error) {
                preset = null;
            }
            if (preset == null) {
                return;
            }
            editorPersona = copyPersona(preset);
            refreshEditorFields();
            toast("已套用预设：" + nameOf(preset));
        }

        private void refreshEditorFields() {
            if (editorPersona == null) {
                return;
            }
            editorAvatar.setText(nullToEmpty(editorPersona.avatar));
            editorName.setText(nullToEmpty(editorPersona.name));
            editorTagline.setText(nullToEmpty(editorPersona.tagline));
            editorPersonality.setText(nullToEmpty(editorPersona.personality));
            editorCatchphrase.setText(nullToEmpty(editorPersona.catchphrase));
            int pitchProgress = Math.round((clampVoice(editorPersona.voicePitch) - 0.5f) / 0.015f);
            int rateProgress = Math.round((clampVoice(editorPersona.voiceRate) - 0.5f) / 0.015f);
            editorPitch.setProgress(mathClamp(pitchProgress, 0, 100));
            editorRate.setProgress(mathClamp(rateProgress, 0, 100));
            if (editorPitchLabel != null) {
                editorPitchLabel.setText(String.format(Locale.US,
                        "音调 %.2f×", clampVoice(editorPersona.voicePitch)));
            }
            if (editorRateLabel != null) {
                editorRateLabel.setText(String.format(Locale.US,
                        "语速 %.2f×", clampVoice(editorPersona.voiceRate)));
            }
            if (editorPresetChips != null) {
                for (int i = 0; i < editorPresetChips.length; i++) {
                    if (editorPresetChips[i] == null) {
                        continue;
                    }
                    boolean selected = editorPresetIds != null && i < editorPresetIds.length
                            && editorPresetIds[i] != null
                            && editorPresetIds[i].equals(editorPersona.id);
                    kit.styleChip(editorPresetChips[i], selected);
                }
            }
        }

        private void previewVoice() {
            ensureVoice();
            if (voice == null) {
                showHint("语音合成不可用");
                return;
            }
            float p = editorPersona != null ? clampVoice(editorPersona.voicePitch) : pitch();
            float r = editorPersona != null ? clampVoice(editorPersona.voiceRate) : rate();
            try {
                voice.setEmotionProfile(editorPersona != null && !isEmpty(editorPersona.id)
                        ? editorPersona.id : personaId());
                voice.setVoice(p, r);
                voice.speak("你好呀，我是" + nameOf(editorPersona) + "，一起玩音乐吧！");
                speakingNow = true;
                syncVoiceUi();
            } catch (Throwable ignored) {
            }
        }

        private void savePersonaEditor() {
            if (editorPersona == null) {
                return;
            }
            String name = editorName.getText().toString().trim();
            if (name.isEmpty()) {
                showHint("给搭子起个名字再保存吧");
                return;
            }
            editorPersona.name = name;
            String avatar = editorAvatar.getText().toString().trim();
            editorPersona.avatar = avatar.isEmpty() ? "🎵" : avatar;
            editorPersona.tagline = editorTagline.getText().toString().trim();
            editorPersona.personality = editorPersonality.getText().toString().trim();
            editorPersona.catchphrase = editorCatchphrase.getText().toString().trim();
            editorPersona.voicePitch = clampVoice(editorPersona.voicePitch);
            editorPersona.voiceRate = clampVoice(editorPersona.voiceRate);
            try {
                store.savePersona(currentSlot, editorPersona);
                if (!isEmpty(editorPersona.id)) {
                    creds.setCompanionPersonaId(editorPersona.id);
                }
            } catch (Throwable error) {
                showHint("保存失败，请重试");
                return;
            }
            persona = editorPersona;
            attachPersona();
            refreshPersonaHeader();
            syncVoiceUi();
            if (editorDialog != null) {
                editorDialog.dismiss();
            }
            showHint("搭子已换装：" + nameOf(persona));
            addBubble("assistant", "换装完成！现在我是「" + nameOf(persona) + "」。"
                    + (isEmpty(persona.tagline) ? "" : " " + persona.tagline), null);
        }

        private EditText editorInput(String hint, boolean multiLine) {
            EditText edit = new EditText(activity);
            edit.setHint(hint);
            edit.setSingleLine(!multiLine);
            if (multiLine) {
                edit.setGravity(Gravity.TOP);
                edit.setMinLines(2);
            }
            AppDialogs.themeInput(edit, 15f, multiLine);
            edit.setMinHeight(kit.dp(48f));
            return edit;
        }

        private TextView editorLabel(String text) {
            TextView label = kit.textBold(text, 12f * scale, c.textSecondary);
            label.setPadding(0, kit.dp(12f), 0, kit.dp(2f));
            return label;
        }

        // ---------------------------------------------------------- 存储安全代理

        private int safeAssistantCount() {
            try {
                int count = store.assistantCount();
                return clamp(count, 1, CompanionStore.MAX_ASSISTANTS);
            } catch (Throwable ignored) {
                return 1;
            }
        }

        private int safeActiveSlot() {
            try {
                return clamp(store.activeSlot(), 0, CompanionStore.MAX_ASSISTANTS - 1);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private boolean safeSetActiveSlot(int slot) {
            try {
                return store.setActiveSlot(slot);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private CompanionPersona safePersona(int slot) {
            try {
                return store.persona(slot);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private List<String[]> safeHistory(int slot) {
            try {
                List<String[]> history = store.history(slot);
                return history == null ? new ArrayList<String[]>() : history;
            } catch (Throwable ignored) {
                return new ArrayList<>();
            }
        }

        private boolean safeAddAssistant(CompanionPersona p) {
            try {
                return store.addAssistant(p);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean safeDeleteAssistant(int slot) {
            try {
                return store.deleteAssistant(slot);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean safeVoiceEnabled() {
            try {
                return creds.voiceEnabled();
            } catch (Throwable ignored) {
                return false;
            }
        }

        private String uniqueAssistantName(int count) {
            java.util.HashSet<String> used = new java.util.HashSet<>();
            // 槽位可能不连续：遍历全部槽位收集已用名字
            for (int i = 0; i < CompanionStore.MAX_ASSISTANTS; i++) {
                CompanionPersona p = safePersona(i);
                if (p != null && !isEmpty(p.name)) {
                    used.add(p.name);
                }
            }
            String candidate = "新搭子 " + (count + 1);
            int suffix = 2;
            while (used.contains(candidate)) {
                candidate = "新搭子 " + (count + 1) + "-" + suffix;
                suffix++;
            }
            return candidate;
        }

        private CompanionPersona copyPersona(CompanionPersona source) {
            if (source == null) {
                return null;
            }
            try {
                CompanionPersona copy = source.copy();
                if (copy != null) {
                    return copy;
                }
            } catch (Throwable ignored) {
            }
            return source;
        }

        // ---------------------------------------------------------- 通用小工具

        private TextView assistantAvatar(float sizeSp) {
            TextView view = kit.textBold(avatar(), sizeSp * scale, c.textPrimary);
            view.setGravity(Gravity.CENTER);
            view.setMinWidth(kit.dp(26f));
            return view;
        }

        private TextView compactLabel(String label, int color) {
            TextView button = kit.textBold(label, 12f * scale, color);
            button.setGravity(Gravity.CENTER);
            button.setPadding(kit.dp(7f), kit.dp(7f), kit.dp(7f), kit.dp(7f));
            button.setMinHeight(kit.dp(36f));
            button.setBackground(kit.ripple(c.surface2, 10f));
            button.setClickable(true);
            button.setFocusable(true);
            return button;
        }

        private TextView roundButton(String label, int bgColor, int textColor, float sizeSp) {
            TextView button = kit.textBold(label, sizeSp * scale, textColor);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(kit.dp(44f));
            button.setPadding(kit.dp(12f), kit.dp(10f), kit.dp(12f), kit.dp(10f));
            button.setBackground(kit.ripple(bgColor, 14f));
            button.setClickable(true);
            button.setFocusable(true);
            return button;
        }

        private LinearLayout.LayoutParams matchWrap(int topMarginPx) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topMarginPx;
            return lp;
        }

        private void scrollToBottom() {
            if (messageScroll == null) {
                return;
            }
            messageScroll.post(() -> {
                if (canTouch() && messageScroll != null) {
                    // fullScroll() moves focus into the message list and disconnects the IME.
                    // Scrolling the viewport must never change the composer's focus.
                    messageScroll.scrollTo(0, messages == null ? 0 : messages.getHeight());
                }
            });
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

        private void safeStopVoice() {
            if (voice != null) {
                try {
                    voice.stop();
                    voice.silence();
                } catch (Throwable ignored) {
                }
            }
            recognizing = false;
            speakingNow = false;
            autoListenPending = false;
        }

        private String name() {
            return nameOf(persona);
        }

        private String avatar() {
            return persona == null || isEmpty(persona.avatar) ? "🎵" : persona.avatar;
        }

        private String tagline() {
            if (persona == null) {
                return "你的 AI 音乐搭子";
            }
            if (!isEmpty(persona.tagline)) {
                return persona.tagline;
            }
            if (!isEmpty(persona.catchphrase)) {
                return persona.catchphrase;
            }
            return "你的 AI 音乐搭子";
        }

        private String personaId() {
            return persona == null ? "" : nullToEmpty(persona.id);
        }

        private float pitch() {
            return persona == null ? 1f : clampVoice(persona.voicePitch);
        }

        private float rate() {
            return persona == null ? 1f : clampVoice(persona.voiceRate);
        }

        private void hideKeyboard() {
            try {
                InputMethodManager imm = (InputMethodManager)
                        activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                View focus = dialog.getCurrentFocus();
                if (imm != null && focus != null) {
                    imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                }
            } catch (Throwable ignored) {
            }
        }

        /** 异步回调落回主线程时 Activity 可能已 finish/destroy；此时一律不碰 UI。 */
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

        private void tap() {
            try {
                activity.getWindow().getDecorView().performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignored) {
            }
        }

        private void toast(String text) {
            try {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        private void cleanup() {
            alive = false;
            uiHandler.removeCallbacksAndMessages(null);
            if (editorDialog != null) {
                try {
                    editorDialog.dismiss();
                } catch (Throwable ignored) {
                }
                editorDialog = null;
            }
            if (switcherDialog != null) {
                try {
                    switcherDialog.dismiss();
                } catch (Throwable ignored) {
                }
                switcherDialog = null;
            }
            try {
                if (persona != null) {
                    store.savePersona(currentSlot, persona);
                }
            } catch (Throwable ignored) {
            }
            if (voice != null) {
                try {
                    voice.stop();
                } catch (Throwable ignored) {
                }
                try {
                    voice.silence();
                } catch (Throwable ignored) {
                }
                try {
                    voice.release();
                } catch (Throwable ignored) {
                }
                voice = null;
            }
            if (client != null) {
                try {
                    client.cancel();
                } catch (Throwable ignored) {
                }
                try {
                    client.shutdown();
                } catch (Throwable ignored) {
                }
                client = null;
            }
            engine = null;
            hideKeyboard();
        }
    }

    // ------------------------------------------------------------------ 静态工具

    private static String errorText(DeepSeekClient.AiError error) {
        try {
            return AiSetupDialog.errorText(error);
        } catch (Throwable ignored) {
            return "AI 请求失败，请稍后重试";
        }
    }

    private static String nameOf(CompanionPersona persona) {
        if (persona == null || isEmpty(persona.name)) {
            return "音乐搭子";
        }
        return persona.name;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptySafe(String value, String fallback) {
        return isEmpty(value) ? fallback : value;
    }

    private static float clampVoice(float value) {
        if (Float.isNaN(value) || value < 0.5f) {
            return 0.5f;
        }
        return value > 2f ? 2f : value;
    }

    private static int mathClamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
