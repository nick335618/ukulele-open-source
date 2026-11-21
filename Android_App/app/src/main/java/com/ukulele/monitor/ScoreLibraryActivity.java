package com.ukulele.monitor;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ukulele.monitor.ui.AppDialogs;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.SettingsStore;
import com.ukulele.monitor.ui.UiKit;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「我的曲库」独立页面。
 *
 * 为什么单独开一页：导入的曲谱原本和「练习曲库」挤在曲谱页的同一个标签下 ——
 * 导入越多，页面就越长，而且练习曲库（内置曲目）也被一起往下推。
 * 现在导入的曲谱集中在独立页面里管理（搜索 / 排序 / 管理菜单 / 回收站），
 * 曲谱页只保留入口，长度不再随导入数量增长。
 *
 * 本页只做展示与管理；选中曲谱后把 id 交回 MainActivity（它才持有播放状态与设备连接）。
 */
public final class ScoreLibraryActivity extends Activity {

    /** 结果：选中的曲谱 id。 */
    static final String EXTRA_SELECTED_ID = "selected_id";
    static final int RESULT_RECORD = RESULT_FIRST_USER + 1;
    private static final int PAGE_SIZE = 5;

    private UiKit kit;
    private AppTheme.Colors colors;
    private ScoreRepository repo;
    private float uiScale = 1f;

    private ScorePageList listContainer;
    private TextView primaryAction, previousPage, nextPage, pageNumber;
    private LinearLayout pager;
    private int pageIndex, pageCount;
    private TextView countLabel;
    private TextView sortLabel;
    private String query = "";
    private int sortMode = SORT_RECENT;
    private ScoreRepository.Category category = ScoreRepository.Category.ALL;
    private final ScoreRepository.Category[] categories = {
        ScoreRepository.Category.ALL, ScoreRepository.Category.MIDI,
        ScoreRepository.Category.DOCUMENT, ScoreRepository.Category.RECORDING
    };
    private final TextView[] categoryButtons = new TextView[categories.length];

    private static final int SORT_RECENT = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_KIND = 2;

    private static final int REQ_DOCUMENT = 41;
    private static final int REQ_EXPORT = 43;

    private String exportScoreId;

    /** 导出等文件 I/O 走后台线程，避免在主线程读写最大 32MB 的原件造成 ANR。 */
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        kit = UiKit.create(this);
        colors = AppTheme.colors();
        repo = new ScoreRepository(this);
        AppTheme.applyWindow(this);
        try {
            uiScale = ScoreDocumentActivity.fontScale(SettingsStore.store(this).textScale());
        } catch (Exception ignored) { uiScale = 1f; }
        if (state != null) {
            query = state.getString("query", "");
            sortMode = state.getInt("sort", SORT_RECENT);
            pageIndex = state.getInt("page", 0);
            int savedCategory = state.getInt("category", 0);
            if (savedCategory >= 0 && savedCategory < categories.length) category = categories[savedCategory];
        }
        setContentView(buildScreen());
        reload();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("query", query);
        out.putInt("sort", sortMode);
        out.putInt("page", pageIndex);
        out.putInt("category", category.ordinal());
    }

    // ------------------------------------------------------------------ layout

    private View buildScreen() {
        LinearLayout root = kit.column();
        root.setBackgroundColor(colors.bgRoot);

        // 顶栏：返回 + 标题
        LinearLayout header = kit.row();
        header.setPadding(kit.dp(12), kit.dp(12), kit.dp(18), kit.dp(8));
        TextView back = iconButton("‹");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setResult(RESULT_CANCELED); finish(); }
        });
        header.addView(back, new LinearLayout.LayoutParams(kit.dp(40), kit.dp(40)));
        LinearLayout heading = kit.column();
        heading.setPadding(kit.dp(8), 0, 0, 0);
        TextView title = bold("我的曲库", 20);
        TextView subtitle = plain("导入曲谱与演奏录奏", 12, colors.textSecondary);
        heading.addView(title);
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(this);
        LinearLayout body = kit.column();
        body.setPadding(kit.dp(20), kit.dp(4), kit.dp(20), kit.dp(24));

        // 搜索
        EditText search = new EditText(this);
        search.setHint("搜索曲谱名称");
        search.setHintTextColor(colors.textTertiary);
        search.setTextColor(colors.textPrimary);
        search.setTextSize(ts(14));
        search.setSingleLine(true);
        search.setText(query);
        search.setPadding(kit.dp(14), 0, kit.dp(14), 0);
        search.setBackground(kit.bg(colors.surface2, 14f));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { query = e.toString(); pageIndex = 0; reload(); }
        });
        body.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(46)));

        LinearLayout filters = kit.row();
        filters.setPadding(0, kit.dp(10), 0, 0);
        for (int i = 0; i < categories.length; i++) {
            ScoreRepository.Category type = categories[i];
            TextView button = outlineButton(type.label);
            button.setTextSize(ts(13));
            button.setMaxLines(1);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setMinHeight(kit.dp(42));
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, -2,
                    type == ScoreRepository.Category.DOCUMENT ? 1.35f : 1f);
            if (i > 0) slot.leftMargin = kit.dp(6);
            filters.addView(button, slot);
            categoryButtons[i] = button;
            button.setOnClickListener(v -> {
                category = type;
                pageIndex = 0;
                reload();
            });
        }
        body.addView(filters);

        // 排序 + 数量
        LinearLayout tools = kit.row();
        tools.setPadding(0, kit.dp(10), 0, kit.dp(4));
        countLabel = plain("", 12, colors.textSecondary);
        tools.addView(countLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sortLabel = plain("", 13, colors.accent);
        sortLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        sortLabel.setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));
        sortLabel.setBackground(kit.ripple(colors.surface2, 12f));
        sortLabel.setClickable(true);
        sortLabel.setFocusable(true);
        sortLabel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSort(); }
        });
        tools.addView(sortLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pager = kit.row();
        pager.setGravity(Gravity.CENTER_VERTICAL);
        previousPage = outlineButton("‹ 上一页");
        nextPage = outlineButton("下一页 ›");
        pageNumber = plain("", 13, colors.textSecondary);
        pageNumber.setGravity(Gravity.CENTER);
        for (TextView view : new TextView[]{previousPage, pageNumber, nextPage}) {
            view.setTextSize(ts(13));
            view.setMaxLines(1);
            pager.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        }
        previousPage.setOnClickListener(v -> changePage(-1));
        nextPage.setOnClickListener(v -> changePage(1));
        body.addView(pager);
        listContainer = new ScorePageList(this, this::changePage);
        listContainer.setPadding(0, kit.dp(8), 0, 0);
        body.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部操作
        LinearLayout actions = kit.row();
        actions.setPadding(0, kit.dp(16), 0, 0);
        primaryAction = primaryButton("导入曲谱");
        primaryAction.setOnClickListener(v -> {
            if (category == ScoreRepository.Category.RECORDING) {
                setResult(RESULT_RECORD);
                finish();
            } else pickFile();
        });
        TextView recycleButton = outlineButton("回收站");
        recycleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRecycleBin(); }
        });
        actions.addView(primaryAction, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(gap(kit.dp(10)), new LinearLayout.LayoutParams(kit.dp(10), 1));
        actions.addView(recycleButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroller.addView(body);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View gap(int width) { View v = new View(this); v.setMinimumWidth(width); return v; }

    // ------------------------------------------------------------------ list

    // ------------------------------------------------------------------ list
    private void changePage(int delta) {
        int target = pageIndex + delta;
        if (target < 0 || target >= pageCount) return;
        pageIndex = target;
        reload();
    }

    private void reload() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        List<ScoreRepository.Item> all = repo.list(false);
        List<ScoreRepository.Item> shown = new ArrayList<>();
        int categoryCount = 0;
        String term = query.trim().toLowerCase(Locale.ROOT);
        for (ScoreRepository.Item item : all) {
            if (item.title == null || (category != ScoreRepository.Category.ALL && category != item.category())) continue;
            categoryCount++;
            if (item.title.toLowerCase(Locale.ROOT).contains(term)) shown.add(item);
        }
        for (int i = 0; i < categories.length; i++) {
            boolean selected = categories[i] == category;
            TextView button = categoryButtons[i];
            button.setSelected(selected);
            button.setBackground(kit.ripple(selected ? colors.accentSoft : colors.surface2, 12f));
            button.setTextColor(selected ? colors.accent : colors.textPrimary);
        }
        Collections.sort(shown, comparator());
        boolean recordings = category == ScoreRepository.Category.RECORDING;
        primaryAction.setText(recordings ? "去录奏" : "导入曲谱");
        pageCount = (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        pageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
        pager.setVisibility(pageCount > 0 ? View.VISIBLE : View.GONE);
        pageNumber.setText((pageIndex + 1) + " / " + pageCount);
        pageNumber.setContentDescription("第 " + (pageIndex + 1) + " 页，共 " + pageCount + " 页");
        setPageButton(previousPage, pageIndex > 0);
        setPageButton(nextPage, pageIndex + 1 < pageCount);
        countLabel.setText(term.isEmpty() ? "共 " + categoryCount + " 首" :
                "匹配 " + shown.size() + " / " + categoryCount + " 首");
        if (sortLabel != null) {
            sortLabel.setText(sortMode == SORT_RECENT ? (recordings ? "最近录奏 ▾" : "最近添加 ▾") : sortMode == SORT_NAME ? "按名称 ▾" : "按类型 ▾");
        }
        if (shown.isEmpty()) {
            String message;
            if (categoryCount == 0 && category == ScoreRepository.Category.RECORDING) {
                message = "还没有录奏。可在演奏台录奏，停止后点「存入曲库」。";
            } else if (categoryCount == 0) {
                message = category == ScoreRepository.Category.ALL ? "还没有曲谱，可从下方导入。" :
                        "这个分类还没有曲谱，可从下方导入。";
            } else {
                message = "没有匹配「" + query.trim() + "」的曲谱。";
            }
            TextView empty = plain(message, 13, colors.textSecondary);
            empty.setLineSpacing(kit.dp(4), 1f);
            listContainer.addView(empty);
            return;
        }
        int from = pageIndex * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, shown.size()); i++)
            listContainer.addView(row(shown.get(i)));
    }

    private void setPageButton(TextView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .38f);
    }

    private Comparator<ScoreRepository.Item> comparator() {
        if (sortMode == SORT_NAME) {
            return new Comparator<ScoreRepository.Item>() {
                @Override public int compare(ScoreRepository.Item a, ScoreRepository.Item b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a.title == null ? "" : a.title, b.title == null ? "" : b.title);
                }
            };
        }
        if (sortMode == SORT_KIND) {
            return new Comparator<ScoreRepository.Item>() {
                @Override public int compare(ScoreRepository.Item a, ScoreRepository.Item b) {
                    String ka = kindOf(a), kb = kindOf(b);
                    int byKind = ka.compareTo(kb);
                    return byKind != 0 ? byKind : Long.compare(b.created, a.created);
                }
            };
        }
        return new Comparator<ScoreRepository.Item>() {
            @Override public int compare(ScoreRepository.Item a, ScoreRepository.Item b) {
                return Long.compare(b.created, a.created);
            }
        };
    }

    /** 单行：标题 + 元信息 + 右侧「⋮」管理入口。 */
    private View row(final ScoreRepository.Item item) {
        LinearLayout outer = kit.column();
        LinearLayout body = kit.row();
        body.setPadding(kit.dp(14), kit.dp(12), kit.dp(10), kit.dp(12));
        body.setBackground(kit.card());
        body.setClickable(true);
        body.setFocusable(true);

        LinearLayout texts = kit.column();
        TextView title = bold(item.title, 15);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        StringBuilder meta = new StringBuilder(kindOf(item));
        if (item.status != null && !item.status.isEmpty()) meta.append(" · ").append(item.status);
        if (item.difficulty != null && !item.difficulty.isEmpty() && !"未分类".equals(item.difficulty)) {
            meta.append(" · ").append(item.difficulty);
        }
        meta.append(" · ").append(date(item.created));
        if (item.outside > 0) meta.append(" · 音域不全");
        TextView sub = plain(meta.toString(), 12, item.outside > 0 ? colors.danger : colors.textSecondary);
        sub.setPadding(0, kit.dp(3), 0, 0);
        texts.addView(sub);
        body.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView menu = bold("⋮", 20);
        menu.setTextColor(colors.textTertiary);
        menu.setGravity(Gravity.CENTER);
        menu.setMinimumWidth(kit.dp(40));
        menu.setMinimumHeight(kit.dp(40));
        menu.setBackground(kit.ripple(colors.surface2, 12f));
        menu.setClickable(true);
        menu.setFocusable(true);
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { itemMenu(item); }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = kit.dp(8);
        body.addView(menu, mp);

        body.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { returnSelection(item); }
        });
        body.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { itemMenu(item); return true; }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = kit.dp(8);
        outer.addView(body, lp);
        return outer;
    }

    /** 选中并回到主界面（主界面负责载入时间轴与练习状态）。 */
    private void returnSelection(ScoreRepository.Item item) {
        Intent data = new Intent();
        data.putExtra(EXTRA_SELECTED_ID, item.id);
        setResult(RESULT_OK, data);
        finish();
    }

    private String kindOf(ScoreRepository.Item item) {
        String mime = item.mime == null ? "" : item.mime.toLowerCase(Locale.ROOT);
        if (item.category() == ScoreRepository.Category.RECORDING) return "演奏录奏";
        if (mime.contains("pdf") || item.title.toLowerCase(Locale.ROOT).endsWith(".pdf")) return "PDF";
        if (mime.startsWith("image/")) return "照片";
        if (item.category() == ScoreRepository.Category.MIDI) return "MIDI";
        if (item.category() == ScoreRepository.Category.DOCUMENT) return "照片";
        if (mime.contains("uks")) return "事件曲谱";
        if (mime.startsWith("text/")) return "文本曲谱";
        return "曲谱";
    }

    private String date(long millis) {
        if (millis <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    // ------------------------------------------------------------------ actions

    private void chooseSort() {
        List<AppDialogs.Item> items = new ArrayList<>();
        items.add(new AppDialogs.Item("最近导入", "新导入的排在前面", false, sortMode == SORT_RECENT ? "当前" : null));
        items.add(new AppDialogs.Item("按名称", "按曲谱名称排序", false, sortMode == SORT_NAME ? "当前" : null));
        items.add(new AppDialogs.Item("按类型", "按录奏、谱面和文件类型排列", false, sortMode == SORT_KIND ? "当前" : null));
        AppDialogs.list(this, "排序方式", items, new AppDialogs.OnPick() {
            @Override public void picked(int index) {
                sortMode = index;
                pageIndex = 0;
                reload();
            }
        }, null, null, null, null);
    }

    private void itemMenu(final ScoreRepository.Item item) {
        List<AppDialogs.Item> actions = new ArrayList<>();
        actions.add(new AppDialogs.Item("开始练习", "载入这首曲谱并回到练习页", false));
        actions.add(new AppDialogs.Item("查看原谱 / 校对", "查看原件、离线识谱与校对文稿", false));
        actions.add(new AppDialogs.Item("重命名", "修改曲谱显示名称", false));
        actions.add(new AppDialogs.Item("导出原谱", "把原件另存到手机存储", false));
        actions.add(new AppDialogs.Item("移到回收站", "原件与练习数据保留，可随时恢复", true));
        if(MidiOctaveEditor.supports(item))actions.add(new AppDialogs.Item("整体升降八度",MidiOctaveEditor.label(item.midiTranspose),false));
        AppDialogs.list(this, item.title, actions, new AppDialogs.OnPick() {
            @Override public void picked(int which) {
                if (which == 0) returnSelection(item);
                else if (which == 1) openDocument(item.id);
                else if (which == 2) rename(item);
                else if (which == 3) export(item);
                else if (which == 5) MidiOctaveEditor.show(ScoreLibraryActivity.this,repo,item.id,()->returnSelection(item));
                else trash(item);
            }
        }, null, null, null, null);
    }

    private void rename(final ScoreRepository.Item item) {
        AppDialogs.prompt(this, "重命名曲谱", item.title, new AppDialogs.OnText() {
            @Override public void onText(String value) {
                try { repo.rename(item.id, value); reload(); }
                catch (Exception e) { toast(e.getMessage() == null ? "重命名失败" : e.getMessage()); }
            }
        });
    }

    private void trash(final ScoreRepository.Item item) {
        AppDialogs.message(this, "移到回收站？",
                "“" + item.title + "”的原件与练习数据会保留，可随时从回收站恢复。",
                "移到回收站", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { repo.trash(item.id); reload(); toast("已移到回收站，可恢复"); }
                        catch (Exception e) { toast("删除失败"); }
                    }
                }, "取消", null, null, null);
    }

    private void export(final ScoreRepository.Item item) {
        exportScoreId = item.id;
        Intent out = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        out.setType(item.mime);
        out.putExtra(Intent.EXTRA_TITLE, item.title);
        out.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(out, REQ_EXPORT);
    }

    private void showRecycleBin() {
        final List<ScoreRepository.Item> items = repo.list(true);
        if (items.isEmpty()) {
            AppDialogs.message(this, "回收站",
                    "回收站是空的。\n\n移到回收站的曲谱会保留原件和练习数据，可随时恢复。\n\n"
                    + "曲谱存放在应用内部存储，卸载或清除应用数据会一并删除，重要原谱请用「导出原谱」另存一份。");
            return;
        }
        List<AppDialogs.Item> rows = new ArrayList<>();
        for (ScoreRepository.Item item : items) {
            rows.add(new AppDialogs.Item(item.title, kindOf(item) + " · 删除于 " + date(item.deleted), false));
        }
        AppDialogs.list(this, "回收站 · " + items.size() + " 首", rows, new AppDialogs.OnPick() {
            @Override public void picked(int index) { trashItemMenu(items.get(index)); }
        }, "关闭", null, "回收站设置", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { chooseRetention(); }
        });
    }

    private void trashItemMenu(final ScoreRepository.Item item) {
        List<AppDialogs.Item> actions = new ArrayList<>();
        actions.add(new AppDialogs.Item("恢复到曲库", "放回「我的曲库」，练习数据保留", false));
        actions.add(new AppDialogs.Item("永久删除", "删除原件和练习数据，无法恢复", true));
        AppDialogs.list(this, item.title, actions, new AppDialogs.OnPick() {
            @Override public void picked(int index) {
                if (index == 0) {
                    try { repo.restore(item.id); reload(); toast("已恢复到曲库"); }
                    catch (Exception e) { toast("恢复失败"); }
                } else {
                    AppDialogs.message(ScoreLibraryActivity.this, "永久删除原谱？",
                            "这将删除原件和练习数据，无法恢复。", "永久删除", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    try { repo.purge(item.id); toast("已永久删除"); }
                                    catch (Exception e) { toast("删除失败"); }
                                }
                            }, "取消", null, null, null);
                }
            }
        }, "返回", null, null, null);
    }

    private void chooseRetention() {
        final int[] dayValues = {0, 30, 90};
        final String[] labels = {"永久保留（默认）", "30 天", "90 天"};
        final String[] notes = {"仅手动永久删除，不会自动清理", "进入回收站超过 30 天自动永久删除", "进入回收站超过 90 天自动永久删除"};
        final int current = getSharedPreferences("score_library", 0).getInt("retention_days", 0);
        final int currentIndex = current == 30 ? 1 : current == 90 ? 2 : 0;
        List<AppDialogs.Item> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            rows.add(new AppDialogs.Item(labels[i], notes[i], false, i == currentIndex ? "当前" : null));
        }
        AppDialogs.list(this, "回收站保留期", rows, new AppDialogs.OnPick() {
            @Override public void picked(int index) {
                final int days = dayValues[index];
                AppDialogs.message(ScoreLibraryActivity.this, "确认保留策略",
                        days == 0 ? "回收站仅手动永久删除。" : "进入回收站超过 " + days + " 天的曲谱将永久删除，不可恢复。",
                        "启用", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                getSharedPreferences("score_library", 0).edit().putInt("retention_days", days).apply();
                                applyRetention();
                                reload();
                            }
                        }, "取消", null, null, null);
            }
        }, "返回", null, null, null);
    }

    /** 与 MainActivity 中的同名逻辑保持一致：按保留策略清理过期回收站条目。 */
    private void applyRetention() {
        int days = getSharedPreferences("score_library", 0).getInt("retention_days", 0);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        for (ScoreRepository.Item item : repo.list(true)) {
            if (item.deleted > 0 && item.deleted < cutoff) {
                try { repo.purge(item.id); } catch (Exception ignored) { }
            }
        }
    }

    private void openDocument(String id) {
        Intent i = new Intent(this, ScoreDocumentActivity.class);
        i.putExtra("id", id);
        startActivityForResult(i, REQ_DOCUMENT);
    }

    private void pickFile() {
        // 导入交给 MainActivity：MIDI/PDF/照片的解析与识别流程都在那里，
        // 这里只负责返回、让主界面去走既有流程，避免出现第二套解析逻辑。
        setResult(RESULT_FIRST_USER);
        finish();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DOCUMENT) {
            reload();
        } else if (req == REQ_EXPORT && res == RESULT_OK && data != null && data.getData() != null && exportScoreId != null) {
            writeExport(data.getData());
        }
    }

    /**
     * Exports the original file on a worker thread.
     *
     * The original can be up to {@link ScoreRepository#MAX_BYTES} (32 MB); doing that read and
     * write on the main thread risks an ANR on slow storage. MainActivity's equivalent export
     * already runs on a background executor, so this matches it.
     */
    private void writeExport(final android.net.Uri target) {
        final String id = exportScoreId;
        exportScoreId = null;
        worker.execute(new Runnable() {
            @Override public void run() {
                String message;
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                    byte[] bytes = ScoreRepository.readAtomic(repo.source(id));
                    out.write(bytes);
                    out.flush();
                    message = "已导出原谱";
                } catch (Exception e) {
                    message = "导出失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
                }
                final String result = message;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(result); }
                });
            }
        });
    }

    // ------------------------------------------------------------------ small helpers

    private float ts(float sizeSp) { return sizeSp * uiScale; }

    private TextView plain(String text, float sizeSp, int color) {
        TextView v = kit.text(text, ts(sizeSp), color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView bold(String text, float sizeSp) {
        TextView v = kit.textBold(text, ts(sizeSp), colors.textPrimary);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView primaryButton(String label) {
        TextView v = kit.textBold(label, ts(15), colors.bgRoot);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(kit.dp(48));
        v.setBackground(kit.ripple(colors.accent, 14f));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private TextView outlineButton(String label) {
        TextView v = kit.textBold(label, ts(15), colors.textPrimary);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(kit.dp(48));
        v.setBackground(kit.ripple(colors.surface2, 14f));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private TextView iconButton(String glyph) {
        TextView v = kit.textBold(glyph, ts(22), colors.textPrimary);
        v.setGravity(Gravity.CENTER);
        v.setBackground(kit.ripple(colors.surface2, 12f));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
