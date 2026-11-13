// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Original Home on Fire presentation backed by the AFTA Usage Access controls. */
@SuppressWarnings("deprecation")
public final class AftaSettingsActivity extends Activity {
    private static final int ROW_PAD_V = 10;
    private Prefs prefs;
    private TextView targetName, targetPlaceholder, launchButton, tip, monitorState, detailedState;
    private ImageView targetIcon;
    private View targetChip, infoButton, homeRow;
    private String displayedTarget;
    private AlertDialog advancedDialog, detailDialog;
    private final List<ToggleBinding> toggles = new ArrayList<ToggleBinding>();
    private final List<View> mainRows = new ArrayList<View>();
    private final Handler refreshHandler = new Handler();
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); refreshHandler.postDelayed(this, 1000); }
    };

    private interface BooleanSetting {
        boolean get();
        void set(boolean value);
    }

    private static final class ToggleBinding {
        final LinearLayout row;
        final Switch indicator;
        final BooleanSetting setting;
        final CharSequence label;
        ToggleBinding(LinearLayout row, Switch indicator, BooleanSetting setting, CharSequence label) {
            this.row = row;
            this.indicator = indicator;
            this.setting = setting;
            this.label = label;
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        if (!Backend.isAfta()) { finish(); return; }
        if (prefs.getTargetPackage().isEmpty()
                && TargetLauncher.resolve(this, AmazonCleanupPolicy.COUCHY_PACKAGE, false) != null) {
            prefs.setTargetPackage(AmazonCleanupPolicy.COUCHY_PACKAGE);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Colors.SETTINGS_BG);
        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(32), dp(20), dp(32), dp(24));
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(-1, -2);
        targetParams.bottomMargin = dp(16);
        content.addView(buildTargetRow(), targetParams);

        ToggleBinding monitor = addToggle(content, R.id.afta_monitor, R.string.afta_monitor_label,
                R.string.afta_monitor_tip, new BooleanSetting() {
                    @Override public boolean get() { return prefs.isMonitorEnabled(); }
                    @Override public void set(boolean value) {
                        if (value) prefs.setMonitorEnabled(true);
                        else stopMonitor();
                    }
                });
        monitorState = label("", 15);
        monitor.row.addView(monitorState);
        mainRows.add(monitor.row);
        addHomeControls(content);
        mainRows.add(addToggle(content, R.id.afta_boot, R.string.switch_boot,
                R.string.afta_boot_tip, new BooleanSetting() {
                    @Override public boolean get() { return prefs.getLaunchOnBoot(); }
                    @Override public void set(boolean value) { prefs.setLaunchOnBoot(value); }
                }).row);
        addVerboseControls(content);
        mainRows.add(addAction(content, R.id.afta_advanced, R.string.afta_advanced_label,
                R.string.afta_advanced_tip, new Runnable() {
                    @Override public void run() { showAdvanced(); }
                }));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildTipBox());
        setContentView(root);
        wireMainFocus();
        refresh();
        targetChip.requestFocus();
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Colors.BRAND);
        bar.setPadding(dp(24), dp(12), dp(24), dp(12));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoParams.rightMargin = dp(12);
        bar.addView(logo, logoParams);
        TextView title = label(getString(R.string.afta_screen_title), 26);
        title.setTextColor(Colors.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        infoButton = roundButton(R.id.afta_info, R.string.info_button_glyph,
                R.string.info_button_desc, R.string.info_button_tip, 20, new Runnable() {
                    @Override public void run() { InfoDialog.show(AftaSettingsActivity.this); }
                });
        bar.addView(infoButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    private LinearLayout buildTargetRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout picker = new LinearLayout(this);
        picker.setId(R.id.afta_target);
        picker.setOrientation(LinearLayout.HORIZONTAL);
        picker.setGravity(Gravity.CENTER_VERTICAL);
        picker.setPadding(dp(12), dp(6), dp(12), dp(6));
        picker.setBackground(getDrawable(R.drawable.target_chip_bg));
        picker.setFocusable(true);
        picker.setClickable(true);
        picker.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(AftaSettingsActivity.this, TargetPickerActivity.class));
            }
        });
        picker.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { openTargetInfo(); return true; }
        });
        picker.setNextFocusLeftId(R.id.afta_target);
        attachTip(picker, R.string.target_picker_tip);
        targetChip = picker;
        targetIcon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconParams.rightMargin = dp(12);
        picker.addView(targetIcon, iconParams);
        targetPlaceholder = label(getString(R.string.target_picker_emoji), 22);
        targetPlaceholder.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams placeholderParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        placeholderParams.rightMargin = dp(12);
        picker.addView(targetPlaceholder, placeholderParams);
        targetName = label("", 18);
        targetName.setTextColor(Colors.WHITE);
        targetName.setSingleLine(true);
        targetName.setEllipsize(TextUtils.TruncateAt.END);
        picker.addView(targetName, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(picker, new LinearLayout.LayoutParams(0, -2, 1f));
        launchButton = roundButton(R.id.afta_launch_target, R.string.launch_button_glyph,
                R.string.launch_button_desc, R.string.launch_button_tip, 18, new Runnable() {
                    @Override public void run() { open(false); }
                });
        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        launchParams.leftMargin = dp(12);
        row.addView(launchButton, launchParams);
        return row;
    }

    private TextView roundButton(int id, int glyph, int description, int help, int size,
                                 final Runnable action) {
        TextView button = label(getString(glyph), size);
        button.setId(id);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(getResources().getColorStateList(R.color.info_button_text));
        button.setBackground(getDrawable(R.drawable.info_button_bg));
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription(getString(description));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        attachTip(button, help);
        return button;
    }

    private LinearLayout controlRow(int id) {
        LinearLayout row = new LinearLayout(this);
        row.setId(id);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(ROW_PAD_V), dp(16), dp(ROW_PAD_V));
        row.setBackground(getDrawable(R.drawable.row_focus_bg));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int key, KeyEvent event) {
                // Single-control rows should not jump diagonally on LEFT/RIGHT.
                return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
        });
        return row;
    }

    private LinearLayout horizontalGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        return group;
    }

    private void addHomeControls(LinearLayout parent) {
        LinearLayout group = horizontalGroup();
        ToggleBinding home = addToggle(group, R.id.afta_home, R.string.switch_hijack,
                R.string.afta_home_tip, new BooleanSetting() {
                    @Override public boolean get() { return prefs.isHijackEnabled(); }
                    @Override public void set(boolean value) { prefs.setHijackEnabled(value); }
                });
        home.row.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        homeRow = home.row;
        TextView caption = label(getString(R.string.escape_label), 15);
        group.addView(caption);
        ToggleBinding longPress = addToggle(group, R.id.afta_escape_long,
                R.string.escape_long_label, R.string.afta_escape_long_tip, new BooleanSetting() {
                    @Override public boolean get() { return prefs.isEscapeLongPress(); }
                    @Override public void set(boolean value) { prefs.setEscapeLongPress(value); }
                });
        compactPair(longPress.row);
        parent.addView(group);
        mainRows.add(home.row);
    }

    private void compactPair(LinearLayout pair) {
        pair.setPadding(dp(8), dp(4), dp(8), dp(4));
        ((TextView) pair.getChildAt(1)).setTextSize(15);
        pair.getChildAt(1).setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        pair.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    }

    private void addVerboseControls(LinearLayout parent) {
        LinearLayout group = horizontalGroup();
        ToggleBinding verbose = addToggle(group, R.id.afta_verbose, R.string.switch_verbose,
                R.string.afta_verbose_tip, new BooleanSetting() {
                    @Override public boolean get() { return prefs.isVerboseLogging(); }
                    @Override public void set(boolean value) { prefs.setVerboseLogging(value); }
                });
        verbose.row.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView log = new ImageView(this);
        log.setId(R.id.afta_log_button);
        log.setImageResource(R.drawable.ic_log);
        log.setImageTintList(getResources().getColorStateList(R.color.info_button_text));
        log.setBackground(getDrawable(R.drawable.info_button_bg));
        log.setPadding(dp(12), dp(12), dp(12), dp(12));
        log.setFocusable(true);
        log.setClickable(true);
        log.setContentDescription(getString(R.string.log_button_desc));
        log.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(AftaSettingsActivity.this, LogViewerActivity.class));
            }
        });
        attachTip(log, R.string.log_button_tip);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        logParams.leftMargin = dp(12);
        group.addView(log, logParams);
        parent.addView(group);
        mainRows.add(verbose.row);
    }

    private ToggleBinding addToggle(LinearLayout parent, int id, int text, int help,
                                     final BooleanSetting setting) {
        final LinearLayout row = controlRow(id);
        Switch indicator = new Switch(this);
        indicator.setFocusable(false);
        indicator.setClickable(false);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
        switchParams.rightMargin = dp(16);
        row.addView(indicator, switchParams);
        CharSequence caption = KeyBadges.wrap(this, getString(text));
        row.addView(label(caption, 18), new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!row.isEnabled()) return;
                if (row.getId() == R.id.afta_logging && prefs.isVerboseLogging()) {
                    toast(getString(R.string.afta_logging_needs_verbose_off));
                    return;
                }
                setting.set(!setting.get());
                changed();
            }
        });
        attachTip(row, help);
        parent.addView(row);
        ToggleBinding binding = new ToggleBinding(row, indicator, setting, caption);
        toggles.add(binding);
        return binding;
    }

    private LinearLayout addAction(LinearLayout parent, int id, int text, int help,
                                    final Runnable action) {
        LinearLayout row = controlRow(id);
        TextView marker = label("›", 24);
        marker.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(48), dp(28));
        markerParams.rightMargin = dp(16);
        row.addView(marker, markerParams);
        row.addView(label(getString(text), 18), new LinearLayout.LayoutParams(0, -2, 1f));
        row.setContentDescription(getString(text));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        attachTip(row, help);
        parent.addView(row);
        return row;
    }

    private TextView label(CharSequence value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Colors.NEUTRAL);
        return view;
    }

    private LinearLayout buildTipBox() {
        LinearLayout box = new LinearLayout(this);
        box.setBackgroundColor(Colors.TIP_BG);
        box.setPadding(dp(24), dp(12), dp(24), dp(12));
        tip = label("", 16);
        tip.setId(R.id.afta_tip);
        box.addView(tip, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void attachTip(View view, int text) {
        final CharSequence help = KeyBadges.wrap(this, getString(text));
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                if (focused && tip != null) tip.setText(help);
            }
        });
    }

    private void wireMainFocus() {
        infoButton.setNextFocusDownId(R.id.afta_target);
        infoButton.setNextFocusLeftId(R.id.afta_target);
        targetChip.setNextFocusUpId(R.id.afta_info);
        targetChip.setNextFocusRightId(R.id.afta_launch_target);
        targetChip.setNextFocusDownId(mainRows.get(0).getId());
        launchButton.setNextFocusUpId(R.id.afta_info);
        launchButton.setNextFocusLeftId(R.id.afta_target);
        launchButton.setNextFocusDownId(mainRows.get(0).getId());
        launchButton.setNextFocusRightId(R.id.afta_launch_target);
        for (int index = 0; index < mainRows.size(); index++) {
            View row = mainRows.get(index);
            row.setNextFocusUpId(index == 0 ? R.id.afta_target : mainRows.get(index - 1).getId());
            row.setNextFocusDownId(index + 1 < mainRows.size() ? mainRows.get(index + 1).getId() : row.getId());
        }
        wireHorizontal(new int[] { R.id.afta_home, R.id.afta_escape_long },
                R.id.afta_monitor, R.id.afta_boot);
        wireHorizontal(new int[] { R.id.afta_verbose, R.id.afta_log_button },
                R.id.afta_boot, R.id.afta_advanced);
    }

    private void wireHorizontal(int[] ids, int up, int down) {
        for (int index = 0; index < ids.length; index++) {
            View control = findViewById(ids[index]);
            control.setOnKeyListener(null);
            control.setNextFocusLeftId(ids[index == 0 ? 0 : index - 1]);
            control.setNextFocusRightId(ids[index + 1 < ids.length ? index + 1 : index]);
            control.setNextFocusUpId(up);
            control.setNextFocusDownId(down);
        }
    }

    private void showAdvanced() {
        if (advancedDialog != null) return;
        final int firstAdvancedBinding = toggles.size();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        addToggle(content, R.id.afta_mode, R.string.afta_mode_label, R.string.afta_mode_tip,
                new BooleanSetting() {
                    @Override public boolean get() { return !prefs.isDiagnosticOnly(); }
                    @Override public void set(boolean value) {
                        if (value) confirmAutomation();
                        else prefs.setDiagnosticOnly(true);
                    }
                });
        addToggle(content, R.id.afta_cover, R.string.afta_cover_label, R.string.afta_cover_tip,
                new BooleanSetting() {
                    @Override public boolean get() { return prefs.isHomeCoverEnabled(); }
                    @Override public void set(boolean value) { prefs.setHomeCoverEnabled(value); }
                });
        addToggle(content, R.id.afta_wake, R.string.afta_wake_label, R.string.afta_wake_tip,
                new BooleanSetting() {
                    @Override public boolean get() { return prefs.getLaunchOnWake(); }
                    @Override public void set(boolean value) { prefs.setLaunchOnWake(value); }
                });
        addToggle(content, R.id.afta_logging, R.string.afta_logging_label, R.string.afta_logging_tip,
                new BooleanSetting() {
                    @Override public boolean get() { return prefs.isDiagnosticLogging(); }
                    @Override public void set(boolean value) { prefs.setDiagnosticLogging(value); }
                });
        addAction(content, R.id.afta_resume, R.string.afta_resume_label, R.string.afta_resume_tip,
                new Runnable() {
                    @Override public void run() {
                        prefs.setMonitorEnabled(true);
                        UsageMonitorService.start(AftaSettingsActivity.this, UsageMonitorService.RESUME);
                        refresh();
                    }
                });
        addAction(content, R.id.afta_open_amazon, R.string.afta_open_amazon_label,
                R.string.afta_open_amazon_label, new Runnable() {
                    @Override public void run() { closeAdvanced(); open(true); }
                });
        addAction(content, R.id.afta_stop, R.string.afta_stop_label, R.string.afta_stop_label,
                new Runnable() {
                    @Override public void run() { stopMonitor(); refresh(); }
                });
        addAction(content, R.id.afta_status, R.string.afta_status_label, R.string.afta_status_label,
                new Runnable() {
                    @Override public void run() { closeAdvanced(); showStatus(); }
                });
        addAction(content, R.id.afta_diagnostics, R.string.afta_diagnostics_label,
                R.string.afta_diagnostics_label, new Runnable() {
                    @Override public void run() {
                        closeAdvanced();
                        startActivity(new Intent(AftaSettingsActivity.this, LogViewerActivity.class));
                    }
                });
        addAction(content, R.id.afta_export, R.string.afta_export_label, R.string.afta_export_label,
                new Runnable() {
                    @Override public void run() { closeAdvanced(); exportDiagnostics(); }
                });
        addAction(content, R.id.afta_permissions, R.string.afta_permissions_label,
                R.string.afta_permissions_label, new Runnable() {
                    @Override public void run() { closeAdvanced(); showPermissionHelp(); }
                });
        advancedDialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.afta_advanced_label).setView(dialogScroll(content))
                .setPositiveButton(R.string.afta_close, null).create();
        advancedDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                while (toggles.size() > firstAdvancedBinding) toggles.remove(toggles.size() - 1);
                advancedDialog = null;
                refresh();
            }
        });
        advancedDialog.show();
        sizeDialog(advancedDialog);
        refresh();
        content.getChildAt(0).requestFocus();
    }

    private View dialogScroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.addView(content);
        FrameLayout holder = new FrameLayout(this);
        int height = (int) (getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        holder.addView(scroll, new FrameLayout.LayoutParams(-1, dp(Math.max(160, Math.min(320, height - 180)))));
        return holder;
    }

    private void sizeDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.min(dp(720), getResources().getDisplayMetrics().widthPixels - dp(48)), -2);
        }
    }

    private void closeAdvanced() { if (advancedDialog != null) advancedDialog.dismiss(); }

    private void confirmAutomation() {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("Enable experimental automation?")
                .setMessage("Home is inferred from launcher arrivals, including Back. "
                        + "Long-press escape is inferred from the HUD. Use Stop monitor to stop automatic actions.")
                .setNegativeButton("Keep diagnostics", null)
                .setPositiveButton("Enable", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.setDiagnosticOnly(false);
                        prefs.setMonitorEnabled(true);
                        UsageMonitorService.start(AftaSettingsActivity.this, UsageMonitorService.RESUME);
                        refresh();
                    }
                }).show();
    }

    private void showStatus() {
        detailedState = label("", 15);
        detailedState.setPadding(dp(16), dp(8), dp(16), dp(8));
        detailDialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.afta_status_label).setView(dialogScroll(detailedState))
                .setPositiveButton(R.string.afta_close, null).create();
        detailDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) { detailedState = null; detailDialog = null; }
        });
        detailDialog.show();
        sizeDialog(detailDialog);
        refresh();
    }

    private void exportDiagnostics() {
        try {
            String path = DiagnosticLog.export(this).getAbsolutePath();
            new AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("Diagnostic snapshot")
                    .setMessage("Copy with adb pull:\n" + path).setPositiveButton("OK", null).show();
        } catch (java.io.IOException ex) { toast("Export failed: " + ex.getMessage()); }
    }

    private void showPermissionHelp() {
        TextView help = label("Usage Access command:\nadb -s DEVICE shell appops set "
                + getPackageName() + " GET_USAGE_STATS allow\n\nLoading cover permission command:\n"
                + "adb -s DEVICE shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n\n"
                + "Accessibility on this AFTA firmware rejects ordinary app certificates. "
                + "WRITE_SECURE_SETTINGS does not remove that restriction.\n\n"
                + "Unavailable in Usage Access mode: raw Home/Menu/Back remapping, double-Home and Home-tab focus detection. "
                + "Amazon remains the system launcher. Repeated Home while already on Amazon may produce no event.", 15);
        help.setPadding(dp(16), dp(8), dp(16), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.afta_permissions_label).setView(dialogScroll(help))
                .setPositiveButton(R.string.afta_close, null).create();
        dialog.show();
        sizeDialog(dialog);
    }

    private void openTargetInfo() {
        String selected = prefs.getTargetPackage();
        if (selected.isEmpty()) { toast(getString(R.string.toast_no_target)); return; }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", selected, null)));
        } catch (RuntimeException ex) { toast(getString(R.string.toast_settings_unavailable)); }
    }

    private void changed() {
        if (prefs.isMonitorEnabled()) UsageMonitorService.start(this, UsageMonitorService.CONFIGURE);
        refresh();
    }

    private void stopMonitor() {
        prefs.setDiagnosticOnly(true);
        prefs.setMonitorEnabled(false);
        stopService(new Intent(this, UsageMonitorService.class));
        DiagnosticLog.event(this, "manual_stop", "", "", "Monitor and automatic actions disabled");
    }

    private void open(boolean amazon) {
        String selected = amazon ? RedirectEngine.HOME_PACKAGE : prefs.getTargetPackage();
        if (prefs.isMonitorEnabled()) {
            UsageMonitorService.start(this, amazon ? UsageMonitorService.OPEN_AMAZON : UsageMonitorService.OPEN_TARGET);
        } else {
            String error = TargetLauncher.launch(this, selected, amazon, "manual");
            toast(error == null ? "Launch submitted" : error);
        }
    }

    private void refreshTarget() {
        String selected = prefs.getTargetPackage();
        if (selected.equals(displayedTarget)) return;
        displayedTarget = selected;
        String name = selected.isEmpty() ? getString(R.string.target_none) : selected;
        Drawable icon = null;
        boolean installed = false;
        if (!selected.isEmpty()) {
            try {
                PackageManager manager = getPackageManager();
                ApplicationInfo app = manager.getApplicationInfo(selected, 0);
                CharSequence caption = manager.getApplicationLabel(app);
                if (caption != null) name = caption + " (" + selected + ")";
                installed = TargetLauncher.resolve(this, selected, false) != null;
                try { icon = manager.getApplicationIcon(app); }
                catch (RuntimeException ignored) { /* App icons are optional. */ }
            } catch (PackageManager.NameNotFoundException ex) {
                name = selected + getString(R.string.target_not_installed_suffix);
            }
        }
        targetName.setText(name);
        targetChip.setContentDescription(name);
        targetIcon.setImageDrawable(icon);
        targetIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        targetPlaceholder.setVisibility(icon == null ? View.VISIBLE : View.GONE);
        launchButton.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        launchButton.setEnabled(installed);
        launchButton.setAlpha(installed ? 1f : 0.35f);
        targetChip.setNextFocusRightId(installed ? R.id.afta_launch_target : R.id.afta_target);
    }

    private void refresh() {
        if (prefs == null || targetName == null) return;
        refreshTarget();
        for (ToggleBinding binding : toggles) {
            boolean checked = binding.setting.get();
            binding.indicator.setChecked(checked);
            binding.row.setContentDescription(binding.label + ": " + (checked ? "on" : "off"));
            if (binding.row.getId() == R.id.afta_escape_long) {
                boolean available = prefs.isMonitorEnabled() && prefs.isHijackEnabled()
                        && Backend.hasUsageAccess(this);
                binding.row.setEnabled(available);
                binding.row.setFocusable(available);
                binding.indicator.setEnabled(available);
                binding.row.setAlpha(available ? 1f : 0.45f);
                if (!available && binding.row.isFocused()) homeRow.requestFocus();
                homeRow.setNextFocusRightId(available ? R.id.afta_escape_long : R.id.afta_home);
            } else if (binding.row.getId() == R.id.afta_logging) {
                // Verbose metadata also opts into persistent diagnostic recording.
                binding.indicator.setEnabled(!prefs.isVerboseLogging());
            }
        }
        int stateText = !prefs.isMonitorEnabled() ? R.string.afta_stopped
                : !Backend.hasUsageAccess(this) ? R.string.afta_permission_needed
                : prefs.isAutomationSuspended() ? R.string.afta_suspended
                : prefs.isDiagnosticOnly() ? R.string.afta_diagnostic_only
                : !UsageMonitorService.running ? R.string.afta_starting : R.string.afta_active;
        monitorState.setText(stateText);
        monitorState.setTextColor(stateText == R.string.afta_active ? Colors.GOOD
                : stateText == R.string.afta_permission_needed || stateText == R.string.afta_suspended
                ? Colors.PROBLEM : Colors.NEUTRAL);
        if (detailedState != null) detailedState.setText(detailedStatus());
    }

    private String detailedStatus() {
        return "Backend: Usage Access (AFTA/API 25)\n"
                + "Accessibility configured: " + AccessibilityHelper.isOurServiceEnabled(this)
                + "; connected: " + AccessibilityHelper.isOurServiceConnected() + "\n"
                + "Usage Access: " + (Backend.hasUsageAccess(this) ? "allowed" : "not allowed") + "\n"
                + "Display over other apps: " + (Backend.hasOverlayPermission(this) ? "allowed" : "not allowed") + "\n"
                + HomeCover.status + "\n" + UsageMonitorService.status + "\n"
                + "Reported foreground: " + UsageMonitorService.foreground + "\n"
                + UsageMonitorService.launchStatus + "\n" + UsageMonitorService.cleanupStatus
                + (UsageMonitorService.running && UsageMonitorService.lastPollElapsed > 0
                ? "\nLast active poll: " + (SystemClock.elapsedRealtime() - UsageMonitorService.lastPollElapsed) + " ms ago" : "")
                + (DiagnosticLog.storageError().isEmpty() ? "" : "\nLog storage error: " + DiagnosticLog.storageError());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.isMonitorEnabled()) UsageMonitorService.start(this, UsageMonitorService.CONFIGURE);
        displayedTarget = null;
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.post(refreshTask);
    }

    @Override protected void onPause() {
        refreshHandler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        refreshHandler.removeCallbacks(refreshTask);
        closeAdvanced();
        if (detailDialog != null) detailDialog.dismiss();
        super.onDestroy();
    }
}
