package com.ukulele.monitor.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灯效导演：让 AI 把用户的一句自然语言愿望翻译成设备可执行的 LED 参数。
 *
 * <p><b>为什么要有这一层：</b>灯效页有 mode / axis / speed / brightness 四个参数、几十种
 * 组合，普通用户不知道该调哪个。AI 只负责「选参数」，真正的下发仍走 MainActivity 既有的
 * LED 指令，因此本类不接触协议、也不改变设备行为。
 *
 * <p><b>为什么必须夹紧：</b>设备端只接受 mode 0..6、axis 0/1、speed/brightness 0..255，
 * 模型经常给出越界值（例如 speed=999）或把数字写成字符串。{@link #parse(String)} 统一
 * 做范围夹紧与类型宽松解析，保证 UI 拿到的计划一定能下发。
 */
public final class LightDirector {

    // 与 MainActivity.buildLedPage 的灯光模式顺序严格一致，改协议前先改这里。
    private static final String[] MODE_NAMES = {
            "关闭", "常亮", "全亮呼吸", "行/列渐进", "游龙", "波纹", "拨弦闪烁"
    };

    private static final int DEFAULT_MODE = 2;
    private static final int DEFAULT_AXIS = 0;
    private static final int DEFAULT_SPEED = 40;
    private static final int DEFAULT_BRIGHTNESS = 180;

    /** 解析后的灯效计划，字段已经夹紧到设备可接受范围。 */
    public static final class LightPlan {
        /** 灯光模式 0..6（见 MainActivity 的灯光模式列表）。 */
        public final int mode;
        /** 0=行方向，1=列方向。 */
        public final int axis;
        /** 流动速度 0..255。 */
        public final int speed;
        /** 整体亮度 0..255。 */
        public final int brightness;
        /** AI 给的一句中文理由，用于界面展示「为什么这么配」。 */
        public final String reason;

        public LightPlan(int mode, int axis, int speed, int brightness, String reason) {
            this.mode = mode;
            this.axis = axis;
            this.speed = speed;
            this.brightness = brightness;
            this.reason = reason == null ? "" : reason;
        }

        @Override
        public String toString() {
            return "LightPlan{mode=" + mode + ", axis=" + axis + ", speed=" + speed
                    + ", brightness=" + brightness + ", reason=" + reason + "}";
        }
    }

    /** 灯效建议回调。只在主线程触发一次。 */
    public interface Cb {
        void onSuccess(LightPlan plan);

        void onError(DeepSeekClient.AiError error);
    }

    private final DeepSeekClient client;

    public LightDirector(DeepSeekClient client) {
        this.client = client;
    }

    /**
     * 根据用户的一句话生成灯效建议。解析失败/模型输出不合法时回调中文错误。
     */
    public void suggest(String userWish, final Cb callback) {
        final Cb target = callback != null ? callback : new Cb() {
            @Override
            public void onSuccess(LightPlan plan) {
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
            }
        };
        final AtomicBoolean done = new AtomicBoolean(false);
        if (client == null) {
            deliverError(target, done, new DeepSeekClient.AiError(0, "not_ready",
                    "AI 客户端未初始化，请重新打开页面再试", false));
            return;
        }
        final String wish = userWish == null ? "" : userWish.trim();
        if (wish.isEmpty()) {
            deliverError(target, done, new DeepSeekClient.AiError(0, "invalid_input",
                    "先描述一下你想要的灯效氛围，例如「安静一点的呼吸灯」", false));
            return;
        }
        List<DeepSeekClient.ChatMessage> history = new ArrayList<DeepSeekClient.ChatMessage>();
        history.add(DeepSeekClient.ChatMessage.user(wish));
        // 普通 JSON 模式 + 默认快速（不思考）：选几档参数不需要长推理，速度更重要。
        client.chat(SYSTEM_PROMPT, history, true, 300, new DeepSeekClient.ChatCallback() {
            @Override
            public void onSuccess(String text) {
                LightPlan plan = parse(text);
                if (plan == null) {
                    deliverError(target, done, new DeepSeekClient.AiError(0, "parse_error",
                            "AI 返回的灯效参数看不懂，换个说法再试一次吧", true));
                    return;
                }
                if (done.compareAndSet(false, true)) {
                    target.onSuccess(plan);
                }
            }

            @Override
            public void onError(DeepSeekClient.AiError error) {
                deliverError(target, done, error);
            }
        });
    }

    /**
     * 解析模型输出为灯效计划。纯函数，可 JVM 单测：
     * 非法 JSON / 非对象 / 完全没有参数 → null；数值越界 → 夹紧到合法范围。
     */
    public static LightPlan parse(String json) {
        if (json == null) {
            return null;
        }
        String text = AiScoreAssistant.stripCodeFences(json);
        if (text.isEmpty()) {
            return null;
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, Object> map = MiniJson.asObject(parsed);
        if (map == null) {
            return null;
        }
        // 模型明确报错时视为没有可用计划。
        if (!MiniJson.text(map, "error", "").trim().isEmpty()) {
            return null;
        }
        // 至少要有一个参数键，否则说明模型只回了理由/废话，不能拿默认值糊弄用户。
        if (!map.containsKey("mode") && !map.containsKey("axis")
                && !map.containsKey("speed") && !map.containsKey("brightness")) {
            return null;
        }
        int mode = clamp(MiniJson.integer(map, "mode", DEFAULT_MODE), 0, 6);
        int axis = clamp(MiniJson.integer(map, "axis", DEFAULT_AXIS), 0, 1);
        int speed = clamp(MiniJson.integer(map, "speed", DEFAULT_SPEED), 0, 255);
        int brightness = clamp(MiniJson.integer(map, "brightness", DEFAULT_BRIGHTNESS), 0, 255);
        String reason = MiniJson.text(map, "reason", "").trim();
        if (reason.length() > 60) {
            reason = reason.substring(0, 60);
        }
        return new LightPlan(mode, axis, speed, brightness, reason);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static void deliverError(final Cb target, final AtomicBoolean done,
                                     final DeepSeekClient.AiError error) {
        if (done.compareAndSet(false, true)) {
            target.onError(error == null
                    ? new DeepSeekClient.AiError(0, "unknown", "生成灯效建议失败", false)
                    : error);
        }
    }

    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    private static String buildSystemPrompt() {
        StringBuilder builder = new StringBuilder(700);
        builder.append("你是尤克里里 App 的「灯效导演」。根据用户描述的画面/氛围，从固定参数里挑一套 LED 灯效。\n");
        builder.append("只输出一个 json 对象，不要 Markdown、不要解释。键固定为：\n");
        builder.append("{\"mode\":整数,\"axis\":整数,\"speed\":整数,\"brightness\":整数,\"reason\":\"不超过20字的中文理由\"}\n");
        builder.append("可选值：\n");
        for (int i = 0; i < MODE_NAMES.length; i++) {
            builder.append("mode ").append(i).append(" = ").append(MODE_NAMES[i]).append('\n');
        }
        builder.append("axis：0=行方向，1=列方向（呼吸/闪烁类模式方向影响不大，填 0 即可）\n");
        builder.append("speed：流动速度 0..255，慢 10~40，中 50~100，快 120~220\n");
        builder.append("brightness：整体亮度 0..255，柔和 60~140，明亮 150~220，全亮 240~255\n");
        builder.append("用户想要的气氛：");
        return builder.toString();
    }
}
