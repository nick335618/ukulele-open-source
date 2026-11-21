package com.ukulele.monitor.ai;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ukulele.monitor.ui.AppDialogs;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.SettingsStore;
import com.ukulele.monitor.ui.UiKit;

/**
 * AI 新手配置弹窗：官方通道 / 个人 API Key / 关闭 AI 三选一。
 *
 * <p>设计上刻意做成「一次只做一个决定」的三段式：官方通道只要开发者口令，个人 Key 才展开
 * 模型选择与连接测试，关闭 AI 则是一句话说明后果。这样第一次用的用户不需要理解 API Key、
 * 模型、接口地址这些概念也能把 AI 打开。
 *
 * <p>隐私文案放在顶部而不是折叠在角落：Key 只存本机、请求直连 api.deepseek.com；语音对话
 * 需要麦克风权限。测试连接与内嵌输入框都在弹窗内完成，不会把 Key 写到日志或界面之外。
 *
 * <p>风格与工程现有 AppDialogs/UiKit 一致（平台 View + AppTheme 令牌 + 应用字号档位），
 * 不新增资源/权限；所有网络回调都在主线程，弹窗关闭后回调直接丢弃。
 */
public final class AiSetupDialog {

    /** 获取 API Key 的官方页面。 */
    private static final String API_KEY_URL = "https://platform.deepseek.com/";

    private AiSetupDialog() {
    }

    /**
     * 打开配置弹窗。
     *
     * @param activity  宿主 Activity（用于弹窗与启动浏览器）
     * @param onChanged 配置保存/切换成功后回调；由调用方刷新 AI 页面状态。可为 null
     */
    public static void show(final Activity activity, final Runnable onChanged) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            new Ui(activity, onChanged).open();
        } catch (Throwable ignored) {
            // 弹窗构建失败（极端低内存/主题异常）时不让入口点击崩溃
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private static final class Ui {
        private final Activity activity;
        private final Runnable onChanged;
        private final UiKit kit;
        private final AppTheme.Colors c;
        private final float scale;
        private final AiCredentials creds;
        private final Dialog dialog;

        private boolean alive = true;
        private int mode;

        private final TextView[] channelButtons = new TextView[3];
        private final LinearLayout[] panels = new LinearLayout[3];
        private LinearLayout panelHost;

        // 官方通道
        private EditText officialInput;
        private TextView officialStatus;
        private TextView officialAction;

        // 个人 Key
        private EditText keyInput;
        private TextView keyStatus;
        private TextView testButton;
        private TextView saveButton;
        private TextView modelHint;
        private LinearLayout modelRow;
        private TextView flashSegment;
        private TextView proSegment;
        private int selectedModel; // 0=flash, 1=pro
        private DeepSeekClient testClient;
        private int testToken; // 连续点「测试连接」时让旧请求的回调失效

        Ui(Activity activity, Runnable onChanged) {
            this.activity = activity;
            this.onChanged = onChanged;
            this.kit = UiKit.create(activity);
            this.c = AppTheme.colors();
            this.scale = textScale(activity);
            this.creds = new AiCredentials(activity);
            this.dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
            this.selectedModel = isProModel(creds.model()) ? 1 : 0;
        }

        void open() {
            ScrollView scroller = new ScrollView(activity);
            scroller.setFillViewport(true);
            scroller.setBackgroundColor(c.bgRoot);

            LinearLayout root = kit.column();
            root.setPadding(kit.dp(20f), kit.dp(14f), kit.dp(20f), kit.dp(16f));
            root.setBackgroundColor(c.bgRoot);

            root.addView(buildTopBar());
            root.addView(buildIntro());
            root.addView(buildChannelSelector());
            panelHost = kit.column();
            panels[0] = buildOfficialPanel();
            panels[1] = buildPersonalPanel();
            panels[2] = buildOffPanel();
            for (LinearLayout panel : panels) {
                panelHost.addView(panel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            root.addView(panelHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            scroller.addView(root, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setContentView(scroller);
            dialog.setCanceledOnTouchOutside(true);
            dialog.setOnDismissListener(d -> {
                alive = false;
                if (testClient != null) {
                    try {
                        testClient.shutdown();
                    } catch (Throwable ignored) {
                    }
                    testClient = null;
                }
                hideKeyboard();
            });

            // 初始通道：沿用上次选择；若上次是「关闭」，新用户优先引导到能直接用的通道。
            int initial = creds.mode();
            if (initial == AiCredentials.MODE_OFF) {
                initial = isEmpty(creds.officialKey())
                        ? AiCredentials.MODE_PERSONAL : AiCredentials.MODE_OFFICIAL;
            }
            selectMode(initial);

            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(c.bgRoot));
                android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                window.setLayout((int) (dm.widthPixels * 0.94f), (int) (dm.heightPixels * 0.90f));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }

        // ---------------------------------------------------------- 静态内容

        private View buildTopBar() {
            LinearLayout bar = kit.row();
            TextView title = kit.textBold("接入 DeepSeek AI", 20f * scale, c.textPrimary);
            bar.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = kit.textBold("关闭", 13f * scale, c.textSecondary);
            close.setGravity(Gravity.CENTER);
            close.setPadding(kit.dp(10f), kit.dp(8f), kit.dp(4f), kit.dp(8f));
            close.setMinHeight(kit.dp(40f));
            close.setClickable(true);
            close.setFocusable(true);
            close.setOnClickListener(v -> dialog.dismiss());
            bar.addView(close);
            return bar;
        }

        private View buildIntro() {
            LinearLayout box = kit.column();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(10f);

            TextView intro = kit.text(
                    "让 App 接入 DeepSeek：可填自己的 API Key，或使用开发者官方通道。",
                    13f * scale, c.textSecondary);
            intro.setLineSpacing(kit.dp(3f), 1f);
            box.addView(intro, lp);

            TextView privacy = kit.text(
                    "隐私提示：Key 只保存在本机，AI 请求直接发往 api.deepseek.com；语音对话需要麦克风权限。",
                    11f * scale, c.textTertiary);
            privacy.setLineSpacing(kit.dp(3f), 1f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = kit.dp(6f);
            box.addView(privacy, plp);
            return box;
        }

        private View buildChannelSelector() {
            LinearLayout row = kit.row();
            String[] labels = {"官方通道", "我的 API Key", "关闭 AI"};
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                TextView segment = kit.segment(labels[i], false, i, labels.length);
                segment.setOnClickListener(v -> selectMode(indexToMode(index)));
                channelButtons[i] = segment;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) {
                    lp.leftMargin = kit.dp(6f);
                }
                row.addView(segment, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(14f);
            LinearLayout box = kit.column();
            box.addView(row, lp);
            return box;
        }

        // ---------------------------------------------------------- 官方通道面板

        private LinearLayout buildOfficialPanel() {
            LinearLayout panel = kit.column();
            panel.setPadding(0, kit.dp(14f), 0, 0);

            TextView hint = kit.text("输入开发者提供的口令即可使用官方通道，不需要自己申请 API Key。",
                    13f * scale, c.textSecondary);
            hint.setLineSpacing(kit.dp(3f), 1f);
            panel.addView(hint);

            officialInput = input("开发者口令", false);
            officialInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            inputLp.topMargin = kit.dp(12f);
            panel.addView(officialInput, inputLp);

            officialAction = kit.action("使用官方通道", c.accent, c.bgRoot);
            officialAction.setOnClickListener(v -> tryOfficial());
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionLp.topMargin = kit.dp(12f);
            panel.addView(officialAction, actionLp);

            officialStatus = kit.text("", 12f * scale, c.textSecondary);
            officialStatus.setLineSpacing(kit.dp(3f), 1f);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = kit.dp(10f);
            panel.addView(officialStatus, statusLp);

            if (isEmpty(creds.officialKey())) {
                setStatus(officialStatus,
                        "当前安装包未配置官方密钥，请联系开发者，或改用「我的 API Key」。", true);
            } else if (creds.officialUnlocked()) {
                setStatus(officialStatus, "官方通道已解锁，输入口令可直接切换到官方通道。", false);
            } else {
                setStatus(officialStatus, "口令由开发者提供；输入后点上方按钮即可启用。", false);
            }
            return panel;
        }

        private void tryOfficial() {
            officialInput.clearFocus();
            hideKeyboard();
            String password = officialInput.getText() == null
                    ? "" : officialInput.getText().toString().trim();
            if (isEmpty(password)) {
                setStatus(officialStatus, "请先输入开发者口令。", true);
                return;
            }
            if (isEmpty(creds.officialKey())) {
                setStatus(officialStatus,
                        "当前安装包未配置官方密钥，请联系开发者，或改用「我的 API Key」。", true);
                return;
            }
            boolean ok;
            try {
                ok = creds.unlockOfficial(password);
            } catch (Throwable error) {
                ok = false;
            }
            if (!ok) {
                setStatus(officialStatus, "口令不正确，请向开发者确认后再试。", true);
                return;
            }
            creds.setMode(AiCredentials.MODE_OFFICIAL);
            toast("官方通道已开启");
            notifyChanged();
            dialog.dismiss();
        }

        // ---------------------------------------------------------- 个人 Key 面板

        private LinearLayout buildPersonalPanel() {
            LinearLayout panel = kit.column();
            panel.setPadding(0, kit.dp(14f), 0, 0);

            TextView hint = kit.text(
                    "使用自己的 DeepSeek API Key：Key 只保存在本机，并直接请求官方接口。",
                    13f * scale, c.textSecondary);
            hint.setLineSpacing(kit.dp(3f), 1f);
            panel.addView(hint);

            LinearLayout keyRow = kit.row();
            keyInput = input("sk-…（粘贴你的 API Key）", false);
            keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            String existing = creds.personalKey();
            if (!isEmpty(existing)) {
                keyInput.setText(existing);
                keyInput.setSelection(keyInput.getText().length());
            }
            keyRow.addView(keyInput, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView eye = kit.textBold("显示", 12f * scale, c.textSecondary);
            eye.setGravity(Gravity.CENTER);
            eye.setPadding(kit.dp(10f), kit.dp(8f), kit.dp(10f), kit.dp(8f));
            eye.setMinHeight(kit.dp(44f));
            eye.setClickable(true);
            eye.setFocusable(true);
            eye.setOnClickListener(v -> {
                // 用 VISIBLE_PASSWORD(0x90) 整体比较判断当前是否明文；不能用 PASSWORD(0x80) 位与，
                // 因为 0x90 & 0x80 也非 0，会导致第一次点击后状态判断卡死、按钮文字错乱（复核 P2-5）。
                boolean visible = (keyInput.getInputType()
                        & InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
                keyInput.setInputType(visible
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                keyInput.setSelection(keyInput.getText().length());
                eye.setText(visible ? "显示" : "隐藏");
            });
            LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eyeLp.leftMargin = kit.dp(8f);
            keyRow.addView(eye, eyeLp);
            LinearLayout.LayoutParams keyRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            keyRowLp.topMargin = kit.dp(12f);
            panel.addView(keyRow, keyRowLp);

            TextView getKey = kit.textBold("获取 API Key ↗", 13f * scale, c.accent);
            getKey.setPadding(0, kit.dp(10f), 0, kit.dp(4f));
            getKey.setClickable(true);
            getKey.setFocusable(true);
            getKey.setOnClickListener(v -> openKeyPage());
            panel.addView(getKey);

            panel.addView(kit.sectionHeader("模型"));
            modelRow = kit.row();
            flashSegment = kit.segment("deepseek-flash · 推荐", selectedModel == 0, 0, 2);
            proSegment = kit.segment("deepseek-v4-pro", selectedModel == 1, 1, 2);
            flashSegment.setOnClickListener(v -> selectModel(0));
            proSegment.setOnClickListener(v -> selectModel(1));
            modelRow.addView(flashSegment, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams proLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            proLp.leftMargin = kit.dp(6f);
            modelRow.addView(proSegment, proLp);
            panel.addView(modelRow);

            modelHint = kit.text("", 11f * scale, c.textTertiary);
            modelHint.setLineSpacing(kit.dp(2f), 1f);
            LinearLayout.LayoutParams modelHintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            modelHintLp.topMargin = kit.dp(6f);
            panel.addView(modelHint, modelHintLp);
            updateModelHint();

            keyStatus = kit.text("", 12f * scale, c.textSecondary);
            keyStatus.setLineSpacing(kit.dp(3f), 1f);
            LinearLayout.LayoutParams keyStatusLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            keyStatusLp.topMargin = kit.dp(10f);
            panel.addView(keyStatus, keyStatusLp);

            LinearLayout actions = kit.row();
            testButton = kit.actionOutline("测试连接");
            saveButton = kit.action("保存并使用", c.accent, c.bgRoot);
            actions.addView(testButton, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            saveLp.leftMargin = kit.dp(8f);
            actions.addView(saveButton, saveLp);
            testButton.setOnClickListener(v -> testConnection());
            saveButton.setOnClickListener(v -> savePersonal());
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionsLp.topMargin = kit.dp(14f);
            panel.addView(actions, actionsLp);
            return panel;
        }

        private void selectModel(int index) {
            selectedModel = index == 1 ? 1 : 0;
            kit.styleSegment(flashSegment, selectedModel == 0);
            kit.styleSegment(proSegment, selectedModel == 1);
            updateModelHint();
        }

        private void updateModelHint() {
            if (modelHint == null) {
                return;
            }
            modelHint.setText(selectedModel == 1
                    ? "deepseek-v4-pro：纯文本模型，适合曲谱结构与乐理推理，不支持看图。"
                    : "deepseek-flash：支持图片理解（相册识谱可用），日常对话速度快。");
        }

        private void testConnection() {
            hideKeyboard();
            final String key = keyInput.getText() == null
                    ? "" : keyInput.getText().toString().trim();
            if (isEmpty(key)) {
                setStatus(keyStatus, "请先填写 API Key，并把「测试连接」当作唯一验证。", true);
                return;
            }
            if (testClient != null) {
                testClient.shutdown();
                testClient = null;
            }
            testButton.setEnabled(false);
            testButton.setAlpha(0.6f);
            testButton.setText("测试中…");
            setStatus(keyStatus, "正在连接 DeepSeek 并验证 Key…", false);
            DeepSeekClient client;
            try {
                client = new DeepSeekClient(key, creds.baseUrl(), modelName());
            } catch (Throwable error) {
                resetTestButton();
                setStatus(keyStatus, "无法创建网络客户端，请稍后重试。", true);
                return;
            }
            testClient = client;
            final int token = ++testToken;
            client.testConnection(new DeepSeekClient.ChatCallback() {
                @Override
                public void onSuccess(String text) {
                    if (!canTouch() || token != testToken) {
                        return;
                    }
                    if (testClient == client) {
                        testClient = null;
                    }
                    resetTestButton();
                    setStatus(keyStatus, "连接成功，Key 与模型都可用。", false);
                    keyStatus.setTextColor(c.success);
                }

                @Override
                public void onError(DeepSeekClient.AiError e) {
                    if (!canTouch() || token != testToken) {
                        return;
                    }
                    if (testClient == client) {
                        testClient = null;
                    }
                    resetTestButton();
                    setStatus(keyStatus, "连接失败：" + errorText(e), true);
                }
            });
        }

        private void resetTestButton() {
            if (testButton == null) {
                return;
            }
            testButton.setEnabled(true);
            testButton.setAlpha(1f);
            testButton.setText("测试连接");
        }

        private void savePersonal() {
            hideKeyboard();
            final String key = keyInput.getText() == null
                    ? "" : keyInput.getText().toString().trim();
            if (isEmpty(key)) {
                setStatus(keyStatus, "请填写 API Key，或返回上方选择「官方通道」。", true);
                return;
            }
            boolean looksRight;
            try {
                looksRight = AiCredentials.looksLikeKey(key);
            } catch (Throwable ignored) {
                looksRight = true;
            }
            // 不因格式差异阻止保存：个人 Key 的真实校验是「测试连接」，这里只提醒。
            if (!looksRight) {
                setStatus(keyStatus, "Key 格式不常见（通常以 sk- 开头），已按原样保存；建议先测试连接。", false);
                keyStatus.setTextColor(c.warn);
            }
            try {
                creds.setPersonalKey(key);
                creds.setModel(modelName());
                creds.setMode(AiCredentials.MODE_PERSONAL);
            } catch (Throwable error) {
                setStatus(keyStatus, "保存失败，请重试。", true);
                return;
            }
            toast("已保存，AI 功能已开启");
            notifyChanged();
            dialog.dismiss();
        }

        private String modelName() {
            return selectedModel == 1 ? DeepSeekClient.MODEL_PRO : DeepSeekClient.MODEL_FLASH;
        }

        private void openKeyPage() {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(API_KEY_URL)));
            } catch (Throwable error) {
                toast("无法打开浏览器，请手动访问 platform.deepseek.com");
            }
        }

        // ---------------------------------------------------------- 关闭面板

        private LinearLayout buildOffPanel() {
            LinearLayout panel = kit.column();
            panel.setPadding(0, kit.dp(14f), 0, 0);
            TextView hint = kit.text(
                    "关闭后，AI 音乐搭子、AI 曲谱导入和语音对话都不可用；已保存的 Key 不会删除，随时可以回来开启。",
                    13f * scale, c.textSecondary);
            hint.setLineSpacing(kit.dp(3f), 1f);
            panel.addView(hint);

            TextView off = kit.action("关闭 AI", c.surface2, c.textPrimary);
            off.setOnClickListener(v -> {
                try {
                    creds.setMode(AiCredentials.MODE_OFF);
                } catch (Throwable ignored) {
                }
                toast("AI 功能已关闭");
                notifyChanged();
                dialog.dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = kit.dp(14f);
            panel.addView(off, lp);
            return panel;
        }

        // ---------------------------------------------------------- 通用工具

        private void selectMode(int newMode) {
            mode = newMode;
            int index = modeToIndex(newMode);
            for (int i = 0; i < panels.length; i++) {
                if (panels[i] != null) {
                    panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < channelButtons.length; i++) {
                if (channelButtons[i] != null) {
                    kit.styleSegment(channelButtons[i], i == index);
                }
            }
        }

        private static int indexToMode(int index) {
            if (index == 0) {
                return AiCredentials.MODE_OFFICIAL;
            }
            if (index == 1) {
                return AiCredentials.MODE_PERSONAL;
            }
            return AiCredentials.MODE_OFF;
        }

        private static int modeToIndex(int mode) {
            if (mode == AiCredentials.MODE_OFFICIAL) {
                return 0;
            }
            if (mode == AiCredentials.MODE_PERSONAL) {
                return 1;
            }
            return 2;
        }

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

        private void setStatus(TextView view, String text, boolean error) {
            if (view == null) {
                return;
            }
            view.setText(text == null ? "" : text);
            view.setTextColor(error ? c.danger : c.textSecondary);
        }

        private void notifyChanged() {
            if (onChanged == null) {
                return;
            }
            try {
                onChanged.run();
            } catch (Throwable ignored) {
                // 调用方刷新失败不影响配置已经保存
            }
        }

        private void toast(String text) {
            try {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
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
    }

    // ------------------------------------------------------------------ 包内工具

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isProModel(String model) {
        return model != null && model.contains("pro");
    }

    /** 与 AppDialogs 相同的字号档位逻辑：UiKit 的 SP 只跟随系统字号，应用内字号要单独乘。 */
    static float textScale(Activity activity) {
        try {
            int setting = SettingsStore.store(activity).textScale();
            if (setting == SettingsStore.TEXT_SCALE_SMALL) {
                return 0.88f;
            }
            if (setting == SettingsStore.TEXT_SCALE_LARGE) {
                return 1.16f;
            }
        } catch (Throwable ignored) {
        }
        return 1f;
    }

    /** 统一的错误文案：优先服务端给的 message，其次按状态码给中文提示。 */
    static String errorText(DeepSeekClient.AiError error) {
        if (error == null) {
            return "网络异常，请稍后重试";
        }
        if (!isEmpty(error.message)) {
            return error.message;
        }
        if (error.httpCode == 401) {
            return "API Key 无效或已失效";
        }
        if (error.httpCode == 402) {
            return "账户余额不足";
        }
        if (error.httpCode == 429) {
            return "请求太频繁，请稍后再试";
        }
        if (error.httpCode > 0) {
            return "服务返回错误（" + error.httpCode + "）";
        }
        return error.retryable ? "网络不稳定，请重试" : "请求失败，请稍后重试";
    }
}
