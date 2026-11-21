package com.ukulele.monitor.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 搭子的持久化 v2：最多 {@link #MAX_ASSISTANTS} 个「助手槽位」，每个槽位 = 一条人格 + 独立历史。
 *
 * <p>为什么改成多槽位：用户希望最多保留 4 个智能助手，并且可以随时切换着聊。把每个槽位的
 * 人格与历史分开存（`slot{i}_persona` / `slot{i}_history`），切换助手就只是换一个 active 指针，
 * 历史天然隔离，不会把 A 搭子的聊天记录串到 B 搭子身上。
 *
 * <p>旧版本只有单助手（键 `persona` / `history`）：{@link #init()} 会把它迁移到 slot 0 并删除旧键，
 * 旧 API（{@link #persona()}、{@link #savePersona(CompanionPersona)}、{@link #history()} 等）
 * 全部代理当前 active slot，所以 MainActivity 等旧调用方不用改。
 *
 * <p>空槽约定：{@link #persona(int)} 对非法/空槽返回 null，已占用槽一定返回非 null（人格 JSON
 * 损坏时回退 sunny 预设）；{@link #addAssistant(CompanionPersona)} 成功后会把新槽设为 active，
 * 调用方用 {@link #activeSlot()} 就能知道新助手在哪。
 *
 * <p>历史每槽最多 {@link #HISTORY_LIMIT} 条；所有 Android 读写都 try/catch（磁盘满、多进程等
 * 极端情况不该让聊天崩溃），读不到就回退默认值。
 */
public final class CompanionStore {

    /** 最多保留的 AI 助手数量。 */
    public static final int MAX_ASSISTANTS = 4;

    private static final String PREFS = "uk_ai_companion";
    private static final String KEY_ACTIVE_SLOT = "active_slot";
    private static final String KEY_SLOT_COUNT = "slot_count";
    /** v1 单助手时代的旧键：只在迁移时读，迁移后删除。 */
    private static final String KEY_LEGACY_PERSONA = "persona";
    private static final String KEY_LEGACY_HISTORY = "history";

    /** 每槽最多保留的历史消息条数（user/assistant 各算一条）。 */
    static final int HISTORY_LIMIT = 20;

    private final SharedPreferences prefs;
    /** 与「当前人格 ID」保持同步，让其他只认识 AiCredentials 的模块也能读到同一 ID；可为 null。 */
    private final AiCredentials credentials;
    private final Object lock = new Object();

    public CompanionStore(Context context) {
        this(prefsFrom(context), credentialsFrom(context));
    }

    /**
     * JVM 测试用构造器：直接注入 SharedPreferences（内存实现）与可空的凭据层，
     * 这样多助手 / 迁移 / 历史隔离等逻辑可以完全脱离 Android 运行时做单测。
     */
    CompanionStore(SharedPreferences prefs, AiCredentials credentials) {
        this.prefs = prefs;
        this.credentials = credentials;
        init();
    }

    // ------------------------------------------------------------ 多助手 API

    /** 已占用的助手数量（至少 1；正常情况在 1..MAX_ASSISTANTS 之间）。 */
    public int assistantCount() {
        synchronized (lock) {
            return Math.max(1, totalOccupiedLocked());
        }
    }

    /** 当前 active 槽位（始终指向一个已占用槽；数据损坏时回退到第一个已占用槽）。 */
    public int activeSlot() {
        synchronized (lock) {
            return activeSlotLocked();
        }
    }

    /** 切换 active 槽位；slot 非法或为空返回 false。成功后同步旧凭据层的人格 ID。 */
    public boolean setActiveSlot(int slot) {
        synchronized (lock) {
            if (!validSlot(slot) || !occupiedLocked(slot)) {
                return false;
            }
            writeInt(KEY_ACTIVE_SLOT, slot);
            syncCredentialsLocked();
            return true;
        }
    }

    /**
     * 读取某个槽位的人格。
     *
     * @return 已占用槽返回人格（JSON 损坏时回退 sunny 预设，保证非 null）；非法槽或空槽返回 null
     */
    public CompanionPersona persona(int slot) {
        if (!validSlot(slot)) {
            return null;
        }
        try {
            CompanionPersona stored = CompanionPersona.fromJson(getString(personaKey(slot), ""));
            if (stored != null) {
                return stored;
            }
            if (occupiedLocked(slot)) {
                // 人格 JSON 损坏但槽位标记为占用：回退预设，别让 UI 拿到 null 后崩掉
                return defaultPersonaLocked();
            }
            if (slot == 0 && totalOccupiedLocked() <= 0) {
                // 存储完全不可用的降级场景：旧 API persona() 至少给一个默认人格
                return defaultPersonaLocked();
            }
            return null;
        } catch (Throwable ignored) {
            return occupiedLocked(slot) ? defaultPersonaLocked() : null;
        }
    }

    /**
     * 保存某个槽位的人格；slot 非法 / persona 为 null 时忽略。
     * 对空槽调用会把该槽变成已占用（UI「复制当前助手再编辑」的场景可用）。
     */
    public void savePersona(int slot, CompanionPersona persona) {
        if (!validSlot(slot) || persona == null) {
            return;
        }
        synchronized (lock) {
            try {
                writePersonaLocked(slot, persona);
                writeInt(KEY_SLOT_COUNT, totalOccupiedLocked());
                if (slot == activeSlotLocked()) {
                    syncCredentialsLocked();
                }
            } catch (Throwable ignored) {
                // 存不进去也不影响本次会话继续使用内存中的人格
            }
        }
    }

    /**
     * 新建一个助手：放进第一个空槽，并把它设为 active。
     *
     * @return 成功 true（此后 {@link #activeSlot()} 即新槽位、{@link #persona(int)} 可读回保存对象）；
     *         persona 为 null 或已满 {@link #MAX_ASSISTANTS} 返回 false
     */
    public boolean addAssistant(CompanionPersona persona) {
        if (persona == null) {
            return false;
        }
        synchronized (lock) {
            try {
                if (totalOccupiedLocked() >= MAX_ASSISTANTS) {
                    return false;
                }
                int slot = firstEmptyLocked();
                if (slot < 0) {
                    return false;
                }
                writePersonaLocked(slot, persona.copy());
                writeHistoryLocked(slot, new ArrayList<String[]>());
                writeInt(KEY_ACTIVE_SLOT, slot);
                writeInt(KEY_SLOT_COUNT, totalOccupiedLocked());
                syncCredentialsLocked();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /**
     * 删除一个助手：至少保留 1 个；删除的是 active 时自动切到第一个剩余槽；被删槽人格和历史清空。
     *
     * @return 删除成功 true；slot 非法/为空或只剩最后一个助手时 false
     */
    public boolean deleteAssistant(int slot) {
        synchronized (lock) {
            if (!validSlot(slot) || !occupiedLocked(slot)) {
                return false;
            }
            if (totalOccupiedLocked() <= 1) {
                return false; // 至少保留一个搭子
            }
            try {
                removeKeys(personaKey(slot), historyKey(slot));
                if (getInt(KEY_ACTIVE_SLOT, 0) == slot) {
                    int next = firstOccupiedLocked();
                    writeInt(KEY_ACTIVE_SLOT, next < 0 ? 0 : next);
                }
                writeInt(KEY_SLOT_COUNT, totalOccupiedLocked());
                syncCredentialsLocked();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** 某个槽位是否已占用（有助手）。 */
    public boolean hasAssistant(int slot) {
        return validSlot(slot) && occupiedLocked(slot);
    }

    /** 全部已占用槽位（升序）；UI 需要遍历时可直接用。 */
    public int[] occupiedSlots() {
        synchronized (lock) {
            int[] buffer = new int[MAX_ASSISTANTS];
            int count = 0;
            for (int i = 0; i < MAX_ASSISTANTS; i++) {
                if (occupiedLocked(i)) {
                    buffer[count++] = i;
                }
            }
            int[] out = new int[count];
            System.arraycopy(buffer, 0, out, 0, count);
            return out;
        }
    }

    // ------------------------------------------------------------ 历史（按槽位）

    /** 某槽最近对话：role/content 二元组，最旧在前；非法槽或读取失败返回空列表。 */
    public List<String[]> history(int slot) {
        if (!validSlot(slot)) {
            return new ArrayList<>();
        }
        try {
            return decodeHistory(getString(historyKey(slot), ""));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** 追加一条对话到指定槽；槽为空/非法、内容为空时忽略；超过上限丢最旧。 */
    public synchronized void append(int slot, String role, String content) {
        if (!validSlot(slot) || content == null || content.trim().isEmpty() || !occupiedLocked(slot)) {
            return;
        }
        try {
            List<String[]> items = history(slot);
            items.add(new String[]{normalizeRole(role), content});
            items = trimHistory(items, HISTORY_LIMIT);
            writeHistoryLocked(slot, items);
        } catch (Throwable ignored) {
            // 静默失败：历史是增强体验，不是核心功能
        }
    }

    /** 清空某槽对话历史（人格保留）。 */
    public void clearHistory(int slot) {
        if (!validSlot(slot)) {
            return;
        }
        try {
            removeKeys(historyKey(slot));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 旧版兼容 API（代理 active slot）

    /** 旧 API：当前 active 助手的人格；永不返回 null。 */
    public CompanionPersona persona() {
        return persona(activeSlot());
    }

    /** 旧 API：保存当前 active 助手的人格。 */
    public void savePersona(CompanionPersona persona) {
        savePersona(activeSlot(), persona);
    }

    /** 旧 API：当前 active 助手的历史。 */
    public List<String[]> history() {
        return history(activeSlot());
    }

    /** 旧 API：追加到当前 active 助手的历史。 */
    public synchronized void append(String role, String content) {
        append(activeSlot(), role, content);
    }

    /** 旧 API：清空当前 active 助手的历史。 */
    public void clearHistory() {
        clearHistory(activeSlot());
    }

    // ------------------------------------------------------------ 初始化 / 迁移

    /** 首次访问：迁移 v1 单助手数据、保证 slot 0 有默认助手、修正 active 指针与计数。 */
    private void init() {
        synchronized (lock) {
            try {
                migrateLegacyLocked();
                if (totalOccupiedLocked() <= 0) {
                    CompanionPersona fallback = defaultPersonaLocked();
                    writePersonaLocked(0, fallback);
                    writeHistoryLocked(0, new ArrayList<String[]>());
                }
                int active = getInt(KEY_ACTIVE_SLOT, 0);
                if (!validSlot(active) || !occupiedLocked(active)) {
                    int first = firstOccupiedLocked();
                    active = first < 0 ? 0 : first;
                }
                // 始终写回规范化后的 active slot：首次使用/数据损坏后也能保证键存在且指向有效槽
                writeInt(KEY_ACTIVE_SLOT, active);
                writeInt(KEY_SLOT_COUNT, totalOccupiedLocked());
                syncCredentialsLocked();
            } catch (Throwable ignored) {
                // 初始化失败时所有读写都会走各自的兜底；聊天功能不应因此崩溃
            }
        }
    }

    /** 把旧版单助手键迁移到 slot 0，然后删掉旧键（只做一次）。 */
    private void migrateLegacyLocked() {
        String legacyPersona = getString(KEY_LEGACY_PERSONA, "");
        String legacyHistory = getString(KEY_LEGACY_HISTORY, "");
        if (legacyPersona.isEmpty() && legacyHistory.isEmpty()) {
            return;
        }
        if (!occupiedLocked(0)) {
            CompanionPersona persona = CompanionPersona.fromJson(legacyPersona);
            if (persona == null) {
                persona = defaultPersonaLocked();
            }
            writePersonaLocked(0, persona);
            writeHistoryLocked(0, decodeHistory(legacyHistory));
        }
        removeKeys(KEY_LEGACY_PERSONA, KEY_LEGACY_HISTORY);
    }

    // ------------------------------------------------------------ 编解码（包内可见，便于 JVM 单测）

    /** 把历史编码成 JSON 数组；非法输入返回 "[]"。 */
    static String encodeHistory(List<String[]> items) {
        List<Object> rows = new ArrayList<>();
        if (items != null) {
            for (String[] item : items) {
                if (item == null || item.length < 2 || item[1] == null) {
                    continue;
                }
                List<Object> row = new ArrayList<>(2);
                row.add(normalizeRole(item[0]));
                row.add(item[1]);
                rows.add(row);
            }
        }
        try {
            return MiniJson.stringify(rows);
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    /** 解析历史 JSON；任何不符合 [["role","content"],...] 结构的内容都忽略。 */
    static List<String[]> decodeHistory(String json) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof List)) {
                return out;
            }
            for (Object row : (List<?>) parsed) {
                if (!(row instanceof List)) {
                    continue;
                }
                List<?> pair = (List<?>) row;
                if (pair.size() < 2 || pair.get(1) == null) {
                    continue;
                }
                String content = String.valueOf(pair.get(1));
                if (content.trim().isEmpty()) {
                    continue;
                }
                String role = pair.get(0) == null ? "user" : String.valueOf(pair.get(0));
                out.add(new String[]{normalizeRole(role), content});
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        return out;
    }

    /** 只保留最后 max 条有效记录；返回新列表，不修改入参。 */
    static List<String[]> trimHistory(List<String[]> items, int max) {
        List<String[]> out = new ArrayList<>();
        if (items == null || max <= 0) {
            return out;
        }
        for (String[] item : items) {
            if (item == null || item.length < 2 || item[1] == null) {
                continue;
            }
            if (item[1].trim().isEmpty()) {
                continue;
            }
            out.add(item);
        }
        if (out.size() <= max) {
            return out;
        }
        return new ArrayList<>(out.subList(out.size() - max, out.size()));
    }

    // ------------------------------------------------------------ 内部

    private static boolean validSlot(int slot) {
        return slot >= 0 && slot < MAX_ASSISTANTS;
    }

    private static String personaKey(int slot) {
        return "slot" + slot + "_persona";
    }

    private static String historyKey(int slot) {
        return "slot" + slot + "_history";
    }

    private boolean occupiedLocked(int slot) {
        return !getString(personaKey(slot), "").isEmpty();
    }

    private int totalOccupiedLocked() {
        int count = 0;
        for (int i = 0; i < MAX_ASSISTANTS; i++) {
            if (occupiedLocked(i)) {
                count++;
            }
        }
        return count;
    }

    private int firstEmptyLocked() {
        for (int i = 0; i < MAX_ASSISTANTS; i++) {
            if (!occupiedLocked(i)) {
                return i;
            }
        }
        return -1;
    }

    private int firstOccupiedLocked() {
        for (int i = 0; i < MAX_ASSISTANTS; i++) {
            if (occupiedLocked(i)) {
                return i;
            }
        }
        return -1;
    }

    private int activeSlotLocked() {
        int active = getInt(KEY_ACTIVE_SLOT, 0);
        if (validSlot(active) && occupiedLocked(active)) {
            return active;
        }
        int first = firstOccupiedLocked();
        return first < 0 ? 0 : first;
    }

    /** 当前 active 人格的唯一 ID 同步到 AiCredentials，保持旧模块读到的是同一个搭子。 */
    private void syncCredentialsLocked() {
        if (credentials == null) {
            return;
        }
        try {
            CompanionPersona persona = persona(activeSlotLocked());
            if (persona != null && persona.id != null && !persona.id.isEmpty()) {
                credentials.setCompanionPersonaId(persona.id);
            }
        } catch (Throwable ignored) {
            // 同步失败只影响其他模块读取默认人格，不影响本存储
        }
    }

    private CompanionPersona defaultPersonaLocked() {
        String id = "";
        try {
            if (credentials != null) {
                id = credentials.companionPersonaId();
            }
        } catch (Throwable ignored) {
            id = "";
        }
        return CompanionPersona.preset(id);
    }

    private void writePersonaLocked(int slot, CompanionPersona persona) {
        putString(personaKey(slot), persona.toJson());
    }

    private void writeHistoryLocked(int slot, List<String[]> items) {
        putString(historyKey(slot), encodeHistory(items));
    }

    // ---- SharedPreferences 容错封装（prefs 可能为 null：Context 异常/测试注入失败） ----

    private String getString(String key, String def) {
        try {
            return prefs == null ? def : prefs.getString(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private int getInt(String key, int def) {
        try {
            return prefs == null ? def : prefs.getInt(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private void putString(String key, String value) {
        try {
            if (prefs != null) {
                prefs.edit().putString(key, value).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeInt(String key, int value) {
        try {
            if (prefs != null) {
                prefs.edit().putInt(key, value).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void removeKeys(String... keys) {
        try {
            if (prefs == null || keys == null) {
                return;
            }
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : keys) {
                if (key != null) {
                    editor.remove(key);
                }
            }
            editor.apply();
        } catch (Throwable ignored) {
        }
    }

    private static String normalizeRole(String role) {
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return "assistant".equals(r) ? "assistant" : "user";
    }

    private static SharedPreferences prefsFrom(Context context) {
        try {
            if (context == null) {
                return null;
            }
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AiCredentials credentialsFrom(Context context) {
        try {
            return context == null ? null : new AiCredentials(context);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
