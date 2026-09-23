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
import java.util.Collections;
import java.util.List;
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
    /**
     * Every configuration access goes through {@code su}, and each one may wait out its full
     * 15 second timeout. Running those on the UI thread freezes the editor (a single save chains
     * four of them), so all config reads and writes are dispatched here instead.
     */
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private boolean loadingCurrent;
    private boolean preserveDockSelection;

    /** Result of reading the configuration off the UI thread. */
    private static final class Loaded {
        Config.GlobalCfg glob;
        JSONObject target;
        boolean enabled = true;
        boolean dock;
    }

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
        // Grey the fields out until the read returns, otherwise a slow root prompt leaves the
        // editor accepting input that applyLoaded() is about to overwrite.
        setInputsEnabled(false);
        final boolean batch = isBatchMode();
        final String targetPkg = pkg;
        final boolean selectedDock = swDock.isChecked();
        final boolean keepSelectedDock = preserveDockSelection;
        preserveDockSelection = false;
        ioExecutor.execute(() -> {
            Loaded loaded = batch ? new Loaded() : readTarget(targetPkg, keepSelectedDock, selectedDock);
            loaded.glob = Config.getGlobal();
            runOnUiThread(() -> applyLoaded(loaded, batch));
        });
    }

    /** Reads the matching config entry (and its native dock route) on a worker thread. */
    private Loaded readTarget(String targetPkg, boolean keepSelectedDock, boolean selectedDock) {
        Loaded loaded = new Loaded();
        loaded.dock = selectedDock;
        try {
            JSONObject root = Config.readRoot();
            if (TextUtils.isEmpty(targetPkg)) {
                loaded.target = Config.defaultObj(root);
            } else {
                JSONObject apps = Config.appsObj(root);
                JSONObject entry = apps.optJSONObject(targetPkg);
                if (entry != null) {
                    loaded.target = entry;
                    loaded.enabled = !entry.optBoolean("disabled", false);
                    // Keep legacy resolution-only entries on the APK's native window route.
                    // A missing dock key means no window-mode override, not Floating.
                    if (!keepSelectedDock) {
                        loaded.dock = entry.has("dock")
                                ? entry.optBoolean("dock", false)
                                : Config.isAppDockMode(getPackageManager(), targetPkg, null);
                    }
                } else if (!keepSelectedDock) {
                    loaded.dock = Config.isAppDockMode(getPackageManager(), targetPkg, null);
                }
            }
        } catch (Throwable ignored) {}
        return loaded;
    }

    private void applyLoaded(Loaded loaded, boolean batch) {
        loadingCurrent = true;
        if (batch) {
            etW.setText(String.valueOf(loaded.glob.floatingWidth));
            etH.setText(String.valueOf(loaded.glob.floatingHeight));
            etDensity.setText("");
            swEnable.setChecked(true);
            swDock.setChecked(false);
            setInputsEnabled(true);
            loadingCurrent = false;
            return;
        }

        Config.GlobalCfg glob = loaded.glob;
        JSONObject target = loaded.target;
        boolean isDock = loaded.dock;
        int defW = isDock ? glob.dockWidth : glob.floatingWidth;
        int defH = isDock ? glob.dockHeight : glob.floatingHeight;
        boolean isDefault = TextUtils.isEmpty(pkg);

        if (target != null) {
            String[] keys = ConfigSchema.resolutionKeys(isDock, target);
            etW.setText(String.valueOf(target.optInt(keys[0], defW)));
            etH.setText(String.valueOf(target.optInt(keys[1], defH)));
            // Blank keeps "follow system" intact; prefilling the built-in default would pin
            // a density the user never chose as soon as the page is saved.
            etDensity.setText(target.has(keys[2]) ? String.valueOf(target.optInt(keys[2])) : "");
        } else {
            etW.setText(String.valueOf(defW));
            etH.setText(String.valueOf(defH));
            etDensity.setText("");
        }
        if (!isDefault) swDock.setChecked(isDock);

        // Reloading may have swapped which window mode the fields edit; a stale preset selection
        // would then describe a mode the save no longer targets.
        spPreset.setSelection(0);
        spPresetSwap.setSelection(0);

        swEnable.setChecked(loaded.enabled);
        setInputsEnabled(loaded.enabled || isDefault); // default is always editable
        loadingCurrent = false;
    }

    private void setInputsEnabled(boolean enabled) {
        spPreset.setEnabled(enabled);
        spPresetSwap.setEnabled(enabled);
        etW.setEnabled(enabled);
        etH.setEnabled(enabled);
        etDensity.setEnabled(enabled);
    }

    void save(boolean applyAfterSaving) {
        // Snapshot every widget on the UI thread first; nothing below may touch a view.
        final boolean batch = isBatchMode();
        final List<String> targets = batch ? new ArrayList<>(batchPackages) : null;
        final String targetPkg = pkg;
        final boolean enable = swEnable.isChecked();
        final boolean dock = swDock.isChecked();
        final String widthText = textOf(etW);
        final String heightText = textOf(etH);
        final String densityText = textOf(etDensity);
        setInputsEnabled(false);
        btnSave.setEnabled(false);
        btnSaveAndApply.setEnabled(false);

        ioExecutor.execute(() -> {
            Config.GlobalCfg glob = Config.getGlobal();
            int defW = dock ? glob.dockWidth : glob.floatingWidth;
            int defH = dock ? glob.dockHeight : glob.floatingHeight;
            int w = parseInt(widthText, defW);
            int h = parseInt(heightText, defH);
            if (!ConfigSchema.isResolutionValid(w, h)) {
                postMessage(getString(R.string.invalid_res));
                return;
            }
            boolean ok;
            try {
                if (batch) {
                    int density = parseOptionalDensity(densityText);
                    ok = Config.applyBatchSettings(targets, w, h, density, enable, dock);
                } else {
                    ok = Config.updateRoot(root -> writeEntry(root, targetPkg, enable, dock,
                            w, h, densityText));
                }
            } catch (Throwable t) {
                postMessage(getString(R.string.save_failed) + ": " + t);
                return;
            }
            final boolean saved = ok;
            runOnUiThread(() -> onSaveFinished(saved, batch, targets, targetPkg, applyAfterSaving));
        });
    }

    /** Applies the editor values to the root object. Runs on {@code ioExecutor}. */
    private void writeEntry(JSONObject root, String targetPkg, boolean enable, boolean dock,
            int w, int h, String densityText) throws Exception {
        if (TextUtils.isEmpty(targetPkg)) {
            JSONObject target = Config.defaultObj(root);
            root.put("default", target);
            String pfx = dock ? "near_" : "";
            target.put(pfx + "w", w);
            target.put(pfx + "h", h);
            if (!densityText.isEmpty()) target.put(pfx + "density", parseIntStr(densityText));
            else target.remove(pfx + "density");
            return;
        }
        JSONObject apps = Config.appsObj(root);
        JSONObject target = apps.optJSONObject(targetPkg);
        if (target == null) {
            target = new JSONObject();
            apps.put(targetPkg, target);
        }
        root.put("apps", apps);
        target.put("disabled", !enable);
        target.put("dock", dock);
        String pfx = dock ? "near_" : "";
        target.put(pfx + "w", w);
        target.put(pfx + "h", h);
        if (!densityText.isEmpty()) target.put(pfx + "density", parseIntStr(densityText));
        else target.remove(pfx + "density");
    }

    private void onSaveFinished(boolean ok, boolean batch, List<String> targets,
            String targetPkg, boolean applyAfterSaving) {
        String message = batch
                ? getString(ok ? R.string.batch_updated : R.string.write_failed, targets.size())
                : getString(ok ? R.string.saved_toast : R.string.write_failed);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (!ok) {
            setInputsEnabled(true);
            btnSave.setEnabled(true);
            btnSaveAndApply.setEnabled(true);
            return;
        }
        if (applyAfterSaving) {
            if (batch) restartAppsAsync(targets);
            else if (!TextUtils.isEmpty(targetPkg)) {
                restartAppsAsync(Collections.singletonList(targetPkg));
            }
        }
        finish();
    }

    private void postMessage(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            setInputsEnabled(true);
            btnSave.setEnabled(true);
            btnSaveAndApply.setEnabled(true);
        });
    }

    void removeOverride() {
        final String targetPkg = pkg;
        btnRemove.setEnabled(false);
        ioExecutor.execute(() -> {
            boolean ok = Config.updateRoot(root -> {
                if (!TextUtils.isEmpty(targetPkg)) {
                    JSONObject apps = Config.appsObj(root);
                    apps.remove(targetPkg);
                    root.put("apps", apps);
                } else {
                    // Only the resolution belongs to this editor. The same object also stores the
                    // list filters and the applyThird/applySystem gates, and the user did not ask
                    // to reset those, so drop the six resolution keys and keep everything else.
                    ConfigSchema.clearDefaultResolution(root);
                }
            });
            final boolean removed = ok;
            runOnUiThread(() -> {
                Toast.makeText(this, getString(removed ? R.string.remove_toast : R.string.write_failed),
                        Toast.LENGTH_LONG).show();
                if (removed) finish();
                else btnRemove.setEnabled(true);
            });
        });
    }

    private static String textOf(TextInputEditText field) {
        Editable editable = field.getText();
        return editable == null ? "" : editable.toString().trim();
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
        // Resolve every relaunch target before killing anything: force-stopping first and only
        // then discovering there is no launcher intent (background-only services, VR-only
        // entries) leaves the app stopped with nothing able to bring it back.
        ArrayList<Intent> launches = new ArrayList<>();
        ArrayList<String> targets = new ArrayList<>();
        for (String packageName : packages) {
            if (TextUtils.isEmpty(packageName)) continue;
            Intent intent = resolveLaunchIntent(packageName);
            if (intent == null) continue;
            targets.add(packageName);
            launches.add(intent);
        }
        for (int i = 0; i < targets.size(); i++) {
            if (forceStopQuietly(targets.get(i))) startActivitySafely(launches.get(i));
        }
        if (!TextUtils.isEmpty(foregroundPackage) && !targets.contains(foregroundPackage)) {
            Intent intent = resolveLaunchIntent(foregroundPackage);
            if (intent != null) startActivitySafely(intent);
        }
    }

    /** The home-screen intent for a package, or null when nothing can bring it back. */
    private Intent resolveLaunchIntent(String packageName) {
        try {
            return getPackageManager().getLaunchIntentForPackage(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void startActivitySafely(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            getApplicationContext().startActivity(intent);
        } catch (Throwable ignored) {
            // Saving the configuration must still succeed when an app cannot be relaunched.
        }
    }

    /**
     * A root child inherits this app's pipes, and output nobody drains fills the 64 KiB buffer
     * and then blocks inside waitFor() until the timeout expires. Silence it like Config does.
     */
    private boolean forceStopQuietly(String packageName) {
        Process process = null;
        try {
            String quoted = "'" + packageName.replace("'", "'\\''") + "'";
            ProcessBuilder builder = new ProcessBuilder("su", "-c", "am force-stop " + quoted);
            java.io.File sink = new java.io.File("/dev/null");
            if (sink.exists()) {
                builder.redirectOutput(sink);
                builder.redirectError(sink);
            }
            process = builder.start();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
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

    static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Throwable t) { return fallback; }
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
