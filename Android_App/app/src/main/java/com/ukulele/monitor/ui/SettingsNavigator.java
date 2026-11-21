package com.ukulele.monitor.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.ukulele.monitor.R;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings index and persistent detail views, sharing the activity's live device bindings. */
public final class SettingsNavigator extends FrameLayout {
    public interface Listener { void onNavigate(String module); }
    private final UiKit kit;
    private final float scale;
    private final Listener listener;
    private final ScrollView home;
    private final LinearLayout index;
    private final Map<String, LinearLayout> groups = new LinkedHashMap<>();
    private final Map<String, View> details = new LinkedHashMap<>();
    private final Map<String, TextView> summaries = new LinkedHashMap<>();
    private String current = "";

    public SettingsNavigator(Context context, float scale, Listener listener) {
        super(context);
        this.kit = UiKit.create(context); this.scale = scale; this.listener = listener;
        setBackgroundColor(AppTheme.colors().bgRoot);
        index = column(); index.setPadding(dp(20), dp(4), dp(20), dp(24));
        home = scroll(index);
        addView(home, new FrameLayout.LayoutParams(-1, -1));
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private ScrollView scroll(View child) {
        ScrollView view = new ScrollView(getContext()); view.setFillViewport(true); view.setClipToPadding(false);
        view.addView(child, new ScrollView.LayoutParams(-1, -2)); return view;
    }
    private TextView label(String text, float size, int color, boolean bold) {
        return bold ? kit.textBold(text, size * scale, color) : kit.text(text, size * scale, color);
    }
    private ImageView icon(int resource, int color) {
        ImageView image = new ImageView(getContext());
        Drawable drawable = getResources().getDrawable(resource, getContext().getTheme()).mutate();
        drawable.setTint(color); image.setImageDrawable(drawable); return image;
    }

    public void addGroup(String key, String title) {
        TextView heading = kit.sectionHeader(title); heading.setTextSize(13 * scale);
        index.addView(heading);
        LinearLayout group = column(); group.setBackground(kit.card()); group.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8);
        index.addView(group, lp); groups.put(key, group);
    }

    public LinearLayout addModule(String groupKey, String id, String title, String summary,
                                  int iconResource, int color, String description,
                                  View illustration, String illustrationCaption) {
        LinearLayout group = groups.get(groupKey);
        if (group == null || details.containsKey(id)) throw new IllegalArgumentException("Invalid settings module: " + id);
        if (group.getChildCount() > 0) {
            View divider = new View(getContext()); divider.setBackgroundColor(AppTheme.colors().stroke);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            dp.leftMargin = dp(72); dp.rightMargin = dp(16); group.addView(divider, dp);
        }
        LinearLayout entry = new LinearLayout(getContext()); entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(16), dp(12), dp(14), dp(12)); entry.setMinimumHeight(dp(76));
        entry.setBackground(kit.ripple(AppTheme.colors().surface, 16));
        FrameLayout badge = new FrameLayout(getContext()); badge.setBackground(kit.bg(AppTheme.withAlpha(color, .12f), 12));
        FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        badge.addView(icon(iconResource, color), imageLp);
        entry.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = column(); texts.setPadding(dp(12), 0, dp(8), 0);
        texts.addView(label(title, 14, AppTheme.colors().textPrimary, true));
        TextView sub = label(summary, 11, AppTheme.colors().textSecondary, false);
        sub.setPadding(0, dp(4), 0, 0); sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(sub); summaries.put(id, sub);
        entry.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        entry.addView(icon(R.drawable.ic_chevron_right, AppTheme.colors().textTertiary), new LinearLayout.LayoutParams(dp(18), dp(18)));
        entry.setClickable(true); entry.setFocusable(true); entry.setContentDescription(title + "，" + summary);
        entry.setOnClickListener(v -> showModule(id, true)); group.addView(entry, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout page = column();
        LinearLayout toolbar = new LinearLayout(getContext()); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(4), dp(20), dp(8));
        ImageView back = icon(R.drawable.ic_arrow_back, AppTheme.colors().textPrimary);
        back.setPadding(dp(12), dp(12), dp(12), dp(12)); back.setBackground(kit.ripple(AppTheme.colors().bgRoot, 14));
        back.setContentDescription("返回设置"); back.setClickable(true); back.setFocusable(true); back.setOnClickListener(v -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView detailTitle = label(title, 18, AppTheme.colors().textPrimary, true);
        detailTitle.setPadding(dp(4), 0, 0, 0);
        toolbar.addView(detailTitle, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = column(); body.setPadding(dp(20), 0, dp(20), dp(24));
        TextView introduction = label(description, 12, AppTheme.colors().textSecondary, false);
        introduction.setPadding(0, 0, 0, dp(16)); body.addView(introduction);
        if (illustration != null) {
            LinearLayout picture = column(); picture.setBackground(kit.card()); picture.setPadding(dp(12), dp(12), dp(12), dp(12));
            picture.addView(illustration, new LinearLayout.LayoutParams(-1, dp(152)));
            TextView caption = label(illustrationCaption, 11, AppTheme.colors().textSecondary, false);
            caption.setPadding(dp(4), dp(8), dp(4), 0); picture.addView(caption);
            LinearLayout.LayoutParams imageCardLp = new LinearLayout.LayoutParams(-1, -2); imageCardLp.bottomMargin = dp(12);
            body.addView(picture, imageCardLp);
        }
        page.addView(scroll(body), new LinearLayout.LayoutParams(-1, 0, 1));
        details.put(id, page); return body;
    }

    public void setSummary(String id, String value) {
        TextView text = summaries.get(id);
        if (text != null && !android.text.TextUtils.equals(text.getText(), value)) {
            text.setText(value);
            LinearLayout labels = (LinearLayout) text.getParent();
            ((View) labels.getParent()).setContentDescription(((TextView) labels.getChildAt(0)).getText() + "，" + value);
        }
    }
    public boolean goBack() { if (current.isEmpty()) return false; showModule("", true); return true; }
    public String currentModule() { return current; }
    public void showModule(String id, boolean notify) {
        current = details.containsKey(id) ? id : "";
        removeAllViews(); addView(current.isEmpty() ? home : details.get(current), new FrameLayout.LayoutParams(-1, -1));
        if (notify && listener != null) listener.onNavigate(current);
    }
}
