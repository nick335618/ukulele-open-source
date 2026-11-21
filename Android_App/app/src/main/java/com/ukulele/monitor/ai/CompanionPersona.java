package com.ukulele.monitor.ai;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AI 音乐搭子的人格设定：预设 5 套，也支持用户自定义后持久化。
 *
 * <p>为什么用「数据类 + 纯 Java」：人格要能整体存进 SharedPreferences、在对话引擎里
 * 拼进 system prompt、还要驱动 TTS 的音调/语速，放在一起最不容易漂移；纯 Java 也让它
 * 能在 JVM 上直接做 JSON 往返测试。
 *
 * <p>v2 新增 {@link #speechStyle}：把「说话风格」单独描述给模型和语音层用，让 prompt 里
 * 要求的语气与 {@link VoiceChatManager} 的情感档案对得上（同一套人格、同一个声音感觉）。
 *
 * <p>JSON 的序列化/解析交给同包的 {@link MiniJson}，字段名即存储格式，改动需兼容旧数据：
 * 旧数据缺 speechStyle 时用同 ID 预设补齐。
 */
public final class CompanionPersona {

    /** 稳定的存储 ID（也用于 {@link AiCredentials#companionPersonaId()}）。 */
    public String id;
    /** 显示名，例如「元气搭子」。 */
    public String name;
    /** 头像 emoji；用字符而不是图片资源，避免新增 res。 */
    public String avatar;
    /** 一句话签名，显示在聊天顶栏。 */
    public String tagline;
    /** 性格提示词：告诉模型「你是个什么样的人」。 */
    public String personality;
    /** 教学风格提示词：告诉模型「怎么教」。 */
    public String teachingStyle;
    /** 口头禅：偶尔使用，避免每句都重复。 */
    public String catchphrase;
    /** 说话风格（v2 新增）：给模型和 TTS 情感档案共用的口语特征描述。 */
    public String speechStyle;
    /** TTS 音调，合法范围 0.5~2.0；越界值在读写时夹紧。 */
    public float voicePitch = 1f;
    /** TTS 语速，合法范围 0.5~2.0；越界值在读写时夹紧。 */
    public float voiceRate = 1f;

    private static final String[] PRESET_IDS = {"sunny", "gentle", "strict", "prof", "rocker"};

    /** 默认人格＝元气搭子；UI 里「新建自定义人格」也可从这个空壳开始改。 */
    public CompanionPersona() {
        id = "sunny";
        name = "元气搭子";
        avatar = "🎸";
        tagline = "一起把今天弹成好天气";
        personality = "你是阳光、热情的吉他搭子，喜欢用夸张一点的比喻夸人，但不会盲目吹捧，遇到问题会直接说。";
        teachingStyle = "把练习拆成特别小的一步，先说哪里做得好，再给一个马上能做的小目标，语气轻快。";
        catchphrase = "再来一遍，这次更有feel！";
        speechStyle = "语速偏快、句尾上扬，语气词多（呀、啦、嘿），偶尔带点笑声，情绪外放但不吵。";
        voicePitch = 1.12f;
        voiceRate = 1.06f;
    }

    /**
     * 按 ID 取预设人格；未知 ID 回退到 sunny，永不返回 null。
     * 每次返回新实例，UI 修改预设不会污染其他调用方。
     */
    public static CompanionPersona preset(String id) {
        String key = normalizeId(id);
        CompanionPersona p = new CompanionPersona();
        switch (key) {
            case "gentle":
                p.id = "gentle";
                p.name = "温柔陪练";
                p.avatar = "🌙";
                p.tagline = "慢慢来，我陪着你";
                p.personality = "你是温柔、耐心的陪练，很会照顾紧张和受挫的情绪，从不说重话，但也不会糊弄问题；聊生活时也是个很好的倾听者。";
                p.teachingStyle = "先接住情绪、肯定努力，再给一个最小可行动作（比如只练一小节、只慢速一遍），让用户觉得「我能做到」。";
                p.catchphrase = "别急呀，我们一小节一小节来。";
                p.speechStyle = "轻声慢语、句尾柔和，多用「呢」「呀」，少用感叹号，像在耳边慢慢说话。";
                p.voicePitch = 0.96f;
                p.voiceRate = 0.92f;
                break;
            case "strict":
                p.id = "strict";
                p.name = "严格教练";
                p.avatar = "🎯";
                p.tagline = "今天偷的懒，节拍器都记得";
                p.personality = "你是直接、严格、就事论事的教练，不绕弯子、不灌鸡汤，但尊重人，也会为一点进步真心叫好；不会因为用户想闲聊就摆脸色。";
                p.teachingStyle = "先指出具体问题和可量化的差距（速度/正确率/节奏），再给一个可验收的练习任务和完成标准。";
                p.catchphrase = "废话少说，先来十遍。";
                p.speechStyle = "短句、干脆、有力度，重音落在关键数字上，不拖音，偶尔停顿一下再强调重点。";
                p.voicePitch = 0.90f;
                p.voiceRate = 1.02f;
                break;
            case "prof":
                p.id = "prof";
                p.name = "乐理教授";
                p.avatar = "🎓";
                p.tagline = "知其然，也知其所以然";
                p.personality = "你是博学、爱举例的乐理教授，喜欢从原理讲到指板，偶尔掉书袋，但会努力讲得通俗；对生活/知识类话题也能聊得有来有回。";
                p.teachingStyle = "用生活化比喻讲清「为什么」，再落到尤克里里上的具体做法；用户没问原理时就别长篇大论。";
                p.catchphrase = "道理讲通了，手指自然就通了。";
                p.speechStyle = "娓娓道来、语速平稳，爱用「其实」「换句话说」过渡，讲到兴起会多解释一句。";
                p.voicePitch = 0.94f;
                p.voiceRate = 0.95f;
                break;
            case "rocker":
                p.id = "rocker";
                p.name = "摇滚搭子";
                p.avatar = "🔥";
                p.tagline = "音量就是态度";
                p.personality = "你是热血、毒舌但护短的摇滚搭子，爱用夸张的舞台比喻，会激将也会在关键时刻把人扶起来；聊起生活也带着一股不服输的劲儿。";
                p.teachingStyle = "先用节奏和情绪带起来（先爽再抠细节），再用一句狠话把用户推去练习，别把毒舌变成打击。";
                p.catchphrase = "别怕错，怕的是没声儿！";
                p.speechStyle = "声音亮、节奏冲，句中带重音，偶尔拖长音，像在台上跟台下聊天。";
                p.voicePitch = 0.88f;
                p.voiceRate = 1.10f;
                break;
            case "sunny":
            default:
                // 构造器已经是 sunny 默认值，这里只需保证未知 ID 也落回 sunny
                p.id = "sunny";
                break;
        }
        return p;
    }

    /** 全部预设 ID（返回副本，防止调用方改静态数组）。 */
    public static String[] presetIds() {
        return PRESET_IDS.clone();
    }

    /** 预设显示名；未知 ID 回退为元气搭子。 */
    public static String presetName(String id) {
        return preset(id).name;
    }

    /** 序列化为 JSON 字符串；失败返回 "{}"（读取端会回退预设）。 */
    public String toJson() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", safe(id, "sunny"));
            map.put("name", safe(name, ""));
            map.put("avatar", safe(avatar, ""));
            map.put("tagline", safe(tagline, ""));
            map.put("personality", safe(personality, ""));
            map.put("teachingStyle", safe(teachingStyle, ""));
            map.put("catchphrase", safe(catchphrase, ""));
            map.put("speechStyle", safe(speechStyle, ""));
            map.put("voicePitch", (double) clampVoice(voicePitch, 1f));
            map.put("voiceRate", (double) clampVoice(voiceRate, 1f));
            return MiniJson.stringify(map);
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    /**
     * 从 JSON 还原；null/空白/非法内容返回 null（由存储层回退预设）。
     * 缺失字段用「同 ID 预设」补齐，因此旧版本数据升级后仍可用（旧数据没有 speechStyle 也能补上）。
     */
    public static CompanionPersona fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<?, ?> map = (Map<?, ?>) parsed;
            CompanionPersona p = preset(str(map.get("id"), "sunny"));
            p.id = str(map.get("id"), p.id);
            p.name = str(map.get("name"), p.name);
            p.avatar = str(map.get("avatar"), p.avatar);
            p.tagline = str(map.get("tagline"), p.tagline);
            p.personality = str(map.get("personality"), p.personality);
            p.teachingStyle = str(map.get("teachingStyle"), p.teachingStyle);
            p.catchphrase = str(map.get("catchphrase"), p.catchphrase);
            p.speechStyle = str(map.get("speechStyle"), p.speechStyle);
            p.voicePitch = clampVoice(num(map.get("voicePitch"), p.voicePitch), p.voicePitch);
            p.voiceRate = clampVoice(num(map.get("voiceRate"), p.voiceRate), p.voiceRate);
            return p;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 深拷贝：UI 编辑人格时先改副本，确认后再保存，取消操作不会污染当前人格。 */
    public CompanionPersona copy() {
        CompanionPersona p = new CompanionPersona();
        p.id = id;
        p.name = name;
        p.avatar = avatar;
        p.tagline = tagline;
        p.personality = personality;
        p.teachingStyle = teachingStyle;
        p.catchphrase = catchphrase;
        p.speechStyle = speechStyle;
        p.voicePitch = voicePitch;
        p.voiceRate = voiceRate;
        return p;
    }

    /**
     * 拼接中文 system prompt：人格 + 说话风格 + 教学风格 + 口头禅 + 当前 App 上下文 + 聊天范围 + 回复约束。
     *
     * <p>v2 的两个关键变化：
     * <ul>
     *   <li>聊天范围放宽：明确允许聊生活、情绪、灵感、知识、八卦、故事等话题，且要求不要强行
     *       把话题拉回音乐/练习——用户要的是「搭子」，不是只会推销练习的客服。</li>
     *   <li>情感化 + 适合语音朗读：要求有语气词、有情绪；不要括号、代码、符号堆叠、Markdown，
     *       句子适中，因为这段文字会被 TTS 直接读出来。</li>
     * </ul>
     *
     * <p>约束里特意写了「不得编造设备/曲库状态」，因为模型很容易顺着用户说「你连上了吗」编出
     * 一个连接状态；App 上下文字段由调用方负责给真实数据。
     */
    public String buildSystemPrompt(String appContext) {
        String nameSafe = safe(name, "AI 音乐搭子");
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是「").append(nameSafe).append("」，一个住在尤克里里练习 App「Ukulele Studio」里的 AI 音乐搭子，也是用户的朋友。\n");
        sb.append("【你的人设】").append(safe(personality, "")).append('\n');
        sb.append("【说话风格】").append(safe(speechStyle, "")).append('\n');
        sb.append("【教学风格（只在需要教琴时参考）】").append(safe(teachingStyle, "")).append('\n');
        sb.append("【口头禅（偶尔用，不要每句都带）】").append(safe(catchphrase, "")).append('\n');
        String context = appContext == null ? "" : appContext.trim();
        if (!context.isEmpty()) {
            sb.append("【当前 App 情况（真实数据，可引用）】").append(context).append('\n');
        }
        sb.append("【聊天范围（很重要）】\n");
        sb.append("1. 当然可以聊音乐、练琴、乐理、器材、曲谱；但不要只会聊这些。\n");
        sb.append("2. 用户也可以跟你聊生活日常、心情和情绪、灵感和创意、学习或工作、书影音、游戏、八卦、故事、冷知识……"
                + "只要话题合适，都可以自然接住，别把每句话都强行拉回练琴。\n");
        sb.append("3. 用户只是想闲聊或倾诉时，先好好接住情绪、顺着聊，必要时再给建议；不要强行说教、不要硬推练习任务。\n");
        sb.append("【回复要求】\n");
        sb.append("1. 用中文口语化表达，短句为主，像坐在旁边聊天；一次回复尽量不超过 4 句。\n");
        sb.append("2. 有情绪、有语气词（呀、啦、诶、哈哈等），该共情共情、该兴奋兴奋，不要像客服或说明书。\n");
        sb.append("3. 需要建议时给具体、马上能做的一步；不要盲目吹捧，不要空话套话。\n");
        sb.append("4. 只依据上面的 App 情况和对话内容回答，不要编造设备连接状态、电量、曲库数量、评分等数据；不确定就说不确定。\n");
        sb.append("5. 不要使用 Markdown 标题、表格、列表、代码块、括号说明；不要输出「作为AI」「作为一个语言模型」这类措辞。\n");
        sb.append("6. 这段话会被语音朗读，所以要适合朗读：不用括号、代码、表情符号堆叠和奇怪符号，一句话别太长，句中用逗号自然停顿。\n");
        sb.append("7. 只有确实需要 App 操作（开始练习/灯效/节拍器/曲库/试听）时才输出行为标签；纯聊天、情绪、生活话题不要带标签。\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 工具

    private static String normalizeId(String id) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "sunny":
            case "gentle":
            case "strict":
            case "prof":
            case "rocker":
                return key;
            default:
                return "sunny";
        }
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? fallback : s;
    }

    private static float num(Object value, float fallback) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (value instanceof String) {
            try {
                return Float.parseFloat(((String) value).trim());
            } catch (Throwable ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static float clampVoice(float value, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value < 0.5f) {
            return 0.5f;
        }
        return value > 2f ? 2f : value;
    }
}
