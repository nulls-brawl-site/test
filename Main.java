package com.example.fullscreentexteditor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.HorizontalScrollView;
import android.widget.CheckBox;
import android.widget.SeekBar;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.BottomSheet.Builder;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class Main {
    /** Version code for Dex update management */
    public static final int VERSION_CODE = 1;

    private static final String TAG = "FullTextEditor";
    private static final String PREFS = "fullscreentexteditor_dex";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_THRESHOLD = "threshold";

    private static Main instance;

    // Reference to the Xposed hook so it can be removed on unload
    private XC_MethodHook.Unhook hookRef;
    private Class<?> chatActivityClass;

    // Track expand buttons per ChatActivity instance using weak references
    private final WeakHashMap<Object, ImageView> expandButtons = new WeakHashMap<>();

    private boolean isExpanded = false;
    private BottomSheet sheet;

    // Settings
    private boolean enabled;
    private int threshold;

    /** Singleton accessor */
    public static synchronized Main getInstance() {
        if (instance == null) {
            instance = new Main();
        }
        return instance;
    }

    private Main() {
        // Load settings
        SharedPreferences sp = prefs();
        enabled = sp.getBoolean(KEY_ENABLED, true);
        threshold = sp.getInt(KEY_THRESHOLD, 110);
        if (threshold < 1) threshold = 110;
    }

    /**
     * Entry point called by Python loader. This sets up method hooks via Xposed.
     */
    public void start() {
        if (!enabled) {
            Log.d(TAG, "Plugin disabled in settings; start() skipped");
            return;
        }
        try {
            chatActivityClass = Class.forName("org.telegram.ui.ChatActivity");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ChatActivity class not found", e);
            return;
        }
        hookCreateView();
        Log.d(TAG, "FullScreenTextEditor Dex started");
    }

    /**
     * Public settings entry point called by Python loader from plugin settings UI.
     * Python calls: instance.getClass().getMethod("showSettings").invoke(instance)
     */
    public void showSettings() {
        try {
            Activity ctx = getAnyActivity();
            if (ctx == null) {
                Log.e(TAG, "showSettings: no Activity");
                return;
            }

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(
                    AndroidUtilities.dp(16),
                    AndroidUtilities.dp(16),
                    AndroidUtilities.dp(16),
                    AndroidUtilities.dp(16)
            );
            root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            TextView title = new TextView(ctx);
            title.setText(LocaleController.getString("TextEditorSettings", R.string.TextEditorSettings));
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(title);

            // Enable checkbox
            CheckBox enableBox = new CheckBox(ctx);
            enableBox.setText("Enable Full-Screen Text Editor");
            enableBox.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            enableBox.setChecked(enabled);
            root.addView(enableBox);

            // Threshold label
            TextView thrLabel = new TextView(ctx);
            thrLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            thrLabel.setTextSize(14);
            thrLabel.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(4));
            root.addView(thrLabel);

            // Threshold slider
            SeekBar thr = new SeekBar(ctx);
            // Android SeekBar min requires API 26, so we emulate min=10 by offset
            final int MIN = 10;
            final int MAX = 500;
            int current = threshold;
            if (current < MIN) current = MIN;
            if (current > MAX) current = MAX;

            thr.setMax(MAX - MIN);
            thr.setProgress(current - MIN);
            root.addView(thr);

            // Set initial label
            thrLabel.setText("Show expand button after: " + current + " chars");

            // Apply changes live (in memory), persist on dismiss
            thr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int value = MIN + progress;
                    thrLabel.setText("Show expand button after: " + value + " chars");
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            BottomSheet bs = new BottomSheet.Builder(ctx, true)
                    .setCustomView(root)
                    .create();

            enableBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                enabled = isChecked;
                prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();

                // Turn on/off immediately
                if (enabled) {
                    // re-hook if needed
                    start();
                } else {
                    onUnload();
                    // Hide already injected buttons if any
                    for (ImageView b : expandButtons.values()) {
                        if (b != null) b.setVisibility(View.GONE);
                    }
                }
            });

            bs.setOnDismissListener(dialog -> {
                int newThreshold = MIN + thr.getProgress();
                threshold = newThreshold;
                prefs().edit().putInt(KEY_THRESHOLD, threshold).apply();
            });

            bs.show();
        } catch (Throwable t) {
            Log.e(TAG, "showSettings error", t);
        }
    }

    /**
     * Unhook any hooked methods and clear state. Called on plugin unload.
     */
    public void onUnload() {
        try {
            if (hookRef != null) {
                hookRef.unhook();
                hookRef = null;
            }
            expandButtons.clear();
            Log.d(TAG, "FullScreenTextEditor Dex unloaded");
        } catch (Throwable t) {
            Log.e(TAG, "Error during unload", t);
        }
    }

    /**
     * Utility to run tasks on the UI thread. This uses AndroidUtilities.runOnUIThread
     * if available, otherwise falls back to main handler.
     */
    private void runOnUiThread(Runnable task) {
        try {
            AndroidUtilities.runOnUIThread(task);
        } catch (Throwable ignored) {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(task);
        }
    }

    /**
     * Hook ChatActivity.createView(Context) using Xposed. When the method
     * completes, we inject our expand button into the text field container.
     */
    private void hookCreateView() {
        try {
            if (hookRef != null) {
                // already hooked
                return;
            }

            hookRef = XposedHelpers.findAndHookMethod(
                    chatActivityClass,
                    "createView",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!enabled) return;
                            Object fragment = param.thisObject;
                            runOnUiThread(() -> injectExpandButton(fragment));
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook createView", t);
        }
    }

    /**
     * Inject the expand button into the ChatActivity's enter view.
     *
     * @param fragment ChatActivity instance
     */
    private void injectExpandButton(Object fragment) {
        try {
            if (!enabled) return;

            // Avoid duplicating button if already injected for this fragment
            if (expandButtons.containsKey(fragment)) {
                ImageView existing = expandButtons.get(fragment);
                if (existing != null) return;
            }

            // Access chatActivityEnterView
            Object enterView = getPrivateField(fragment, "chatActivityEnterView");
            if (enterView == null) return;

            // getEditField() returns the EditText used for composing messages
            Method getEditFieldMethod = enterView.getClass().getMethod("getEditField");
            Object editFieldObj = getEditFieldMethod.invoke(enterView);
            if (!(editFieldObj instanceof EditText)) return;
            EditText editField = (EditText) editFieldObj;

            // Access textFieldContainer to add our button
            Object containerObj = getPrivateField(enterView, "textFieldContainer");
            if (!(containerObj instanceof ViewGroup)) return;
            ViewGroup textFieldContainer = (ViewGroup) containerObj;

            Context context = getParentActivity(fragment);
            if (context == null) {
                context = ApplicationLoader.applicationContext;
            }

            // Create expand button
            ImageView expandBtn = new ImageView(context);
            expandBtn.setImageResource(R.drawable.pip_video_expand);
            expandBtn.setColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons));
            expandBtn.setScaleType(ImageView.ScaleType.CENTER);

            // Initial visibility based on current text length
            int currentLen = editField.getText() != null ? editField.getText().length() : 0;
            expandBtn.setVisibility(currentLen >= threshold ? View.VISIBLE : View.GONE);

            int pad = AndroidUtilities.dp(8);
            expandBtn.setPadding(pad, pad, pad, pad);

            // Layout params for bottom right
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(48), AndroidUtilities.dp(48));
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.bottomMargin = AndroidUtilities.dp(48);
            params.rightMargin = AndroidUtilities.dp(4);

            textFieldContainer.addView(expandBtn, params);

            // Hook up click listener
            Object frag = fragment;
            expandBtn.setOnClickListener(v -> expandEditor(frag, enterView, editField));

            // Text watcher to show/hide button
            editField.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable editable) {
                    if (!enabled) {
                        expandBtn.setVisibility(View.GONE);
                        return;
                    }
                    int textLen = editable != null ? editable.length() : 0;
                    boolean show = textLen >= threshold;
                    expandBtn.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });

            // Track the button so we can clean it up
            expandButtons.put(fragment, expandBtn);

        } catch (Throwable t) {
            Log.e(TAG, "Failed to inject expand button", t);
        }
    }

    /**
     * Expand the editor into a full screen dialog.
     *
     * @param fragment ChatActivity instance
     * @param enterView The chat enter view object
     * @param editField The original EditText where messages are composed
     */
    private void expandEditor(Object fragment, Object enterView, EditText editField) {
        if (!enabled) return;
        if (isExpanded) return;
        isExpanded = true;
        try {
            Context ctx = getParentActivity(fragment);
            if (ctx == null) {
                isExpanded = false;
                return;
            }

            // Build the container layout
            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            // Toolbar
            LinearLayout toolbar = new LinearLayout(ctx);
            toolbar.setOrientation(LinearLayout.HORIZONTAL);
            int p16 = AndroidUtilities.dp(16);
            toolbar.setPadding(p16, p16, p16, AndroidUtilities.dp(8));
            toolbar.setGravity(Gravity.CENTER_VERTICAL);

            // Back button
            ImageView backBtn = new ImageView(ctx);
            backBtn.setImageResource(R.drawable.ic_ab_back_solar);
            backBtn.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            backBtn.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            toolbar.addView(backBtn, new LinearLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40)));

            // Title
            TextView title = new TextView(ctx);
            title.setText("Text Editor");
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setTextSize(18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            toolbar.addView(title, titleParams);

            // Done button
            ImageView doneBtn = new ImageView(ctx);
            doneBtn.setImageResource(R.drawable.ic_ab_done);
            doneBtn.setColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton));
            doneBtn.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            toolbar.addView(doneBtn, new LinearLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40)));

            container.addView(toolbar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Scroll area with a wrapper
            ScrollView scroll = new ScrollView(ctx);
            FrameLayout editWrapper = new FrameLayout(ctx);

            // Use EditTextCaption if available
            EditText fullEdit;
            try {
                Class<?> editCaptionCls = Class.forName("org.telegram.ui.Components.EditTextCaption");
                fullEdit = (EditText) editCaptionCls
                        .getConstructor(Context.class, android.util.AttributeSet.class)
                        .newInstance(ctx, null);
            } catch (Exception e) {
                fullEdit = new EditText(ctx);
            }

            final EditText fe = fullEdit;
            fe.setBackgroundColor(Color.TRANSPARENT);
            fe.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            fe.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            fe.setTextSize(16);
            fe.setGravity(Gravity.TOP | Gravity.LEFT);
            fe.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
            fe.setMinHeight(AndroidUtilities.dp(200));

            // Copy text from original edit field
            CharSequence originalText = editField.getText();
            if (originalText != null) {
                fe.setText(originalText);
                fe.setSelection(fe.getText().length());
            }

            FrameLayout.LayoutParams wrapperParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            wrapperParams.leftMargin = AndroidUtilities.dp(16);
            wrapperParams.rightMargin = AndroidUtilities.dp(16);

            editWrapper.addView(fe, wrapperParams);

            scroll.addView(editWrapper, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f);

            container.addView(scroll, scrollParams);

            // Format bar simplified: only bold and italic
            HorizontalScrollView formatScroll = new HorizontalScrollView(ctx);
            formatScroll.setHorizontalScrollBarEnabled(false);

            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setShape(GradientDrawable.RECTANGLE);
            pillBg.setCornerRadius(AndroidUtilities.dp(22));
            pillBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            formatScroll.setBackground(pillBg);

            LinearLayout formatBar = new LinearLayout(ctx);
            formatBar.setOrientation(LinearLayout.HORIZONTAL);
            formatBar.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
            formatBar.setGravity(Gravity.CENTER_VERTICAL);

            addFormatButton(ctx, formatBar, "B", "bold", fe);
            addFormatButton(ctx, formatBar, "I", "italic", fe);

            formatScroll.addView(formatBar);

            LinearLayout formatRow = new LinearLayout(ctx);
            formatRow.setOrientation(LinearLayout.HORIZONTAL);
            formatRow.setGravity(Gravity.CENTER);
            formatRow.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(16));

            formatRow.addView(formatScroll, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(120), AndroidUtilities.dp(44)));

            container.addView(formatRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Build bottom sheet
            Builder builder = new BottomSheet.Builder(ctx, true);
            builder.setCustomView(container);
            sheet = builder.create();
            sheet.setCanDismissWithSwipe(false);

            // Click listeners for back/done
            backBtn.setOnClickListener(v -> {
                sheet.dismiss();
                isExpanded = false;
            });

            doneBtn.setOnClickListener(v -> {
                CharSequence newText = fe.getText();
                if (newText != null) {
                    editField.setText(newText);
                    editField.setSelection(editField.getText().length());
                }
                sheet.dismiss();
                isExpanded = false;
            });

            sheet.setOnDismissListener(dialog -> {
                isExpanded = false;
                sheet = null;
            });

            sheet.show();
            fe.requestFocus();
            AndroidUtilities.showKeyboard(fe);

        } catch (Throwable t) {
            Log.e(TAG, "Error expanding editor", t);
            isExpanded = false;
        }
    }

    /**
     * Helper to add a simple format button.
     */
    private void addFormatButton(Context ctx, LinearLayout formatBar, String label, String format, EditText edit) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        btn.setTextSize(18);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        if ("bold".equals(format)) {
            btn.setTypeface(null, Typeface.BOLD);
        } else if ("italic".equals(format)) {
            btn.setTypeface(null, Typeface.ITALIC);
        }

        btn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        btn.setClickable(true);
        btn.setOnClickListener(v -> applySimpleFormat(format, edit));

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44));
        formatBar.addView(btn, btnParams);
    }

    /**
     * Apply simple bold or italic formatting to the selected text within edit.
     */
    private void applySimpleFormat(String format, EditText edit) {
        int start = edit.getSelectionStart();
        int end = edit.getSelectionEnd();
        if (start == end) return;

        CharSequence text = edit.getText();
        String selected = text.subSequence(start, end).toString();

        if ("bold".equals(format)) {
            edit.getText().replace(start, end, "**" + selected + "**");
        } else if ("italic".equals(format)) {
            edit.getText().replace(start, end, "_" + selected + "_");
        }
    }

    /**
     * Reflectively get a private field from an object. Returns null if not found.
     */
    private static Object getPrivateField(Object target, String fieldName) {
        try {
            Class<?> cls = target.getClass();
            while (cls != null) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {}
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting field " + fieldName, e);
        }
        return null;
    }

    /**
     * Attempt to call getParentActivity() on a ChatActivity to retrieve its Activity context.
     */
    private static Activity getParentActivity(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("getParentActivity");
            Object result = m.invoke(fragment);
            if (result instanceof Activity) {
                return (Activity) result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling getParentActivity", e);
        }
        return null;
    }

    /**
     * SharedPreferences for settings.
     */
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Try to get ANY Activity to show settings from plugin menu.
     * Uses AndroidUtilities.findActivity if present, fallback to parent activity of last known fragments is not available here.
     */
    private static Activity getAnyActivity() {
        try {
            // Many Telegram forks include AndroidUtilities.findActivity(Context)
            return AndroidUtilities.findActivity(ApplicationLoader.applicationContext);
        } catch (Throwable t) {
            // If not available, settings won't open unless called while a ChatActivity is active
            return null;
        }
    }
}