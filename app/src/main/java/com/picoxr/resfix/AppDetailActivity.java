package com.picoxr.resfix;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per-app (or default) resolution editor. If pkg == "" it edits the global default
 * for non-system apps (stored under "default"), otherwise under "apps[<pkg>]".
 */
public class AppDetailActivity extends AppCompatActivity {

    public static final String EXTRA_BATCH_PACKAGES = "batch_packages";
    String pkg;
    ArrayList<String> batchPackages;
    TextView tvTitle, tvPkg, tvEnableLabel, tvDockLabel, tvDensityLabel;
    ImageView ivIcon;
    MaterialSwitch swEnable, swDock;
    MaterialCardView cardEnable, cardDock;
    Spinner spPreset, spPresetSwap;
    TextInputEditText etW, etH, etDensity;
    MaterialButton btnSave, btnSaveAndApply, btnRemove, btnSwapVal;
    private static final ExecutorService restartExecutor = Executors.newSingleThreadExecutor();
    private boolean loadingCurrent;
    private boolean preserveDockSelection;

    final String[] resFloatArr = {"1280 × 722","1600 × 902","1920 × 1082","2560 × 1442","3840 × 2162"};
    final String[] resDockArr = {"807 × 432","1127 × 752","1447 × 1072","1767 × 1392","2087 × 1712"};

    @Override
    protected void onCreate(Bundle b) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        pkg = getIntent().getStringExtra("pkg");
        batchPackages = getIntent().getStringArrayListExtra(EXTRA_BATCH_PACKAGES);
        boolean isBatch = isBatchMode();

        Toolbar toolbar = findViewById(R.id.detail_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvTitle = findViewById(R.id.tv_title);
        tvPkg = findViewById(R.id.tv_pkg);
        ivIcon = findViewById(R.id.iv_icon);
        swEnable = findViewById(R.id.sw_enable);
        swDock = findViewById(R.id.sw_dock);
        tvEnableLabel = findViewById(R.id.tv_enable_label);
        tvDockLabel = findViewById(R.id.tv_dock_label);
        cardEnable = findViewById(R.id.card_enable);
        cardDock = findViewById(R.id.card_dock);
        spPreset = findViewById(R.id.sp_preset);
        spPresetSwap = findViewById(R.id.sp_preset_swap);
        etW = findViewById(R.id.et_w);
        etH = findViewById(R.id.et_h);
        etDensity = findViewById(R.id.et_density);
        btnSave = findViewById(R.id.btn_save);
        btnSaveAndApply = findViewById(R.id.btn_save_and_apply);
        btnRemove = findViewById(R.id.btn_remove);
        btnSwapVal = findViewById(R.id.btn_swap_val);

        if (isBatch) {
            tvTitle.setText(getString(R.string.batch_apps_title, batchPackages.size()));
            tvPkg.setText(R.string.batch_apps_hint);
            ivIcon.setImageResource(R.drawable.ic_batch_apps);
            View appInfoCard = findViewById(R.id.card_app_info);
            appInfoCard.setClickable(true);
            appInfoCard.setContentDescription(getString(R.string.batch_apps_hint));
            appInfoCard.setOnClickListener(v -> showBatchAppsPopup(v));
            btnRemove.setVisibility(View.GONE);
        } else if (TextUtils.isEmpty(pkg)) {
            tvTitle.setText(R.string.default_title);
            tvPkg.setText(R.string.default_cfg);
            ivIcon.setImageResource(R.mipmap.ic_launcher);
            btnSaveAndApply.setVisibility(View.GONE);
        } else {
            tvPkg.setText(pkg);
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                tvTitle.setText(pm.getApplicationLabel(ai));
                ivIcon.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception e) {
                tvTitle.setText(R.string.detail_title);
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        }
        tvTitle.setSelected(true);
        tvPkg.setSelected(true);
        setupAutoMarquee(tvEnableLabel);
        setupAutoMarquee(tvDockLabel);
        tvDensityLabel = findViewById(R.id.tv_density_label);
        setupAutoMarquee(tvDensityLabel);

        // Floating Resolution
        String[] itemsFloat = new String[resFloatArr.length + 1];
        itemsFloat[0] = getString(R.string.select_preset);
        System.arraycopy(resFloatArr, 0, itemsFloat, 1, resFloatArr.length);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, itemsFloat);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPreset.setAdapter(adapter);
        spPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    applyDimensions(resFloatArr[position - 1]);
                    spPresetSwap.setSelection(0);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Dock Resolution
        String[] itemsDock = new String[resDockArr.length + 1];
        itemsDock[0] = getString(R.string.select_preset);
        System.arraycopy(resDockArr, 0, itemsDock, 1, resDockArr.length);
        ArrayAdapter<String> adapterSwap = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, itemsDock);
        adapterSwap.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPresetSwap.setAdapter(adapterSwap);
        spPresetSwap.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    applyDimensions(resDockArr[position - 1]);
                    spPreset.setSelection(0);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadCurrent();
        cardEnable.setOnClickListener(v -> swEnable.toggle());
        cardDock.setOnClickListener(v -> swDock.toggle());
        swDock.setOnCheckedChangeListener((x, checked) -> {
            if (!loadingCurrent && !isBatchMode()) {
                preserveDockSelection = true;
                loadCurrent();
            }
        });

        swEnable.setOnCheckedChangeListener((x, checked) -> {
            spPreset.setEnabled(checked);
            spPresetSwap.setEnabled(checked);
            etW.setEnabled(checked); etH.setEnabled(checked); etDensity.setEnabled(checked);
        });

        btnSave.setOnClickListener(v -> save(false));
        btnSaveAndApply.setOnClickListener(v -> save(true));
        btnRemove.setOnClickListener(v -> removeOverride());
        btnSwapVal.setOnClickListener(v -> {
            Editable tw = etW.getText();
            Editable th = etH.getText();
            String sw = tw != null ? tw.toString() : "";
            String sh = th != null ? th.toString() : "";
            etW.setText(sh);
            etH.setText(sw);
        });
    }

    // Helper method to split the string and set text
    private void applyDimensions(String resolution) {
        String[] dimensions = resolution.split(" × ");
        if (dimensions.length == 2) {
            etW.setText(dimensions[0]);
            etH.setText(dimensions[1]);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void loadCurrent() {
        loadingCurrent = true;
        Config.GlobalCfg glob = Config.getGlobal();
        if (isBatchMode()) {
            etW.setText(String.valueOf(glob.floatingWidth));
            etH.setText(String.valueOf(glob.floatingHeight));
            etDensity.setText("");
            swEnable.setChecked(true);
            swDock.setChecked(false);
            loadingCurrent = false;
            return;
        }
        JSONObject root = Config.readRoot();
        JSONObject target = null;
        boolean enabled = true;
        boolean isDock = TextUtils.isEmpty(pkg) && swDock.isChecked();
        boolean useSelectedDock = preserveDockSelection;
        preserveDockSelection = false;
        try {
            if (TextUtils.isEmpty(pkg)) {
                target = Config.defaultObj(root);
            } else {
                JSONObject apps = Config.appsObj(root);
                if (apps.has(pkg)) {
                    target = apps.getJSONObject(pkg);
                    enabled = !target.optBoolean("disabled", false);
                    // Keep legacy resolution-only entries on the APK's native window route.
                    // A missing dock key means no window-mode override, not Floating.
                    if (!useSelectedDock) {
                        isDock = target.has("dock")
                                ? target.optBoolean("dock", false)
                                : Config.isAppDockMode(getPackageManager(), pkg, null);
                    }
                } else if (!useSelectedDock) {
                    isDock = Config.isAppDockMode(getPackageManager(), pkg, null);
                }
            }
        } catch (Throwable ignored) {}

        if (TextUtils.isEmpty(pkg)) {
            if (target != null) {
                if (isDock) {
                    etW.setText(String.valueOf(target.optInt("near_w", glob.dockWidth)));
                    etH.setText(String.valueOf(target.optInt("near_h", glob.dockHeight)));
                    etDensity.setText(target.has("near_density") ? String.valueOf(target.optInt("near_density")) : String.valueOf(glob.dockDensity));
                } else {
                    etW.setText(String.valueOf(target.optInt("w", glob.floatingWidth)));
                    etH.setText(String.valueOf(target.optInt("h", glob.floatingHeight)));
                    etDensity.setText(target.has("density") ? String.valueOf(target.optInt("density")) : String.valueOf(glob.floatingDensity));
                }
            } else {
                if (isDock) {
                    etW.setText(String.valueOf(glob.dockWidth));
                    etH.setText(String.valueOf(glob.dockHeight));
                    etDensity.setText(String.valueOf(glob.dockDensity));
                } else {
                    etW.setText(String.valueOf(glob.floatingWidth));
                    etH.setText(String.valueOf(glob.floatingHeight));
                    etDensity.setText(String.valueOf(glob.floatingDensity));
                }
            }
        } else {
            if (target != null) {
                String widthKey = isDock ? "near_w" : "w";
                String heightKey = isDock ? "near_h" : "h";
                String densityKey = isDock ? "near_density" : "density";
                if (isDock && (!target.has(widthKey) || !target.has(heightKey))) {
                    widthKey = "w";
                    heightKey = "h";
                    densityKey = "density";
                }
                etW.setText(String.valueOf(target.optInt(widthKey, isDock ? glob.dockWidth : glob.floatingWidth)));
                etH.setText(String.valueOf(target.optInt(heightKey, isDock ? glob.dockHeight : glob.floatingHeight)));
                etDensity.setText(String.valueOf(target.optInt(densityKey, isDock ? glob.dockDensity : glob.floatingDensity)));
            } else {
                etW.setText(String.valueOf(isDock ? glob.dockWidth : glob.floatingWidth));
                etH.setText(String.valueOf(isDock ? glob.dockHeight : glob.floatingHeight));
                etDensity.setText(String.valueOf(isDock ? glob.dockDensity : glob.floatingDensity));
            }
            swDock.setChecked(isDock);
        }

        swEnable.setChecked(enabled);
        boolean en = enabled || TextUtils.isEmpty(pkg); // default always editable
        spPreset.setEnabled(en);
        spPresetSwap.setEnabled(en);
        etW.setEnabled(en); etH.setEnabled(en); etDensity.setEnabled(en);
        loadingCurrent = false;
    }

    void save(boolean applyAfterSaving) {
        Config.GlobalCfg glob = Config.getGlobal();
        boolean isDockInEditor = swDock.isChecked();
        int defW = isDockInEditor ? glob.dockWidth : glob.floatingWidth;
        int defH = isDockInEditor ? glob.dockHeight : glob.floatingHeight;
        
        int w = parseInt(etW, defW), h = parseInt(etH, defH);
        if (w < 320 || h < 240) { Toast.makeText(this,R.string.invalid_res,Toast.LENGTH_SHORT).show(); return; }
        try {
            if (isBatchMode()) {
                String densityText = etDensity.getText() != null ? etDensity.getText().toString().trim() : "";
                int density = parseOptionalDensity(densityText);
                boolean ok = Config.applyBatchSettings(batchPackages, w, h, density,
                        swEnable.isChecked(), swDock.isChecked());
                Toast.makeText(this, ok ? getString(R.string.batch_updated, batchPackages.size())
                        : getString(R.string.write_failed), Toast.LENGTH_LONG).show();
                if (ok) {
                    if (applyAfterSaving) restartAppsAsync(batchPackages);
                    finish();
                }
                return;
            }
            JSONObject root = Config.readRootForWrite();
            if (root == null) {
                Toast.makeText(this, R.string.write_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (TextUtils.isEmpty(pkg)) {
                JSONObject target = Config.defaultObj(root);
                root.put("default", target);
                String pfx = isDockInEditor ? "near_" : "";
                target.put(pfx + "w", w); target.put(pfx + "h", h);
                String d = etDensity.getText() != null ? etDensity.getText().toString().trim() : "";
                if (!TextUtils.isEmpty(d)) target.put(pfx + "density", parseIntStr(d));
                else target.remove(pfx + "density");
            } else {
                JSONObject apps = Config.appsObj(root);
                JSONObject target = apps.optJSONObject(pkg);
                if (target == null) { target = new JSONObject(); apps.put(pkg, target); }
                root.put("apps", apps);
                target.put("disabled", !swEnable.isChecked());
                target.put("dock", swDock.isChecked());
                String pfx = swDock.isChecked() ? "near_" : "";
                target.put(pfx + "w", w); target.put(pfx + "h", h);
                String d = etDensity.getText() != null ? etDensity.getText().toString().trim() : "";
                if (!TextUtils.isEmpty(d)) target.put(pfx + "density", parseIntStr(d));
                else target.remove(pfx + "density");
            }
            boolean ok = Config.writeRoot(root);
            Toast.makeText(this, ok ? getString(R.string.saved_toast) : getString(R.string.write_failed), Toast.LENGTH_LONG).show();
            if (ok) {
                if (applyAfterSaving && !TextUtils.isEmpty(pkg)) {
                    restartAppsAsync(java.util.Collections.singletonList(pkg));
                }
                finish();
            }
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.save_failed) + ": " + t, Toast.LENGTH_LONG).show();
        }
    }

    void removeOverride() {
        try {
            JSONObject root = Config.readRootForWrite();
            if (root == null) {
                Toast.makeText(this, R.string.write_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (!TextUtils.isEmpty(pkg)) {
                JSONObject apps = Config.appsObj(root);
                apps.remove(pkg);
                root.put("apps", apps);
            } else {
                root.remove("default");
            }
            boolean ok = Config.writeRoot(root);
            Toast.makeText(this, ok ? getString(R.string.remove_toast) : getString(R.string.write_failed), Toast.LENGTH_LONG).show();
            if (ok) finish();
        } catch (Throwable t) { Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show(); }
    }

    private boolean isBatchMode() {
        return batchPackages != null && !batchPackages.isEmpty();
    }

    private void showBatchAppsPopup(View anchor) {
        if (!isBatchMode()) return;

        LinearLayout content = new LinearLayout(this);
        int padding = dp(14);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundResource(R.drawable.bg_dropdown);

        ArrayList<String> packages = new ArrayList<>(batchPackages);
        packages.sort((first, second) -> appLabel(first).compareToIgnoreCase(appLabel(second)));
        for (String packageName : packages) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            ImageView icon = new ImageView(this);
            row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(packageName));
            } catch (PackageManager.NameNotFoundException ignored) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            LinearLayout textColumn = new LinearLayout(this);
            textColumn.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(this);
            name.setText(appLabel(packageName));
            name.setTextColor(getColor(android.R.color.white));
            name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textColumn.addView(name, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView packageText = new TextView(this);
            packageText.setText(packageName);
            packageText.setTextColor(getColor(android.R.color.darker_gray));
            packageText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            packageText.setSingleLine(true);
            packageText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textColumn.addView(packageText, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameParams.setMarginStart(dp(12));
            row.addView(textColumn, nameParams);
            content.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        PopupWindow popup = new PopupWindow(content, Math.max(anchor.getWidth(), dp(260)),
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(12));
        popup.setOutsideTouchable(true);
        popup.showAsDropDown(anchor, 0, dp(8));
    }

    private String appLabel(String packageName) {
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(appInfo);
            return !TextUtils.isEmpty(label) ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void restartAppsAsync(java.util.List<String> packages) {
        ArrayList<String> targets = new ArrayList<>(packages);
        restartExecutor.execute(() -> restartApps(targets));
    }

    private void restartApps(java.util.List<String> packages) {
        String foregroundPackage = getForegroundPackage();
        for (String packageName : packages) {
            if (TextUtils.isEmpty(packageName)) continue;
            try {
                Process stop = new ProcessBuilder("su", "-c", "am force-stop " + packageName).start();
                if (stop.waitFor(15, TimeUnit.SECONDS) && stop.exitValue() == 0) {
                    launchPackage(packageName);
                } else {
                    stop.destroyForcibly();
                }
            } catch (Throwable ignored) {
                // Saving the configuration must still succeed when an app cannot be relaunched.
            }
        }
        if (!TextUtils.isEmpty(foregroundPackage) && !packages.contains(foregroundPackage)) {
            launchPackage(foregroundPackage);
        }
    }

    private boolean isAppRunning(String packageName) {
        try {
            Process process = new ProcessBuilder("su", "-c", "pidof " + packageName)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            reader.close();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0 && !TextUtils.isEmpty(output);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String getForegroundPackage() {
        try {
            Process process = new ProcessBuilder("su", "-c", "dumpsys window windows")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String result = null;
            while ((line = reader.readLine()) != null) {
                int marker = line.indexOf("mCurrentFocus=");
                if (marker < 0) continue;
                int slash = line.indexOf('/', marker);
                if (slash < 0) continue;
                int space = line.lastIndexOf(' ', slash);
                String component = line.substring(space + 1, slash).trim();
                int brace = component.lastIndexOf('}');
                result = brace >= 0 ? component.substring(brace + 1) : component;
                break;
            }
            reader.close();
            if (!process.waitFor(15, TimeUnit.SECONDS) && process.isAlive()) process.destroyForcibly();
            return result;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void launchPackage(String packageName) {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                getApplicationContext().startActivity(launchIntent);
            }
        } catch (Throwable ignored) {
        }
    }

    static int parseInt(TextInputEditText e, int def) {
        if (e.getText() == null) return def;
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }
    static int parseIntStr(String s) {
        return Integer.parseInt(s.trim());
    }

    static int parseOptionalDensity(String s) {
        if (TextUtils.isEmpty(s)) return -1;
        int density = parseIntStr(s);
        if (!ConfigSchema.isDensityValid(density)) {
            throw new IllegalArgumentException("Invalid density");
        }
        return density;
    }

    private void setupAutoMarquee(TextView tv) {
        tv.setSelected(true);
        tv.post(() -> {
            if (tv.getLayout() != null && tv.getLayout().getLineCount() > 2) {
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
                tv.setSelected(true);
            }
        });
    }
}
