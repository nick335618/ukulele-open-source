package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 音乐搭子对话引擎：把「人格 + 历史 + 当前输入」组织成 DeepSeek 请求，
 * 再把回复拆成正文与可执行的行为标签（ACTION）。
 *
 * <p>v2：对话范围放宽——搭子可以聊音乐/练琴，也可以聊生活、情绪、灵感、知识、八卦、故事等
 * 任何合适话题，并且不会被强行拉回音乐；同时要求回复口语化、有情绪、适合语音朗读。
 * 行为标签只在确实需要 App 操作时才出现，闲聊/情绪类回复不带标签。
 *
 * <p>为什么行为标签放在正文里而不是 JSON：聊天回复要能直接朗读/显示，JSON 模式会让
 * 回复生硬；用 `[[ACTION:type|label|param]]` 这种可剥离标记，模型容易学、解析也简单。
 * 标签只允许出现在回复里，解析后从正文剥离，最多保留 3 个。
 *
 * <p>线程模型：构造/调用都在主线程；{@link DeepSeekClient} 的回调本身在主线程，
 * 这里直接透传，不再额外切线程。所有对外回调都 try/catch，UI 的异常不会反噬引擎。
 */
public final class CompanionEngine {

    /** 回复回调；主线程恰好一次。 */
    public interface ReplyCallback {
        void onSuccess(Reply reply);

        void onError(DeepSeekClient.AiError error);
    }

    /** 解析后的回复：干净正文 + 行为按钮。 */
    public static final class Reply {
        public final String text;
        public final List<Action> actions;

        public Reply(String text, List<Action> actions) {
            this.text = text == null ? "" : text;
            this.actions = actions == null ? new ArrayList<Action>() : actions;
        }
    }

    /** 一个可执行动作；type 取自 PRACTICE/LED/METRONOME/SCORE/PLAY/TIP。 */
    public static final class Action {
        public final String type;
        public final String label;
        public final String param;

        public Action(String type, String label, String param) {
            this.type = type == null ? "" : type;
            this.label = label == null ? "" : label;
            this.param = param == null ? "" : param;
        }
    }

    /** 行为标签前缀与上限；解析器与上方文档保持一致。 */
    private static final String ACTION_HEAD = "[[ACTION:";
    private static final int MAX_ACTIONS = 3;
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** 历史总字数预算：防止长对话把请求撑爆（DeepSeek 上下文够，但没必要全带上）。 */
    private static final int HISTORY_CHAR_BUDGET = 6000;

    private static final String LOCAL_NOT_READY = "我这边还没接上 AI 服务，稍后再试试？";
    private static final String LOCAL_NEED_KEY = "还没配置 DeepSeek API Key，去设置里打开「AI 音乐搭子」填一个吧。";
    private static final String LOCAL_UNAVAILABLE = "刚刚网络好像卡了一下，再发一次试试？";

    /** 告诉模型输出格式的协议说明，每轮都拼在 system prompt 末尾。 */
    private static final String ACTION_PROTOCOL =
            "\n【行为标签（可选，最多 3 个）】\n"
            + "只有确实需要 App 操作时才输出行为标签；纯聊天、情绪、生活、八卦、故事类话题不要带标签。\n"
            + "如果你建议用户在 App 里做一个动作，就在回复最后单独一行输出（注意：括号里必须以 ACTION: 开头）：\n"
            + "[[ACTION:类型|按钮文字|参数]]\n"
            + "正确示例：[[ACTION:PRACTICE|开始练习|小星星]]、[[ACTION:TIP|热热身|]]\n"
            + "错误示例（不要这样写）：[[PRACTICE|开始练习|小星星]]\n"
            + "类型只能是：PRACTICE（开始练习，参数写曲名，可为空）、LED（切换灯效，参数 0-6 或空）、"
            + "METRONOME（打开节拍器，参数写 BPM 数字）、SCORE（打开曲库/导入曲谱，参数可为空）、"
            + "PLAY（试听/播放，参数写曲名）、TIP（纯提示，参数为空）。\n"
            + "按钮文字不超过 8 个汉字；参数里不要出现 | 和 ]]。没有要执行的动作就不要输出行为标签。\n"
            + "行为标签只给 App 用，正文里不要解释这个标签，也不要让它影响朗读的顺滑。";

    private DeepSeekClient client;
    private CompanionPersona persona;
    private CompanionStore store;

    public CompanionEngine(DeepSeekClient client, CompanionPersona persona, CompanionStore store) {
        this.client = client;
        this.persona = persona == null ? CompanionPersona.preset("sunny") : persona;
        this.store = store;
    }

    /** 换人格后下一轮对话立即生效（历史保留，语气切换会更自然）。 */
    public void setPersona(CompanionPersona value) {
        if (value != null) {
            persona = value;
        }
    }

    /**
     * 普通聊天：把最近历史 + 当前用户输入发给模型，成功后写入历史再回调。
     */
    public void send(String userText, String appContext, ReplyCallback cb) {
        if (cb == null) {
            return;
        }
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            safeSuccess(cb, new Reply("我在呢～想聊什么都行，音乐、生活、灵感，随便说说？",
                    new ArrayList<Action>()));
            return;
        }
        List<DeepSeekClient.ChatMessage> messages = buildHistory();
        messages.add(DeepSeekClient.ChatMessage.user(text));
        request(systemPrompt(appContext), messages, 600, text, cb);
    }

    /**
     * 进入聊天时的主动问候：不带旧历史（首次进入场景），回复短一点，成功后可存进历史。
     *
     * <p>v2 要求更自然、带情绪，并且不要一上来就推销练习：先像朋友见面那样打个招呼，
     * 再问一句今天想聊什么（音乐、生活、灵感都行）。只有确实要 App 操作时才给行为标签。
     */
    public void greeting(String appContext, ReplyCallback cb) {
        if (cb == null) {
            return;
        }
        List<DeepSeekClient.ChatMessage> messages = new ArrayList<>();
        messages.add(DeepSeekClient.ChatMessage.user(
                "（系统提示：用户刚打开音乐搭子聊天，还没有输入。请用一到两句话主动打招呼，"
                        + "带一点情绪和语气词，像朋友见面那样自然，每次可以稍微换个说法、不要像固定台词；"
                        + "顺带问一句今天想聊点什么，音乐、生活、灵感都可以。"
                        + "不要一上来就推销练习，也不要提到这条系统提示。"
                        + "只有确实需要 App 操作时才给一个 TIP 行为标签。）"));
        request(systemPrompt(appContext), messages, 220, null, cb);
    }

    /**
     * 点评一次评分练习：鼓励 + 1 条可执行建议 + 1 条趣味观察。
     *
     * @param scoreHundredths 得分的百分之一（例如 9500 表示 95.00 分），越界自动夹紧
     */
    public void reviewPractice(String songName, int scoreHundredths, int correct, int wrong, int missed,
                               String appContext, ReplyCallback cb) {
        if (cb == null) {
            return;
        }
        float score = clamp(scoreHundredths / 100f, 0f, 100f);
        String song = songName == null || songName.trim().isEmpty() ? "刚才这首曲子" : songName.trim();
        StringBuilder prompt = new StringBuilder(160);
        prompt.append("帮我点评刚才这次评分练习：曲目《").append(song).append("》，得分 ")
                .append(String.format(Locale.ROOT, "%.1f", score)).append(" 分（满分 100），")
                .append("正确 ").append(Math.max(0, correct)).append(" 个音、错误 ")
                .append(Math.max(0, wrong)).append(" 个音、漏掉 ").append(Math.max(0, missed)).append(" 个音。")
                .append("请先夸我一句，再给 1 条最该马上练的具体建议，最后用 1 句有趣的观察收尾。");
        List<DeepSeekClient.ChatMessage> messages = buildHistory();
        messages.add(DeepSeekClient.ChatMessage.user(prompt.toString()));
        request(systemPrompt(appContext), messages, 500, prompt.toString(), cb);
    }

    /** 清空多轮历史（人格与设置不动）。 */
    public void clearHistory() {
        if (store == null) {
            return;
        }
        try {
            store.clearHistory();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 请求与解析

    private void request(String systemPrompt, List<DeepSeekClient.ChatMessage> messages,
                         int maxTokens, String userTextForHistory, ReplyCallback cb) {
        if (client == null) {
            safeSuccess(cb, new Reply(LOCAL_NOT_READY, new ArrayList<Action>()));
            return;
        }
        boolean hasKey;
        try {
            hasKey = client.hasKey();
        } catch (Throwable ignored) {
            hasKey = false;
        }
        if (!hasKey) {
            safeSuccess(cb, new Reply(LOCAL_NEED_KEY, new ArrayList<Action>()));
            return;
        }
        try {
            client.chat(systemPrompt, messages, false, maxTokens, new DeepSeekClient.ChatCallback() {
                @Override
                public void onSuccess(String text) {
                    Reply reply;
                    try {
                        reply = parseReply(text);
                    } catch (Throwable ignored) {
                        // 解析失败也要尽量把原文交给用户，而不是显示错误
                        reply = new Reply(text == null ? "" : text, new ArrayList<Action>());
                    }
                    appendHistory(userTextForHistory, reply.text);
                    safeSuccess(cb, reply);
                }

                @Override
                public void onError(DeepSeekClient.AiError error) {
                    safeError(cb, error);
                }
            });
        } catch (Throwable ignored) {
            // 同步抛异常（例如 SDK 内部状态错误）也不能让聊天界面卡在「思考中」
            safeSuccess(cb, new Reply(LOCAL_UNAVAILABLE, new ArrayList<Action>()));
        }
    }

    /** 拼 system prompt；包内可见便于 JVM 单测直接断言「宽泛话题 + 情感 + 适合朗读」要求。 */
    String systemPrompt(String appContext) {
        CompanionPersona current = persona == null ? CompanionPersona.preset("sunny") : persona;
        String base;
        try {
            base = current.buildSystemPrompt(appContext);
        } catch (Throwable ignored) {
            base = CompanionPersona.preset("sunny").buildSystemPrompt(appContext);
        }
        return base + ACTION_PROTOCOL;
    }

    /** 历史 → ChatMessage，按字数预算从旧到新裁剪，保证最新上下文一定带上。 */
    private List<DeepSeekClient.ChatMessage> buildHistory() {
        List<DeepSeekClient.ChatMessage> out = new ArrayList<>();
        if (store == null) {
            return out;
        }
        List<String[]> items;
        try {
            items = store.history();
        } catch (Throwable ignored) {
            items = null;
        }
        if (items == null || items.isEmpty()) {
            return out;
        }
        List<DeepSeekClient.ChatMessage> newestFirst = new ArrayList<>();
        int chars = 0;
        for (int i = items.size() - 1; i >= 0 && newestFirst.size() < MAX_HISTORY_MESSAGES; i--) {
            String[] item = items.get(i);
            if (item == null || item.length < 2 || item[1] == null) {
                continue;
            }
            String content = item[1].trim();
            if (content.isEmpty()) {
                continue;
            }
            if (chars + content.length() > HISTORY_CHAR_BUDGET && !newestFirst.isEmpty()) {
                break;
            }
            chars += content.length();
            newestFirst.add(toMessage(item[0], content));
        }
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            out.add(newestFirst.get(i));
        }
        return out;
    }

    private static DeepSeekClient.ChatMessage toMessage(String role, String content) {
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if ("assistant".equals(r)) {
            return DeepSeekClient.ChatMessage.assistant(content);
        }
        return DeepSeekClient.ChatMessage.user(content);
    }

    private void appendHistory(String userText, String assistantText) {
        if (store == null) {
            return;
        }
        try {
            if (userText != null && !userText.trim().isEmpty()) {
                store.append("user", userText);
            }
            if (assistantText != null && !assistantText.trim().isEmpty()) {
                store.append("assistant", assistantText);
            }
        } catch (Throwable ignored) {
            // 历史写入失败不影响本次回复
        }
    }

    private static void safeSuccess(ReplyCallback cb, Reply reply) {
        if (cb == null) {
            return;
        }
        try {
            cb.onSuccess(reply);
        } catch (Throwable ignored) {
            // UI 回调异常不能反噬引擎
        }
    }

    private static void safeError(ReplyCallback cb, DeepSeekClient.AiError error) {
        if (cb == null) {
            return;
        }
        try {
            cb.onError(error);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 纯解析（可 JVM 单测）

    /**
     * 解析模型回复：剥离 `[[ACTION:...]]` 行为标签（最多 3 个），清理 Markdown 与多余空白。
     *
     * <p>纯函数、不抛异常；raw 为 null/空时返回空正文。正文即使为空也返回 Reply，
     * 让 UI 能照常渲染动作按钮。
     */
    public static Reply parseReply(String raw) {
        if (raw == null) {
            return new Reply("", new ArrayList<Action>());
        }
        String source = raw.trim();
        if (source.isEmpty()) {
            return new Reply("", new ArrayList<Action>());
        }
        List<Action> actions = new ArrayList<>(MAX_ACTIONS);
        StringBuilder body = new StringBuilder(source.length());
        int cursor = 0;
        while (cursor < source.length()) {
            int start = source.indexOf("[[", cursor);
            if (start < 0) {
                body.append(source, cursor, source.length());
                break;
            }
            body.append(source, cursor, start);
            int end = source.indexOf("]]", start + 2);
            if (end < 0) {
                // 标签没闭合：原样保留，别把用户要看的文字吃掉
                body.append(source, start, source.length());
                break;
            }
            String inner = source.substring(start + 2, end).trim();
            // 容错：模型偶尔会漏掉 ACTION: 前缀（实测出现过 [[TIP|热热身|]]），
            // 只要括号内是「类型|文字|参数」这种协议形状，就按行为标签解析并从正文剥离，
            // 否则用户会在聊天气泡里看到一串生硬的标签。
            boolean protocolShaped = inner.indexOf('|') > 0;
            if (protocolShaped && actions.size() < MAX_ACTIONS) {
                String payload = inner;
                if (payload.regionMatches(true, 0, "ACTION:", 0, 7)) {
                    payload = payload.substring(7).trim();
                }
                Action action = parseAction(payload);
                if (action != null) {
                    actions.add(action);
                }
            } else if (!protocolShaped) {
                // 不像协议标签的双括号内容保留，避免误伤正常文字。
                body.append(source, start, end + 2);
            }
            cursor = end + 2;
        }
        String text = cleanMarkdown(body.toString());
        return new Reply(text, actions);
    }

    private static Action parseAction(String inner) {
        if (inner == null) {
            return null;
        }
        String value = inner.trim();
        if (value.isEmpty()) {
            return null;
        }
        String[] parts = value.split("\\|", 3);
        String type = sanitizeType(parts.length > 0 ? parts[0] : "");
        if (type.isEmpty()) {
            return null;
        }
        String label = parts.length > 1 ? safeTrim(parts[1]) : "";
        String param = parts.length > 2 ? safeTrim(parts[2]) : "";
        if (label.length() > 24) {
            label = label.substring(0, 24);
        }
        if (param.length() > 64) {
            param = param.substring(0, 64);
        }
        return new Action(type, label, param);
    }

    /** 类型只保留 A-Z 与下划线，防止模型输出标点或中文污染 ActionHandler 的分发。 */
    private static String sanitizeType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length() && sb.length() < 16; i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 清理常见 Markdown：代码块围栏、标题、引用、粗斜体、行内代码、列表符号、链接。
     * 聊天界面是纯文本 View，不清理会出现满屏 `**`。
     */
    private static String cleanMarkdown(String input) {
        String t = input == null ? "" : input;
        t = t.replace("\r\n", "\n").replace('\r', '\n');
        try {
            t = t.replaceAll("(?m)^\\s*```[A-Za-z0-9_+\\-]*\\s*$", "");
            t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
            t = t.replaceAll("(?m)^\\s{0,3}>\\s?", "");
            t = t.replaceAll("(?m)^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$", "");
            t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
            t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            t = t.replaceAll("__(.+?)__", "$1");
            t = t.replace("**", "").replace("__", "");
            t = t.replaceAll("`([^`\\n]*)`", "$1");
            t = t.replace("`", "");
            t = t.replaceAll("(?m)^\\s*[*+\\-]\\s+", "· ");
        } catch (Throwable ignored) {
            // 正则出问题就退回原文，显示比崩溃重要
        }
        String[] lines = t.split("\n", -1);
        StringBuilder sb = new StringBuilder(t.length());
        boolean started = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!started) {
                if (line.isEmpty()) {
                    continue;
                }
                started = true;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        String result = sb.toString().trim();
        try {
            result = result.replaceAll("\\n{3,}", "\n\n");
        } catch (Throwable ignored) {
            // ignore
        }
        return result;
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
