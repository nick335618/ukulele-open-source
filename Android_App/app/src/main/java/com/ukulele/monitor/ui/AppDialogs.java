package com.ukulele.monitor.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Dialogs that match the app's design system.
 *
 * Why this exists: every dialog used to be a bare {@code AlertDialog.Builder}, so the app
 * looked like two different products - a designed main screen and stock Android popups.
 * The stock list dialog also wrapped long score file names over several lines and produced
 * an unreadable wall of text, and {@code setMessage} silently truncated long bodies because
 * the message area does not scroll.
 *
 * Everything here is drawn with {@link UiKit} + {@link AppTheme}, so dialogs follow the
 * light/dark theme and the user's font-size setting like the rest of the app.
 *
 * This class only builds views; it contains no product logic.
 */
public final class AppDialogs {

    /** One tappable item in a list dialog. */
    public static final class Item {
        public final String title;
        public final String subtitle;
        public final boolean danger;
        /** Not null for "selected" list rows (e.g. the current practice speed). */
        public final String trailing;

        public Item(String title, String subtitle, boolean danger) { this(title, subtitle, danger, null); }

        public Item(String title, String subtitle, boolean danger, String trailing) {
            this.title = title;
            this.subtitle = subtitle;
            this.danger = danger;
            this.trailing = trailing;
        }
    }

    public interface OnPick { void picked(int index); }

    private AppDialogs() { }

    /** Theme-aware bridge for legacy dialogs; callers retain standard button/list semantics. */
    public static AlertDialog.Builder builder(android.content.Context context) {
        final android.content.Context themed = new android.view.ContextThemeWrapper(context,
                AppTheme.isLight() ? android.R.style.Theme_Material_Light_Dialog_Alert
                                   : android.R.style.Theme_Material_Dialog_Alert);
        return new AlertDialog.Builder(themed) {
            @Override public AlertDialog create() {
                AlertDialog dialog = super.create();
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(UiKit.create(context).bg(AppTheme.colors().surface, 20f));
                    window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        public void onViewDetachedFromWindow(View v) { }
                        public void onViewAttachedToWindow(View v) {
                            v.removeOnAttachStateChangeListener(this);
                            v.post(() -> {
                                if (!dialog.isShowing()) return;
                                WindowManager.LayoutParams lp = window.getAttributes();
                                int width = context.getResources().getDisplayMetrics().widthPixels;
                                lp.width = Math.min(UiKit.create(context).dp(560), (int)(width * .92f));
                                window.setAttributes(lp);
                                themeLegacyView(v);
                                for (int which : new int[]{-1,-2,-3}) {
                                    android.widget.Button b = dialog.getButton(which);
                                    if (b != null) { b.setTextColor(AppTheme.colors().accent); b.setAllCaps(false); }
                                }
                            });
                        }
                    });
                }
                return dialog;
            }
            @Override public AlertDialog show() { AlertDialog d = create(); d.show(); return d; }
        };
    }

    /** Theme the legacy recognition progress spinner as well as list/message dialogs. */
    public static android.app.ProgressDialog progress(Activity activity, String message) {
        android.content.Context context = new android.view.ContextThemeWrapper(activity,
            AppTheme.isLight() ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert);
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(context);
        dialog.setMessage(message); dialog.setCancelable(false); dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(UiKit.create(activity).bg(AppTheme.colors().surface, 20));
            themeLegacyView(dialog.getWindow().getDecorView());
        }
        return dialog;
    }

    private static void themeLegacyView(View view) {
        AppTheme.Colors c = AppTheme.colors();
        if (view instanceof android.widget.EditText) {
            android.widget.EditText e = (android.widget.EditText)view;
            e.setTextColor(c.textPrimary); e.setHintTextColor(c.textSecondary);
            e.setBackground(UiKit.create(e.getContext()).bg(c.surface2, 12));
        } else if (view instanceof TextView) {
            // Preserve app-authored labels; only tint the platform's legacy title/message/list rows.
            int id = view.getId();
            String resource = "";
            try { resource = view.getResources().getResourceName(id); } catch (Exception ignored) { }
            if (resource.startsWith("android:")) ((TextView)view).setTextColor(c.textPrimary);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) themeLegacyView(group.getChildAt(i));
        }
    }

    // ------------------------------------------------------------------ builders

    /**
     * Title + optional scrollable body + one or more buttons.
     *
     * The body is placed in a ScrollView: a long explanation (the guide dialog can run to
     * several hundred characters) is otherwise cut off with no way to read the rest.
     */
    public static AlertDialog message(Activity activity, String title, String body,
                                      String positive, DialogInterface.OnClickListener onPositive,
                                      String negative, DialogInterface.OnClickListener onNegative,
                                      String neutral, DialogInterface.OnClickListener onNeutral) {
        UiKit kit = UiKit.create(activity);
        AppTheme.Colors c = AppTheme.colors();
        float scale = textScale(activity);

        LinearLayout content = kit.column();
        content.setPadding(kit.dp(22), kit.dp(4), kit.dp(22), 0);
        if (body != null && !body.isEmpty()) {
            TextView text = kit.text(body, 14f * scale, c.textSecondary);
            text.setLineSpacing(kit.dp(5), 1f);
            final int bodyLimit = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .55f);
            ScrollView scroller = new ScrollView(activity) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int offered = View.MeasureSpec.getSize(heightSpec);
                    int cap = View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED
                        ? bodyLimit : Math.min(bodyLimit, offered);
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
                }
            };
            scroller.addView(text);
            // Cap the body height. Without a cap a very long body (the guide dialog can run to
            // several hundred characters) grows until it pushes the buttons off the bottom of
            // the screen, leaving the dialog unclosable. Scrolling inside the cap keeps all the
            // text reachable AND the buttons visible.
            content.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        AlertDialog dialog = create(activity, kit, c, title, content, scale);
        ButtonRow buttons = new ButtonRow(activity, kit, c, dialog, scale);
        if (neutral != null) buttons.add(neutral, onNeutral, false);
        if (negative != null) buttons.add(negative, onNegative, false);
        if (positive != null) buttons.add(positive, onPositive, true);
        if (!buttons.isEmpty()) content.addView(buttons.view());

        show(dialog);
        return dialog;
    }

    /** Message dialog with a single confirm button. */
    public static AlertDialog message(Activity activity, String title, String body) {
        return message(activity, title, body, "知道了", null, null, null, null, null);
    }

    /**
     * Scrollable list of items.
     *
     * Long file names wrap onto several lines in the stock list dialog; here a row shows the
     * name plus a secondary line, with the name allowed to take as many lines as it needs
     * instead of being clipped.
     */
    public static AlertDialog list(Activity activity, String title, List<Item> items,
                                   OnPick onPick, String negative, DialogInterface.OnClickListener onNegative,
                                   String neutral, DialogInterface.OnClickListener onNeutral) {
        UiKit kit = UiKit.create(activity);
        AppTheme.Colors c = AppTheme.colors();
        float scale = textScale(activity);

        LinearLayout content = kit.column();
        content.setPadding(kit.dp(14), kit.dp(4), kit.dp(14), 0);

        LinearLayout list = kit.column();
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(list);
        content.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = create(activity, kit, c, title, content, scale);
        // Rows are built after the dialog exists so each one can close it on tap.
        for (int i = 0; i < items.size(); i++) {
            list.addView(row(activity, kit, c, items.get(i), i, onPick, dialog, scale));
        }

        ButtonRow buttons = new ButtonRow(activity, kit, c, dialog, scale);
        if (neutral != null) buttons.add(neutral, onNeutral, false);
        if (negative != null) buttons.add(negative, onNegative, false);
        if (buttons.isEmpty()) {
            // A list must always have a way out other than the Back key. Callers that pass no
            // buttons (sort pickers, action menus) still get a Cancel row.
            buttons.add("取消", null, false);
        }
        content.addView(buttons.view());

        show(dialog);
        // A list wants as much room as it can get.
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.height = Math.min(kit.dp(126 + 76 * items.size()),
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.72f));
            window.setAttributes(lp);
        }
        return dialog;
    }

    /** Prompt with a single text field. */
    public static AlertDialog prompt(Activity activity, String title, String initial,
                                     final OnText onText) {
        UiKit kit = UiKit.create(activity);
        AppTheme.Colors c = AppTheme.colors();
        float scale = textScale(activity);

        LinearLayout content = kit.column();
        content.setPadding(kit.dp(22), kit.dp(6), kit.dp(22), 0);
        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setText(initial == null ? "" : initial);
        input.setSingleLine(true);
        input.setTextColor(c.textPrimary);
        input.setHintTextColor(c.textTertiary);
        input.setTextSize(15f * scale);
        input.setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12));
        input.setBackground(kit.bg(c.surface2, 14f));
        input.setSelection(input.getText().length());
        input.setMinHeight(kit.dp(48));
        content.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = create(activity, kit, c, title, content, scale);
        ButtonRow buttons = new ButtonRow(activity, kit, c, dialog, scale);
        buttons.add("取消", null, false);
        buttons.add("保存", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { onText.onText(input.getText().toString()); }
        }, true);
        content.addView(buttons.view());

        show(dialog);
        return dialog;
    }

    public interface OnText { void onText(String value); }

    /** One button of a {@link #form} dialog. */
    public static final class Action {
        public final String label;
        public final DialogInterface.OnClickListener listener;
        public final boolean primary;
        public Action(String label, DialogInterface.OnClickListener listener, boolean primary) {
            this.label = label; this.listener = listener; this.primary = primary;
        }
    }

    /**
     * Dialog wrapping a caller-supplied form.
     *
     * For screens that need real input controls (multi-line editors, several fields) the
     * caller still builds the inner view, but the frame around it - surface, rounded corners,
     * title typography, button row - comes from the design system instead of the platform
     * default, so those dialogs no longer look like a different app.
     */
    public static AlertDialog form(Activity activity, String title, View formContent,
                                   List<Action> actions, int horizontalPaddingDp) {
        UiKit kit = UiKit.create(activity);
        AppTheme.Colors c = AppTheme.colors();
        float scale = textScale(activity);

        LinearLayout content = kit.column();
        content.setPadding(kit.dp(horizontalPaddingDp), kit.dp(4), kit.dp(horizontalPaddingDp), 0);
        // The form takes all leftover space (weight 1) so the button row below it always stays
        // visible however tall the form is; scrolling is the form's own responsibility.
        int available = activity.getResources().getDisplayMetrics().widthPixels - kit.dp(44);
        formContent.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, available), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(kit.dp(280), View.MeasureSpec.AT_MOST));
        boolean compact = formContent.getMeasuredHeight() > 0 && formContent.getMeasuredHeight() < kit.dp(260);
        content.addView(formContent, compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = create(activity, kit, c, title, content, scale);
        ButtonRow buttons = new ButtonRow(activity, kit, c, dialog, scale);
        if (actions != null) {
            for (Action action : actions) buttons.add(action.label, action.listener, action.primary);
        }
        if (buttons.isEmpty()) buttons.add("取消", null, false);
        content.addView(buttons.view());

        show(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.height = compact ? ViewGroup.LayoutParams.WRAP_CONTENT :
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.90f);
            window.setAttributes(lp);
        }
        return dialog;
    }

    /** Themes an EditText created by a caller so it is legible in both themes. */
    public static void themeInput(android.widget.EditText input, float sizeSp, boolean multiLine) {
        UiKit kit = UiKit.create(input.getContext());
        AppTheme.Colors c = AppTheme.colors();
        if (sizeSp <= 0) sizeSp = 15f;
        input.setTextColor(c.textPrimary);
        input.setHintTextColor(c.textTertiary);
        input.setTextSize(sizeSp * textScale((Activity) input.getContext()));
        if (multiLine) input.setGravity(android.view.Gravity.TOP);
        input.setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10));
        input.setBackground(kit.bg(c.surface2, 12f));
    }

    // ------------------------------------------------------------------ internals

    /** A dialog title drawn with the design system instead of the platform's default. */
    private static View titleView(UiKit kit, AppTheme.Colors c, String title, float scale) {
        if (title == null || title.isEmpty()) return null;
        TextView view = kit.textBold(title, 20f * scale, c.textPrimary);
        view.setPadding(kit.dp(22), kit.dp(20), kit.dp(22), kit.dp(2));
        return view;
    }

    /**
     * Builds the dialog with its custom title already attached.
     *
     * The title must be supplied on the Builder, not via {@code dialog.setCustomTitle()}
     * after {@code create()}: the latter applies too late for the dialog to attach the
     * title area correctly, and can leave the title undrawn.
     */
    private static AlertDialog create(Activity activity, UiKit kit, AppTheme.Colors c,
                                      String title, View content, float scale) {
        AlertDialog.Builder builder = builder(activity).setView(content);
        View titleView = titleView(kit, c, title, scale);
        if (titleView != null) builder.setCustomTitle(titleView);
        return builder.create();
    }

    /**
     * Applies the user's in-app font-size setting.
     *
     * UiKit sizes text in SP, which only follows the *system* font scale; the app also has its
     * own 「界面字号」 setting (SettingsStore.textScale). Without this, dialogs would keep the
     * default size while the rest of the app grew.
     */
    private static float textScale(Activity activity) {
        try {
            int setting = SettingsStore.store(activity).textScale();
            if (setting == SettingsStore.TEXT_SCALE_SMALL) return 0.88f;
            if (setting == SettingsStore.TEXT_SCALE_LARGE) return 1.16f;
        } catch (Exception ignored) { }
        return 1f;
    }

    private static void show(AlertDialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        // Rounded, themed container instead of the square default.
        window.setBackgroundDrawable(UiKit.create(window.getContext()).bg(AppTheme.colors().surface, 18f));
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = (int) (window.getContext().getResources().getDisplayMetrics().widthPixels * 0.92f);
        window.setAttributes(lp);
    }

    /**
     * Left-aligned row: title (wraps freely), optional subtitle, optional trailing hint.
     *
     * Dismisses {@code owner} before handing control to the caller: the baseline
     * {@code AlertDialog.setItems} closed the list automatically, so callers never dismiss it
     * themselves and the old dialog would stay on screen underneath any dialog the callback
     * opens (the rename prompt used to float on top of the score menu).
     */
    private static View row(Activity activity, UiKit kit, AppTheme.Colors c,
                           final Item item, final int index, final OnPick onPick,
                           final AlertDialog owner, float scale) {
        LinearLayout outer = kit.row();
        outer.setPadding(kit.dp(10), kit.dp(14), kit.dp(10), kit.dp(14));
        outer.setBackground(kit.ripple(c.surface2, 12f));
        outer.setClickable(true);
        outer.setFocusable(true);

        LinearLayout texts = kit.column();
        TextView title = kit.textBold(item.title, 15f * scale, item.danger ? c.danger : c.textPrimary);
        // Multi-line on purpose: a long imported file name must stay readable.
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = kit.text(item.subtitle, 12f * scale, c.textSecondary);
            sub.setPadding(0, kit.dp(3), 0, 0);
            texts.addView(sub);
        }
        outer.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (item.trailing != null) {
            TextView hint = kit.textBold(item.trailing, 13f * scale, c.accent);
            hint.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.leftMargin = kit.dp(10);
            outer.addView(hint, hp);
        }

        outer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Close first, then act: the callback usually opens another dialog.
                if (owner != null && owner.isShowing()) owner.dismiss();
                if (onPick != null) onPick.picked(index);
            }
        });

        LinearLayout wrapper = kit.column();
        wrapper.addView(outer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView spacer = kit.text("", 4f * scale, c.textPrimary);
        wrapper.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(6)));
        return wrapper;
    }

    /** Horizontal button row; primary action highlighted, others plain text. */
    private static final class ButtonRow {
        private final LinearLayout row;
        private final UiKit kit;
        private final AppTheme.Colors c;
        private final AlertDialog dialog;
        private final float scale;

        ButtonRow(Activity activity, UiKit kit, AppTheme.Colors c, AlertDialog dialog, float scale) {
            this.kit = kit; this.c = c; this.dialog = dialog; this.scale = scale;
            this.row = kit.row();
            row.setGravity(Gravity.END);
            row.setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(12));
        }

        boolean isEmpty() { return row.getChildCount() == 0; }
        LinearLayout view() { return row; }

        void add(final String label, final DialogInterface.OnClickListener listener, final boolean primary) {
            TextView button = kit.textBold(label, 14f * scale, primary ? c.accent : c.textSecondary);
            button.setGravity(Gravity.CENTER);
            button.setPadding(kit.dp(8), kit.dp(12), kit.dp(8), kit.dp(12));
            button.setMinHeight(kit.dp(44));
            button.setMinWidth(0);
            button.setMaxLines(2);
            button.setAutoSizeTextTypeUniformWithConfiguration(10, Math.max(11, Math.round(14f * scale)), 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            button.setBackground(primary ? kit.ripple(c.accentDim, 14f) : kit.ripple(c.surface2, 14f));
            button.setClickable(true);
            button.setFocusable(true);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    if (listener != null) listener.onClick(dialog, 0);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = row.getChildCount() == 0 ? 0 : kit.dp(8);
            row.addView(button, lp);
        }
    }
}
