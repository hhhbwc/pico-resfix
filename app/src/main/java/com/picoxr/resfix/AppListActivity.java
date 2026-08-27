package com.picoxr.resfix;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main screen: lists installed 2D (launcher) apps. A "show system apps" switch filters
 * system apps. Tapping an app opens AppDetailActivity for its per-app resolution.
 * "默认设置" opens a simple editor for the global default (third-party apps).
 */
public class AppListActivity extends AppCompatActivity {

    RecyclerView recycler;
    TextView status;
    FloatingActionButton fabDefault;
    FloatingActionButton fabBatch;
    FloatingActionButton fabBatchEdit;
    EditText etSearch;
    ImageView ivClearSearch;
    AppAdapter adapter;
    Config.GlobalCfg glob;
    boolean showUser = true;
    boolean showSystem = false;
    boolean showVR = false;
    boolean showModified = true;
    private List<Config.AppEntry> allApps;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService configExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong reloadGeneration = new AtomicLong();
    private Future<?> reloadFuture;
    private volatile boolean destroyed;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Set<String> selectedPackages = new HashSet<>();
    private boolean selectionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recycler = findViewById(R.id.recycler);
        status = findViewById(R.id.status);
        fabDefault = findViewById(R.id.fab_default);
        fabBatch = findViewById(R.id.fab_batch);
        fabBatchEdit = findViewById(R.id.fab_batch_edit);
        etSearch = findViewById(R.id.et_search);
        ivClearSearch = findViewById(R.id.iv_clear_search);
        Config.GlobalCfg saved = Config.getGlobal();
        showUser = saved.showUser;
        showSystem = saved.showSystem;
        showVR = saved.showVR;
        showModified = saved.showModified;

        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new AppAdapter();
        recycler.setAdapter(adapter);

        fabDefault.setOnClickListener(v -> openDefaultEditor());
        fabBatch.setOnClickListener(v -> {
            if (!selectionMode) {
                selectionMode = true;
                updateBatchButtonState();
                adapter.notifyDataSetChanged();
            } else {
                exitSelectionMode();
            }
        });
        fabBatchEdit.setOnClickListener(v -> {
            if (selectedPackages.isEmpty()) {
                android.widget.Toast.makeText(this, R.string.batch_selection_empty,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            showBatchSettingsDialog();
        });
        ivClearSearch.setOnClickListener(v -> etSearch.setText(""));
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                ivClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_list, menu);
        MenuItem item = menu.findItem(R.id.menu_filter);
        View actionView = item.getActionView();
        if (actionView != null) {
            MaterialButton btnMod = actionView.findViewById(R.id.btn_filter_modified);
            MaterialButton btnUser = actionView.findViewById(R.id.btn_filter_user);
            MaterialButton btnSys = actionView.findViewById(R.id.btn_filter_system);
            MaterialButton btnVR = actionView.findViewById(R.id.btn_filter_vr);

            renderFilterButtons(actionView);

            btnMod.setOnClickListener(v -> {
                if (showModified && !showUser && !showSystem && !showVR) return;
                showModified = !showModified;
                updateFilterButtonStyle(btnMod, showModified);
                saveFilters();
                reload();
            });
            btnUser.setOnClickListener(v -> {
                if (!showModified && showUser && !showSystem && !showVR) return;
                showUser = !showUser;
                updateFilterButtonStyle(btnUser, showUser);
                saveFilters();
                reload();
            });
            btnSys.setOnClickListener(v -> {
                if (!showModified && !showUser && showSystem && !showVR) return;
                showSystem = !showSystem;
                updateFilterButtonStyle(btnSys, showSystem);
                saveFilters();
                reload();
            });
            btnVR.setOnClickListener(v -> {
                if (!showModified && !showUser && !showSystem && showVR) return;
                showVR = !showVR;
                updateFilterButtonStyle(btnVR, showVR);
                saveFilters();
                reload();
            });
        }
        return true;
    }

    private void saveFilters() {
        final boolean u = showUser, s = showSystem, v = showVR, m = showModified;
        configExecutor.execute(() -> Config.updateRoot(root -> {
            org.json.JSONObject defaults = Config.defaultObj(root);
            defaults.put("showUser", u);
            defaults.put("showSystem", s);
            defaults.put("showVR", v);
            defaults.put("showModified", m);
            root.put("default", defaults);
        }));
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_import_batch) {
            showBatchImportDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showBatchImportDialog() {
        EditText input = new EditText(this);
        input.setMinLines(8);
        input.setHint("{\n  \"apps\": {\n    \"com.example.app\": {\"w\":1920,\"h\":1080,\"density\":240,\"dock\":true}\n  }\n}");
        new AlertDialog.Builder(this)
                .setTitle("Batch Import")
                .setMessage("Paste JSON with default and/or apps. Existing entries with the same package will be replaced.")
                .setView(input)
                .setPositiveButton("Import", (d, which) -> {
                    executor.execute(() -> {
                        String result = Config.applyBatchJson(String.valueOf(input.getText()));
                        handler.post(() -> {
                            if (destroyed || isFinishing()) return;
                            android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show();
                            reload();
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> exitSelectionMode())
                .show();
    }

    private void exitSelectionMode() {
        selectedPackages.clear();
        selectionMode = false;
        updateBatchButtonState();
        adapter.notifyDataSetChanged();
    }

    private void showBatchSettingsDialog() {
        ArrayList<String> packages = new ArrayList<>(selectedPackages);
        exitSelectionMode();
        Intent intent = new Intent(this, AppDetailActivity.class);
        intent.putStringArrayListExtra(AppDetailActivity.EXTRA_BATCH_PACKAGES,
                packages);
        startActivity(intent);
    }

    private void updateBatchButtonState() {
        int color = getColor(selectionMode ? R.color.toggle_button : R.color.dropdown_bg);
        fabBatch.setBackgroundTintList(ColorStateList.valueOf(color));
        fabBatchEdit.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        fabBatch.setContentDescription(selectionMode
                ? getString(R.string.batch_selected_count, selectedPackages.size())
                : getString(R.string.batch_select));
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    private void renderFilterButtons(View actionView) {
        if (actionView == null) return;
        updateFilterButtonStyle((MaterialButton) actionView.findViewById(R.id.btn_filter_modified), showModified);
        updateFilterButtonStyle((MaterialButton) actionView.findViewById(R.id.btn_filter_user), showUser);
        updateFilterButtonStyle((MaterialButton) actionView.findViewById(R.id.btn_filter_system), showSystem);
        updateFilterButtonStyle((MaterialButton) actionView.findViewById(R.id.btn_filter_vr), showVR);
    }

    private boolean isAlive(long generation) {
        return !destroyed && !isFinishing() && generation == reloadGeneration.get();
    }

    private void updateFilterButtonStyle(MaterialButton btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.toggle_button)));
            btn.setTextColor(getColor(android.R.color.white));
        } else {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.card_bg)));
            btn.setTextColor(getColor(android.R.color.darker_gray));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        reloadGeneration.incrementAndGet();
        if (reloadFuture != null) reloadFuture.cancel(true);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        configExecutor.shutdownNow();
        super.onDestroy();
    }

    void reload() {
        if (destroyed) return;
        final long generation = reloadGeneration.incrementAndGet();
        final boolean u = showUser, s = showSystem, v = showVR, m = showModified;
        if (reloadFuture != null) reloadFuture.cancel(true);
        reloadFuture = executor.submit(() -> {
            if (!isAlive(generation)) return;
            Config.GlobalCfg currentGlob = Config.getGlobal();
            List<Config.AppEntry> result = Config.listApps(this, u, s, v, m, currentGlob);

            // Sort: Custom first, then by label alphabet
            result.sort((a, b) -> {
                if (a.hasOverride != b.hasOverride) {
                    return a.hasOverride ? -1 : 1;
                }
                return String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label));
            });

            handler.post(() -> {
                if (!isAlive(generation)) return;
                glob = currentGlob;
                allApps = result;
                android.util.Log.i("ResFixGUI", "listApps returned " + allApps.size()
                        + " apps (showUser=" + u + ", showSystem=" + s + ")");
                filter(etSearch.getText().toString());
            });
        });
    }

    void filter(String query) {
        if (destroyed || allApps == null) return;
        List<Config.AppEntry> filtered;
        if (android.text.TextUtils.isEmpty(query)) {
            filtered = allApps;
        } else {
            filtered = new java.util.ArrayList<>();
            String q = query.toLowerCase(Locale.ROOT);
            for (Config.AppEntry e : allApps) {
                if (String.valueOf(e.label).toLowerCase(Locale.ROOT).contains(q)
                        || e.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                    filtered.add(e);
                }
            }
        }
        adapter.setApps(filtered);
        status.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    void openDefaultEditor() {
        // Reuse AppDetailActivity in "default mode" (no package = edit global default)
        Intent i = new Intent(this, AppDetailActivity.class);
        i.putExtra("pkg", "");
        startActivity(i);
    }

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        List<Config.AppEntry> apps;

        void setApps(List<Config.AppEntry> a) { apps = a; notifyDataSetChanged(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int t) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Config.AppEntry e = apps.get(pos);
            h.label.setText(e.label != null ? e.label : e.pkg);
            h.label.setSelected(true);
            h.pkg.setText(e.pkg);
            h.pkg.setSelected(true);
            h.cardSys.setVisibility(e.isSystem ? View.VISIBLE : View.GONE);
            h.cardDock.setVisibility(e.isDock ? View.VISIBLE : View.GONE);
            String prefix = e.hasOverride
                    ? h.root.getContext().getString(R.string.custom_prefix)
                    : h.root.getContext().getString(R.string.default_prefix);
            String res = prefix + e.w + "x" + e.h + (e.density > 0 ? " @" + e.density : "");
            h.res.setText(res);
            if (e.hasOverride) {
                h.res.setTextColor(h.root.getContext().getColor(R.color.primary));
            } else {
                h.res.setTextColor(h.root.getContext().getColor(android.R.color.white));
            }
            h.selected.setOnCheckedChangeListener(null);
            h.selected.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            h.selected.setChecked(selectedPackages.contains(e.pkg));
            h.selected.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedPackages.add(e.pkg);
                    updateBatchButtonState();
                } else {
                    selectedPackages.remove(e.pkg);
                    if (selectionMode && selectedPackages.isEmpty()) {
                        exitSelectionMode();
                    } else {
                        updateBatchButtonState();
                    }
                }
            });
            h.root.setOnClickListener(v -> {
                if (selectionMode) {
                    h.selected.setChecked(!h.selected.isChecked());
                } else {
                    Intent i = new Intent(AppListActivity.this, AppDetailActivity.class);
                    i.putExtra("pkg", e.pkg);
                    startActivity(i);
                }
            });

            h.root.setOnLongClickListener(v -> {
                if (!selectionMode) {
                    selectionMode = true;
                    selectedPackages.add(e.pkg);
                    updateBatchButtonState();
                    adapter.notifyDataSetChanged();
                } else {
                    h.selected.setChecked(!h.selected.isChecked());
                }
                return true;
            });

            // Lazy load icon
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.tag = e.pkg;
            final String pkgName = e.pkg;
            Drawable cached = iconCache.get(pkgName);
            if (cached != null) {
                h.icon.setImageDrawable(cached);
            } else {
                executor.execute(() -> {
                    try {
                        PackageManager pm = getPackageManager();
                        final Drawable icon = pm.getApplicationIcon(pkgName);
                        iconCache.put(pkgName, icon);
                        handler.post(() -> {
                            if (!destroyed && !isFinishing() && pkgName.equals(h.tag)
                                    && h.itemView.getWindowToken() != null) {
                                h.icon.setImageDrawable(icon);
                            }
                        });
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        @Override
        public int getItemCount() { return apps == null ? 0 : apps.size(); }

        class VH extends RecyclerView.ViewHolder {
            View root, cardSys, cardDock; TextView label, pkg, res;
            ImageView icon; com.google.android.material.checkbox.MaterialCheckBox selected; String tag;
            VH(View v) { super(v); root = v; label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_pkg); res = v.findViewById(R.id.tv_res);
                cardSys = v.findViewById(R.id.card_sys);
                cardDock = v.findViewById(R.id.card_dock);
                selected = v.findViewById(R.id.cb_selected);
                icon = v.findViewById(R.id.iv_icon); }
        }
    }
}
