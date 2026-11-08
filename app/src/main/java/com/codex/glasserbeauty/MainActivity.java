package com.codex.glasserbeauty;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String PREFS = "glasser";
    private static final String FAVORITES_KEY = "favorites";
    private static final String HOME_TILES_KEY = "home_tiles";
    private static final String WIDGET_IDS_KEY = "widget_ids";
    private static final String THEME_KEY = "theme_mode";
    private static final String ICON_PACK_KEY = "icon_pack";
    private static final String DOCK_POSITION_KEY = "dock_position";
    private static final String HIDDEN_APPS_KEY = "hidden_apps";
    private static final String DRAWER_STYLE_KEY = "drawer_style";
    private static final int MAX_FAVORITES = 5;
    private static final int APP_WIDGET_HOST_ID = 1207;
    private static final int REQUEST_PICK_WIDGET = 2701;
    private static final int REQUEST_CONFIGURE_WIDGET = 2702;
    private static final int REQUEST_PICK_ICON = 2703;
    private static final int TEXT_MEDIUM = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppEntry> apps = new ArrayList<>();
    private final Map<String, AppEntry> appsByPackage = new HashMap<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Set<String> hiddenApps = new LinkedHashSet<>();
    private final Set<String> homeTiles = new LinkedHashSet<>();
    private final List<Integer> widgetIds = new ArrayList<>();
    private final Map<String, String> customIconUris = new HashMap<>();
    private final Map<String, String> iconPackResources = new HashMap<>();
    private String selectedIconPack = "";
    private String themeMode = "glass";
    private String dockPosition = "bottom";
    private String pendingIconPackage;

    private FrameLayout pages;
    private View rootContainer;
    private View drawerPage;
    private View settingsPage;
    private ImageView drawerBackdrop;
    private int currentPage;
    private LinearLayout dock;
    private LinearLayout homePage;
    private TextView dockLabel;
    private View dockSpacer;
    private View searchPill;
    private LinearLayout homeTileRows;
    private LinearLayout widgetContainer;
    private AppWidgetManager widgetManager;
    private AppWidgetHost widgetHost;
    private View appGrid;
    private FrameLayout appListHost;
    private String drawerStyle = "vertical";
    private EditText appSearch;
    private TextView dateText;
    private TextView timeText;
    private AppAdapter adapter;
    private float touchStartY;
    private float touchStartX;
    private float touchStartRawY;
    private float touchStartRawX;
    private float swipeDeltaY;
    private int touchStartPage;
    private boolean interactiveSwipe;
    private VelocityTracker velocityTracker;
    private boolean returnTransitionPending;
    private boolean externalWindowLostFocus;

    private final Runnable clockUpdater = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadLauncherSettings();
        configureSystemBars();
        loadApps();
        loadCustomIcons();
        loadIconPackMappings();
        loadFavorites();
        loadHomeTiles();
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, APP_WIDGET_HOST_ID);
        loadWidgetIds();
        buildInterface();
        renderDock();
        renderHomeTiles();
        renderWidgets();
        filterApps("");
        updateClock();
        handler.postDelayed(clockUpdater, 30_000L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clockUpdater);
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        if (widgetHost != null) {
            try { widgetHost.startListening(); } catch (Exception ignored) { }
        }
    }

    @Override protected void onStop() {
        if (widgetHost != null) {
            try { widgetHost.stopListening(); } catch (Exception ignored) { }
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (returnTransitionPending) {
            returnTransitionPending = false;
            externalWindowLostFocus = false;
            playReturnEntrance();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && returnTransitionPending) {
            externalWindowLostFocus = true;
        } else if (hasFocus && externalWindowLostFocus && returnTransitionPending) {
            externalWindowLostFocus = false;
            returnTransitionPending = false;
            playReturnEntrance();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showHome();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            WindowManager windowManager = getSystemService(WindowManager.class);
            if (windowManager != null && windowManager.isCrossWindowBlurEnabled()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.setBlurBehindRadius(dp(48));
                window.setAttributes(attributes);
            }
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!"midnight".equals(themeMode) && (getResources().getConfiguration().uiMode & 0x30) != 0x20) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        rootContainer = root;
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(left, top, right, bottom);
            return insets.consumeSystemWindowInsets();
        });

        pages = new FrameLayout(this);
        currentPage = 0;
        View launcherHome = createHomePage();
        pages.addView(launcherHome, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        drawerBackdrop = new ImageView(this);
        drawerBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        drawerBackdrop.setBackgroundColor(Color.argb(150, 0, 0, 0));
        drawerBackdrop.setVisibility(View.GONE);
        pages.addView(drawerBackdrop, new ViewGroup.LayoutParams(-1, -1));
        drawerPage = createDrawerPage();
        drawerPage.setVisibility(View.GONE);
        FrameLayout.LayoutParams drawerFrameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        drawerFrameParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        pages.addView(drawerPage, drawerFrameParams);
        settingsPage = createSettingsPage();
        settingsPage.setVisibility(View.GONE);
        pages.addView(settingsPage, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pages, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private View createHomePage() {
        LinearLayout home = new LinearLayout(this);
        homePage = home;
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(20), dp(18), dp(20), dp(18));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandMark = new ImageView(this);
        brandMark.setImageResource(R.drawable.ic_launcher);
        brandMark.setContentDescription("Glasser Beauty");
        brandRow.addView(brandMark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout brandCopy = new LinearLayout(this);
        brandCopy.setOrientation(LinearLayout.VERTICAL);
        brandCopy.setPadding(dp(10), 0, 0, 0);
        TextView brandName = textView("GLASSER BEAUTY", 13, R.color.text_primary, TEXT_MEDIUM);
        brandName.setLetterSpacing(0.12f);
        TextView brandSubtitle = textView("A softer kind of home", 11,
                R.color.text_secondary, Typeface.NORMAL);
        brandCopy.addView(brandName);
        brandCopy.addView(brandSubtitle);
        brandRow.addView(brandCopy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dateChip = textView("✦  YOUR SPACE", 10, R.color.accent, TEXT_MEDIUM);
        dateChip.setLetterSpacing(0.08f);
        dateChip.setGravity(Gravity.CENTER);
        dateChip.setPadding(dp(12), 0, dp(12), 0);
        dateChip.setBackground(glassSurface(dp(18)));
        brandRow.addView(dateChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        brandParams.bottomMargin = dp(14);
        home.addView(brandRow, brandParams);

        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(beautyHeroSurface(dp(32)));
        hero.setClipToOutline(true);
        hero.setElevation(dp(12));
        View halo = new View(this);
        GradientDrawable haloDrawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x58FFFFFF, 0x3CA78AFF, 0x007C5CD3});
        haloDrawable.setShape(GradientDrawable.OVAL);
        halo.setBackground(haloDrawable);
        FrameLayout.LayoutParams haloParams = new FrameLayout.LayoutParams(dp(190), dp(190),
                Gravity.RIGHT | Gravity.TOP);
        haloParams.rightMargin = -dp(40);
        haloParams.topMargin = -dp(62);
        hero.addView(halo, haloParams);
        View orbit = new View(this);
        GradientDrawable orbitDrawable = new GradientDrawable();
        orbitDrawable.setShape(GradientDrawable.OVAL);
        orbitDrawable.setColor(Color.TRANSPARENT);
        orbitDrawable.setStroke(dp(1), 0x52FFFFFF);
        orbit.setBackground(orbitDrawable);
        FrameLayout.LayoutParams orbitParams = new FrameLayout.LayoutParams(dp(126), dp(126),
                Gravity.RIGHT | Gravity.TOP);
        orbitParams.rightMargin = dp(13);
        orbitParams.topMargin = dp(17);
        hero.addView(orbit, orbitParams);
        TextView sparkle = textView("✧", 36, R.color.text_primary, Typeface.NORMAL);
        sparkle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sparkleParams = new FrameLayout.LayoutParams(dp(72), dp(72),
                Gravity.RIGHT | Gravity.TOP);
        sparkleParams.rightMargin = dp(38);
        sparkleParams.topMargin = dp(43);
        hero.addView(sparkle, sparkleParams);

        LinearLayout heroContent = new LinearLayout(this);
        heroContent.setOrientation(LinearLayout.VERTICAL);
        heroContent.setGravity(Gravity.CENTER_VERTICAL);
        heroContent.setPadding(dp(24), dp(20), dp(24), dp(20));
        TextView eyebrow = textView("A MOMENT TO YOURSELF", 10, R.color.accent, TEXT_MEDIUM);
        eyebrow.setLetterSpacing(0.15f);
        timeText = textView("", 58, R.color.text_primary, Typeface.NORMAL);
        timeText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dateText = textView("", 17, R.color.text_primary, TEXT_MEDIUM);
        TextView motto = textView("Make room for what moves you.", 12,
                R.color.text_secondary, Typeface.NORMAL);
        LinearLayout.LayoutParams heroTimeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroTimeParams.topMargin = dp(5);
        heroTimeParams.bottomMargin = dp(1);
        heroContent.addView(eyebrow);
        heroContent.addView(timeText, heroTimeParams);
        heroContent.addView(dateText);
        LinearLayout.LayoutParams mottoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mottoParams.topMargin = dp(8);
        heroContent.addView(motto, mottoParams);
        hero.addView(heroContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(212));
        home.addView(hero, heroParams);

        widgetContainer = new LinearLayout(this);
        widgetContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams widgetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        widgetParams.topMargin = dp(15);
        home.addView(widgetContainer, widgetParams);

        LinearLayout appsHeader = new LinearLayout(this);
        appsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView favoritesLabel = textView("YOUR APPS", 11, R.color.text_secondary, TEXT_MEDIUM);
        favoritesLabel.setLetterSpacing(0.12f);
        appsHeader.addView(favoritesLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView addButton = textView("＋  Add", 12, R.color.text_primary, TEXT_MEDIUM);
        addButton.setGravity(Gravity.CENTER);
        addButton.setPadding(dp(12), 0, dp(12), 0);
        addButton.setBackground(glassSurface(dp(20)));
        addButton.setOnClickListener(view -> showAddMenu());
        appsHeader.addView(addButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.bottomMargin = dp(5);
        home.addView(appsHeader, headerParams);

        homeTileRows = new LinearLayout(this);
        homeTileRows.setOrientation(LinearLayout.VERTICAL);
        home.addView(homeTileRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Space spacer = new Space(this);
        dockSpacer = spacer;
        home.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        dockLabel = textView("FAVOURITES", 11, R.color.text_secondary, TEXT_MEDIUM);
        dockLabel.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams favoritesLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        favoritesLabelParams.bottomMargin = dp(8);
        home.addView(dockLabel, favoritesLabelParams);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(8), dp(6), dp(8), dp(6));
        dock.setBackground(glassSurface(dp(32)));
        dock.setElevation(dp(12));
        home.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));

        searchPill = createSearchPill();
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        searchParams.topMargin = dp(14);
        home.addView(searchPill, searchParams);
        home.setOnLongClickListener(view -> { showSettings(); return true; });
        spacer.setOnLongClickListener(view -> { showSettings(); return true; });
        applyDockPosition();
        return home;
    }

    private View createSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(18));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = textView("‹", 34, R.color.text_primary, Typeface.NORMAL);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showHome());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = textView("Beauty settings", 22, R.color.text_primary, TEXT_MEDIUM);
        top.addView(title);
        page.addView(top);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(24));
        addSettingsRow(content, "Theme", themeLabel(), "Choose Glass, Midnight, or Rose", this::chooseTheme);
        addSettingsRow(content, "App icon pack", iconPackLabel(), "Apply an installed compatible icon pack", this::chooseIconPack);
        addSettingsRow(content, "Dock position", dockPositionLabel(), "Move the favorites dock on Home", this::chooseDockPosition);
        addSettingsRow(content, "App drawer layout", drawerStyle.equals("horizontal") ? "Horizontal pages" : "Vertical list", "Choose how apps scroll", this::chooseDrawerStyle);
        addSettingsRow(content, "Hidden apps", hiddenApps.size() + " hidden", "Choose apps hidden from the drawer", this::manageHiddenApps);
        addSettingsRow(content, "About Creator", "Glasser Beauty", "Creator information", this::showCreatorInfo);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void addSettingsRow(LinearLayout parent, String title, String value, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(13), dp(18), dp(13));
        row.setBackground(glassSurface(dp(20)));
        TextView name = textView(title, 16, R.color.text_primary, TEXT_MEDIUM);
        TextView current = textView(value, 14, R.color.accent, TEXT_MEDIUM);
        TextView description = textView(subtitle, 12, R.color.text_secondary, Typeface.NORMAL);
        row.addView(name);
        row.addView(current);
        row.addView(description);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        parent.addView(row, params);
    }

    private void showSettings() {
        if (pages != null) switchPage(2);
    }

    private void showCreatorInfo() {
        new AlertDialog.Builder(this)
                .setTitle("About Creator")
                .setMessage("Glasser Beauty is created by Intisar Rahman")
                .setPositiveButton("OK", null)
                .show();
    }

    private void loadLauncherSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        themeMode = prefs.getString(THEME_KEY, "glass");
        dockPosition = prefs.getString(DOCK_POSITION_KEY, "bottom");
        selectedIconPack = prefs.getString(ICON_PACK_KEY, "");
        drawerStyle = prefs.getString(DRAWER_STYLE_KEY, "vertical");
        hiddenApps.addAll(prefs.getStringSet(HIDDEN_APPS_KEY, new LinkedHashSet<>()));
    }

    private String themeLabel() {
        if ("midnight".equals(themeMode)) return "Midnight";
        if ("rose".equals(themeMode)) return "Rose";
        return "Glass";
    }

    private String dockPositionLabel() {
        return "top".equals(dockPosition) ? "Top · below clock" : "Bottom · above search";
    }

    private String iconPackLabel() {
        if (selectedIconPack.isEmpty()) return "System icons";
        try { return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(selectedIconPack, 0)).toString(); }
        catch (Exception ignored) { return "System icons"; }
    }

    private void chooseTheme() {
        String[] names = {"Glass", "Midnight", "Rose"};
        String[] values = {"glass", "midnight", "rose"};
        new AlertDialog.Builder(this).setTitle("Choose theme").setSingleChoiceItems(names,
                "midnight".equals(themeMode) ? 1 : ("rose".equals(themeMode) ? 2 : 0),
                (dialog, which) -> {
                    themeMode = values[which];
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(THEME_KEY, themeMode).apply();
                    dialog.dismiss();
                    rebuildForSettings();
                    switchPage(2);
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseDockPosition() {
        String[] choices = {"Bottom · above search", "Top · below clock"};
        new AlertDialog.Builder(this).setTitle("Dock position").setSingleChoiceItems(choices,
                "top".equals(dockPosition) ? 1 : 0, (dialog, which) -> {
                    dockPosition = which == 1 ? "top" : "bottom";
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(DOCK_POSITION_KEY, dockPosition).apply();
                    dialog.dismiss();
                    rebuildForSettings();
                    switchPage(2);
                }).setNegativeButton("Cancel", null).show();
    }

    private void applyDockPosition() {
        if (homePage == null || dockLabel == null || dock == null || searchPill == null) return;
        if (dockLabel.getParent() == homePage) homePage.removeView(dockLabel);
        if (dock.getParent() == homePage) homePage.removeView(dock);
        if ("top".equals(dockPosition)) {
            homePage.addView(dockLabel, 1);
            homePage.addView(dock, 2);
        } else {
            int index = homePage.indexOfChild(searchPill);
            homePage.addView(dockLabel, index);
            homePage.addView(dock, index + 1);
        }
    }

    private void rebuildForSettings() {
        configureSystemBars();
        buildInterface();
        renderDock();
        renderHomeTiles();
        renderWidgets();
        filterApps(appSearch == null ? "" : appSearch.getText().toString());
    }

    private void chooseIconPack() {
        List<ResolveInfo> packs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] actions = {"org.adw.ActivityStarter.THEMES", "org.adw.launcher.THEMES",
                "com.novalauncher.THEME", "com.anddoes.launcher.THEME",
                "com.teslacoilsw.launcher.THEME", "ch.deletescape.lawnchair.ICONPACK"};
        PackageManager pm = getPackageManager();
        for (String action : actions) {
            Intent query = new Intent(action);
            List<ResolveInfo> found;
            if (Build.VERSION.SDK_INT >= 33) found = pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0));
            else { //noinspection deprecation
                found = pm.queryIntentActivities(query, 0);
            }
            for (ResolveInfo info : found) {
                if (info.activityInfo != null && seen.add(info.activityInfo.packageName)) packs.add(info);
            }
        }
        String[] labels = new String[packs.size() + 1];
        labels[0] = "System icons";
        for (int i = 0; i < packs.size(); i++) labels[i + 1] = packs.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this).setTitle("Apply icon pack").setItems(labels, (dialog, which) -> {
            selectedIconPack = which == 0 ? "" : packs.get(which - 1).activityInfo.packageName;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ICON_PACK_KEY, selectedIconPack).apply();
            loadIconPackMappings();
            rebuildForSettings();
            switchPage(2);
        }).setNegativeButton("Cancel", null).show();
    }

    private void loadIconPackMappings() {
        iconPackResources.clear();
        if (selectedIconPack == null || selectedIconPack.isEmpty()) return;
        try {
            Resources resources = getPackageManager().getResourcesForApplication(selectedIconPack);
            int appFilter = resources.getIdentifier("appfilter", "xml", selectedIconPack);
            if (appFilter != 0) {
                XmlResourceParser parser = resources.getXml(appFilter);
                try { readIconPackFilter(parser); } finally { parser.close(); }
                return;
            }
            appFilter = resources.getIdentifier("appfilter", "raw", selectedIconPack);
            java.io.InputStream stream;
            if (appFilter != 0) stream = resources.openRawResource(appFilter);
            else stream = createPackageContext(selectedIconPack, CONTEXT_IGNORE_SECURITY)
                    .getAssets().open("appfilter.xml");
            try (java.io.InputStream input = stream) {
                XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(input, "UTF-8");
                readIconPackFilter(parser);
            }
        } catch (Exception ignored) { }
    }

    private void readIconPackFilter(XmlPullParser parser) throws Exception {
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String component = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (component == null || drawable == null) continue;
            if (component.startsWith("ComponentInfo{") && component.endsWith("}"))
                component = component.substring(14, component.length() - 1);
            int slash = component.indexOf('/');
            if (slash > 0) {
                String packageName = component.substring(0, slash);
                String activity = component.substring(slash + 1);
                if (activity.startsWith(".")) activity = packageName + activity;
                iconPackResources.put(packageName + "/" + activity, drawable);
            }
        }
    }

    private Drawable packedIconFor(AppEntry entry) {
        String drawable = iconPackResources.get(entry.packageName + "/" + entry.activityName);
        if (drawable == null) drawable = iconPackResources.get(entry.packageName + "/." + entry.activityName);
        if (drawable == null || selectedIconPack == null || selectedIconPack.isEmpty()) return null;
        try {
            Resources resources = getPackageManager().getResourcesForApplication(selectedIconPack);
            int id = resources.getIdentifier(drawable, "drawable", selectedIconPack);
            if (id == 0) id = resources.getIdentifier(drawable, "mipmap", selectedIconPack);
            return id == 0 ? null : resources.getDrawable(id, getTheme());
        } catch (Exception ignored) { return null; }
    }

    private void playReturnEntrance() {
        if (pages == null) return;
        View page = pageAt(currentPage);
        if (page == null) return;
        page.animate().cancel();
        page.setAlpha(0.62f);
        page.setScaleX(0.97f);
        page.setScaleY(0.97f);
        page.setTranslationY(dp(18));
        page.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(300L).setInterpolator(new DecelerateInterpolator(1.8f))
                .withLayer().start();
        if (currentPage == 0 && dock != null) {
            dock.setAlpha(0.55f);
            dock.setScaleX(0.92f);
            dock.setScaleY(0.92f);
            dock.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(45L).setDuration(300L)
                    .setInterpolator(new OvershootInterpolator(0.45f)).start();
        }
    }

    private View createDrawerPage() {
        LinearLayout drawer = new LinearLayout(this);
        drawer.setBackground(frostedDrawerSurface(dp(34)));
        drawer.setClipToOutline(true);
        drawer.setElevation(dp(12));
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(20), dp(15), dp(20), dp(8));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView headingLabel = textView("GLASSER BEAUTY  ·  LIBRARY", 10, R.color.accent, TEXT_MEDIUM);
        headingLabel.setLetterSpacing(0.14f);
        TextView headingTitle = textView("All apps", 27, R.color.text_primary, TEXT_MEDIUM);
        heading.addView(headingLabel);
        heading.addView(headingTitle);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        headingParams.bottomMargin = dp(10);
        drawer.addView(heading, headingParams);

        appSearch = new EditText(this);
        appSearch.setSingleLine(true);
        appSearch.setTextSize(16);
        appSearch.setTextColor(themeColor(R.color.text_primary));
        appSearch.setHintTextColor(themeColor(R.color.text_secondary));
        appSearch.setHint("Search your apps");
        appSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        appSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        appSearch.setPadding(dp(18), 0, dp(16), 0);
        appSearch.setBackground(glassSurface(dp(28)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        searchParams.bottomMargin = dp(15);
        drawer.addView(appSearch, searchParams);

        appListHost = new FrameLayout(this);
        drawer.addView(appListHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new AppAdapter();
        rebuildAppList();
        appSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        appSearch.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = appSearch.getText().toString().trim();
                if (!query.isEmpty()) {
                    searchWeb(query, appSearch);
                }
                return true;
            }
            return false;
        });
        return drawer;
    }

    private View createSearchPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), 0, dp(16), 0);
        pill.setBackground(glassSurface(dp(30)));
        pill.setElevation(dp(8));

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(android.R.drawable.ic_menu_search);
        searchIcon.setColorFilter(themeColor(R.color.accent));
        pill.addView(searchIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = textView("Search anything", 15, R.color.text_secondary, Typeface.NORMAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = dp(12);
        pill.addView(label, labelParams);
        ImageView mic = new ImageView(this);
        mic.setImageResource(android.R.drawable.ic_btn_speak_now);
        mic.setColorFilter(themeColor(R.color.accent));
        mic.setContentDescription("Voice search");
        pill.addView(mic, new LinearLayout.LayoutParams(dp(24), dp(24)));
        mic.setOnClickListener(view -> {
            try {
                startExternalActivity(new Intent(Intent.ACTION_VOICE_COMMAND), mic);
            } catch (ActivityNotFoundException exception) {
                returnTransitionPending = false;
                showDrawer(true);
            }
        });

        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setOnClickListener(view -> showDrawer(true));
        return pill;
    }

    private void loadApps() {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager manager = getPackageManager();
        List<ResolveInfo> resolved;
        if (Build.VERSION.SDK_INT >= 33) {
            resolved = manager.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0));
        } else {
            //noinspection deprecation
            resolved = manager.queryIntentActivities(query, 0);
        }
        apps.clear();
        appsByPackage.clear();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            AppEntry entry = new AppEntry(info);
            AppEntry previous = appsByPackage.get(entry.packageName);
            if (previous == null || entry.label.compareToIgnoreCase(previous.label) < 0) {
                appsByPackage.put(entry.packageName, entry);
            }
        }
        apps.addAll(appsByPackage.values());
        Collections.sort(apps, (left, right) -> left.label.compareToIgnoreCase(right.label));
    }

    private void loadFavorites() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(FAVORITES_KEY, "");
        if (!stored.isEmpty()) {
            for (String packageName : stored.split("\\|")) {
                if (appsByPackage.containsKey(packageName)) {
                    favorites.add(packageName);
                }
            }
        }
        if (favorites.isEmpty()) {
            String[] preferred = {
                    "com.oplus.camera",
                    "com.google.android.dialer",
                    "com.whatsapp.w4b",
                    "com.spotify.music",
                    "com.instagram.android"
            };
            for (String packageName : preferred) {
                if (appsByPackage.containsKey(packageName) && favorites.size() < MAX_FAVORITES) {
                    favorites.add(packageName);
                }
            }
            saveFavorites();
        }
    }

    private void loadHomeTiles() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(HOME_TILES_KEY, "");
        if (!stored.isEmpty()) for (String pkg : stored.split("\\|")) {
            if (appsByPackage.containsKey(pkg)) homeTiles.add(pkg);
        }
    }

    private void loadCustomIcons() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (AppEntry entry : apps) {
            String uri = prefs.getString("custom_icon_" + entry.packageName, null);
            if (uri != null) customIconUris.put(entry.packageName, uri);
        }
    }

    private Drawable iconFor(AppEntry entry) {
        String raw = customIconUris.get(entry.packageName);
        if (raw != null) {
            try {
                Uri uri = Uri.parse(raw);
                try (java.io.InputStream stream = getContentResolver().openInputStream(uri)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap != null) return new BitmapDrawable(getResources(), bitmap);
                }
            } catch (Exception ignored) { }
        }
        Drawable packed = packedIconFor(entry);
        if (packed != null) return packed;
        return entry.icon;
    }

    private void chooseCustomIcon(AppEntry entry) {
        pendingIconPackage = entry.packageName;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker, REQUEST_PICK_ICON); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No image picker is available", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearCustomIcon(AppEntry entry) {
        customIconUris.remove(entry.packageName);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("custom_icon_" + entry.packageName).apply();
        refreshAppIcons();
    }

    private void refreshAppIcons() {
        renderDock();
        renderHomeTiles();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void saveHomeTiles() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(HOME_TILES_KEY, String.join("|", homeTiles)).apply();
    }

    private void loadWidgetIds() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(WIDGET_IDS_KEY, "");
        if (!stored.isEmpty()) for (String id : stored.split(",")) {
            try { widgetIds.add(Integer.parseInt(id)); } catch (NumberFormatException ignored) { }
        }
    }

    private void saveWidgetIds() {
        StringBuilder value = new StringBuilder();
        for (Integer id : widgetIds) { if (value.length() > 0) value.append(','); value.append(id); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(WIDGET_IDS_KEY, value.toString()).apply();
    }

    private void renderHomeTiles() {
        if (homeTileRows == null) return;
        homeTileRows.removeAllViews();
        List<AppEntry> entries = new ArrayList<>();
        for (String pkg : homeTiles) if (appsByPackage.containsKey(pkg)) entries.add(appsByPackage.get(pkg));
        for (int i = 0; i < entries.size(); i += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(78));
            for (int j = i; j < Math.min(i + 4, entries.size()); j++) {
                AppEntry entry = entries.get(j);
                AppTile tile = new AppTile(this);
                tile.bind(entry, false);
                tile.setOnClickListener(v -> launchApp(entry, tile));
                tile.setOnLongClickListener(v -> { showHomeTileMenu(entry); return true; });
                row.addView(tile, new LinearLayout.LayoutParams(0, dp(78), 1f));
            }
            homeTileRows.addView(row, rowParams);
        }
    }

    private void showAddMenu() {
        new AlertDialog.Builder(this).setTitle("Add to home")
                .setItems(new String[]{"App", "Widget"}, (dialog, which) -> {
                    if (which == 0) showAppPicker(); else pickWidget();
                }).show();
    }

    private void showAppPicker() {
        List<AppEntry> choices = new ArrayList<>();
        for (AppEntry entry : apps) if (!homeTiles.contains(entry.packageName)) choices.add(entry);
        if (choices.isEmpty()) {
            Toast.makeText(this, "All available apps are already on Home", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(this).setTitle("Add app tile").setItems(labels, (dialog, which) -> {
            homeTiles.add(choices.get(which).packageName);
            saveHomeTiles();
            renderHomeTiles();
        }).show();
    }

    private void showHomeTileMenu(AppEntry entry) {
        List<String> actions = new ArrayList<>();
        actions.add("Remove from Home");
        actions.add(hiddenApps.contains(entry.packageName) ? "Show in drawer" : "Hide app");
        actions.add("Change icon");
        if (customIconUris.containsKey(entry.packageName)) actions.add("Use app icon");
        actions.add("App info");
        new AlertDialog.Builder(this).setTitle(entry.label).setItems(actions.toArray(new String[0]), (dialog, which) -> {
            String action = actions.get(which);
            if ("Remove from Home".equals(action)) { homeTiles.remove(entry.packageName); saveHomeTiles(); renderHomeTiles(); }
            else if ("Change icon".equals(action)) chooseCustomIcon(entry);
            else if ("Use app icon".equals(action)) clearCustomIcon(entry);
            else openAppInfo(entry.packageName, null);
        }).show();
    }

    private void pickWidget() {
        int id = widgetHost.allocateAppWidgetId();
        Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        try { startActivityForResult(pick, REQUEST_PICK_WIDGET); }
        catch (ActivityNotFoundException e) { widgetHost.deleteAppWidgetId(id); Toast.makeText(this, "Widget picker unavailable", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_ICON) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingIconPackage != null) {
                Uri uri = data.getData();
                try { getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                customIconUris.put(pendingIconPackage, uri.toString());
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("custom_icon_" + pendingIconPackage, uri.toString()).apply();
                refreshAppIcons();
            }
            pendingIconPackage = null;
            return;
        }
        if (requestCode != REQUEST_PICK_WIDGET && requestCode != REQUEST_CONFIGURE_WIDGET) return;
        int id = data == null ? -1 : data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (requestCode == REQUEST_PICK_WIDGET && resultCode == RESULT_OK && id >= 0) {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info != null && info.configure != null) {
                Intent config = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                config.setComponent(info.configure);
                config.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                try { startActivityForResult(config, REQUEST_CONFIGURE_WIDGET); }
                catch (ActivityNotFoundException e) { addWidget(id); }
            } else addWidget(id);
        } else if (resultCode == RESULT_OK && id >= 0) addWidget(id);
        else if (id >= 0) widgetHost.deleteAppWidgetId(id);
    }

    private void addWidget(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info == null) { widgetHost.deleteAppWidgetId(id); return; }
        if (!widgetIds.contains(id)) widgetIds.add(id);
        saveWidgetIds();
        addWidgetView(id, info);
    }

    private void renderWidgets() {
        if (widgetContainer == null) return;
        widgetContainer.removeAllViews();
        LinearLayout halfRow = null;
        for (Integer id : new ArrayList<>(widgetIds)) {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info != null) {
                int widthMode = getWidgetWidthMode(id);
                if (widthMode == 1) {
                    if (halfRow == null || halfRow.getChildCount() >= 2) {
                        halfRow = new LinearLayout(this);
                        halfRow.setOrientation(LinearLayout.HORIZONTAL);
                        widgetContainer.addView(halfRow, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                    addWidgetView(id, info, halfRow, true);
                } else {
                    halfRow = null;
                    addWidgetView(id, info, widgetContainer, false);
                }
            }
            else { widgetIds.remove(id); widgetHost.deleteAppWidgetId(id); }
        }
        saveWidgetIds();
    }

    private int getWidgetWidthMode(int id) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("widget_width_" + id, 0);
    }

    private int getWidgetHeightMode(int id) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("widget_height_" + id, 1);
    }

    private void addWidgetView(int id, AppWidgetProviderInfo info) {
        addWidgetView(id, info, widgetContainer, false);
    }

    private void addWidgetView(int id, AppWidgetProviderInfo info, LinearLayout parent, boolean halfWidth) {
        AppWidgetHostView hostView = widgetHost.createView(this, id, info);
        hostView.setAppWidget(id, info);
        hostView.setOnLongClickListener(view -> {
            new AlertDialog.Builder(this).setTitle("Widget options")
                    .setItems(new String[]{"Resize widget", "Remove widget"}, (dialog, which) -> {
                        if (which == 0) showWidgetSizePicker(id);
                        else new AlertDialog.Builder(this).setMessage("Remove this widget?")
                                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> {
                        ViewGroup owner = (ViewGroup) hostView.getParent();
                        if (owner != null) owner.removeView(hostView);
                        widgetIds.remove(Integer.valueOf(id));
                        widgetHost.deleteAppWidgetId(id);
                        saveWidgetIds();
                        if (owner != null && owner != widgetContainer && owner.getChildCount() == 0)
                            widgetContainer.removeView(owner);
                    }).show();
                    }).show();
            return true;
        });
        int heightMode = getWidgetHeightMode(id);
        int heightDp = heightMode == 0 ? 120 : (heightMode == 2 ? 260 : 185);
        int screenWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density) - 48;
        int widthDp = halfWidth ? Math.max(120, screenWidthDp / 2 - 6) : screenWidthDp;
        Bundle options = widgetManager.getAppWidgetOptions(id);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
        widgetManager.updateAppWidgetOptions(id, options);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                halfWidth ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp), halfWidth ? 1f : 0f);
        if (halfWidth) params.setMargins(0, 0, dp(5), dp(8));
        else params.bottomMargin = dp(8);
        parent.addView(hostView, params);
    }

    private void showWidgetSizePicker(int id) {
        String[] sizes = {"Full width · Compact", "Full width · Medium", "Full width · Tall",
                "Half width · Compact", "Half width · Medium", "Half width · Tall"};
        new AlertDialog.Builder(this).setTitle("Resize widget").setItems(sizes, (dialog, which) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("widget_width_" + id, which < 3 ? 0 : 1)
                    .putInt("widget_height_" + id, which % 3).apply();
            renderWidgets();
        }).show();
    }

    private void saveFavorites() {
        StringBuilder stored = new StringBuilder();
        for (String packageName : favorites) {
            if (stored.length() > 0) stored.append('|');
            stored.append(packageName);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(FAVORITES_KEY, stored.toString()).apply();
    }

    private void renderDock() {
        if (dock == null) return;
        dock.removeAllViews();
        List<AppEntry> pinned = new ArrayList<>();
        for (String packageName : favorites) {
            AppEntry entry = appsByPackage.get(packageName);
            if (entry != null) pinned.add(entry);
        }
        if (pinned.isEmpty()) {
            TextView empty = textView("Long-press an app to add a favorite", 13,
                    R.color.text_secondary, Typeface.NORMAL);
            dock.addView(empty);
            return;
        }
        for (AppEntry entry : pinned) {
            AppTile tile = new AppTile(this);
            tile.bind(entry, true);
            tile.setOnClickListener(view -> launchApp(entry, view));
            tile.setOnLongClickListener(view -> {
                showFavoriteMenu(entry, view);
                return true;
            });
            dock.addView(tile, new LinearLayout.LayoutParams(0, dp(78), 1f));
        }
    }

    private void filterApps(String rawQuery) {
        if (adapter == null) return;
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.getDefault());
        List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry entry : apps) {
            if (!hiddenApps.contains(entry.packageName)
                    && (query.isEmpty() || entry.label.toLowerCase(Locale.getDefault()).contains(query))) {
                filtered.add(entry);
            }
        }
        adapter.setItems(filtered);
        if ("horizontal".equals(drawerStyle)) renderHorizontalPages();
    }

    private void rebuildAppList() {
        if (appListHost == null) return;
        appListHost.removeAllViews();
        if (drawerStyle.equals("horizontal")) {
            HorizontalScrollView pager = new PagedAppScrollView(this);
            pager.setHorizontalScrollBarEnabled(false);
            pager.setFillViewport(true);
            pager.setClipToPadding(false);
            appGrid = pager;
        } else {
            GridView grid = new GridView(this);
            grid.setNumColumns(4);
            grid.setVerticalSpacing(dp(10));
            grid.setHorizontalSpacing(dp(2));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            appGrid = grid;
        }
        appGrid.setPadding(0, dp(4), 0, dp(20));
        if (appGrid instanceof GridView) {
            ((GridView) appGrid).setSelector(android.R.color.transparent);
            ((GridView) appGrid).setAdapter(adapter);
        } else ((HorizontalScrollView) appGrid).post(this::renderHorizontalPages);
        appListHost.addView(appGrid, new FrameLayout.LayoutParams(-1, -1));
        filterApps(appSearch == null ? "" : appSearch.getText().toString());
    }

    private void renderHorizontalPages() {
        if (!(appGrid instanceof HorizontalScrollView) || appListHost == null || adapter == null) return;
        HorizontalScrollView scroll = (HorizontalScrollView) appGrid;
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int pageWidth = appListHost.getWidth();
        int pageHeight = appListHost.getHeight();
        if (pageWidth <= 0 || pageHeight <= 0) {
            appListHost.post(this::renderHorizontalPages);
            return;
        }
        int pageCount = Math.max(1, (adapter.getCount() + 19) / 20);
        for (int page = 0; page < pageCount; page++) {
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(4);
            grid.setRowCount(5);
            grid.setUseDefaultMargins(false);
            strip.addView(grid, new LinearLayout.LayoutParams(pageWidth, pageHeight));
            for (int slot = page * 20; slot < Math.min(adapter.getCount(), (page + 1) * 20); slot++) {
                AppEntry entry = adapter.getItem(slot);
                AppTile tile = new AppTile(this);
                tile.bind(entry, false);
                tile.setOnClickListener(v -> launchApp(entry, tile));
                tile.setOnLongClickListener(v -> { showFavoriteMenu(entry, tile); return true; });
                GridLayout.LayoutParams cell = new GridLayout.LayoutParams(
                        GridLayout.spec(slot % 20 / 4, 1f), GridLayout.spec(slot % 4, 1f));
                cell.width = 0;
                cell.height = pageHeight / 5;
                grid.addView(tile, cell);
            }
        }
        scroll.removeAllViews();
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, -1));
        scroll.scrollTo(0, 0);
    }

    private final class PagedAppScrollView extends HorizontalScrollView {
        private float downX;
        private int startPage;
        PagedAppScrollView(Context context) { super(context); }
        @Override public void fling(int velocityX) { }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                int stride = getChildPageWidth();
                startPage = stride == 0 ? 0 : Math.round((float) getScrollX() / stride);
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float distance = event.getX() - downX;
                int stride = getChildPageWidth();
                int pages = getChildCount() == 0 ? 0 : ((ViewGroup) getChildAt(0)).getChildCount() - 1;
                int page = Math.abs(distance) > dp(40)
                        ? startPage + (distance < 0 ? 1 : -1)
                        : stride == 0 ? startPage : Math.round((float) getScrollX() / stride);
                int target = Math.max(0, Math.min(pages, page));
                post(() -> smoothScrollTo(target * Math.max(1, getChildPageWidth()), 0));
            }
            return handled;
        }
        private int getChildPageWidth() {
            if (getChildCount() == 0) return getWidth();
            View strip = getChildAt(0);
            return strip instanceof ViewGroup && ((ViewGroup) strip).getChildCount() > 0
                    ? ((ViewGroup) strip).getChildAt(0).getWidth() : getWidth();
        }
    }

    private void chooseDrawerStyle() {
        String[] options = {"Vertical (default)", "Horizontal pages"};
        new AlertDialog.Builder(this).setTitle("App drawer layout").setItems(options, (d, which) -> {
            drawerStyle = which == 1 ? "horizontal" : "vertical";
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(DRAWER_STYLE_KEY, drawerStyle).apply();
            rebuildAppList();
            showSettings();
        }).show();
    }

    private void manageHiddenApps() {
        if (apps.isEmpty()) return;
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry e = apps.get(i); labels[i] = e.label; checked[i] = hiddenApps.contains(e.packageName);
        }
        new AlertDialog.Builder(this).setTitle("Hidden apps").setMultiChoiceItems(labels, checked, (d, which, on) -> {
            String pkg = apps.get(which).packageName;
            if (on) hiddenApps.add(pkg); else hiddenApps.remove(pkg);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(HIDDEN_APPS_KEY, new LinkedHashSet<>(hiddenApps)).apply();
            filterApps(appSearch == null ? "" : appSearch.getText().toString());
        }).setPositiveButton("Done", (d, w) -> { rebuildSettingsPage(); }).show();
    }

    private void rebuildSettingsPage() {
        if (pages == null) return;
        View old = settingsPage;
        int index = pages.indexOfChild(old);
        pages.removeView(old);
        settingsPage = createSettingsPage();
        settingsPage.setVisibility(View.VISIBLE);
        pages.addView(settingsPage, index, new ViewGroup.LayoutParams(-1, -1));
        currentPage = 2;
    }

    private void showFavoriteMenu(AppEntry entry, View origin) {
        boolean isFavorite = favorites.contains(entry.packageName);
        List<String> actions = new ArrayList<>();
        actions.add(isFavorite ? "Remove from favorites" : "Add to favorites");
        actions.add("Change icon");
        if (customIconUris.containsKey(entry.packageName)) actions.add("Use app icon");
        actions.add("App info");
        new AlertDialog.Builder(this)
                .setTitle(entry.label)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    if (action.endsWith("favorites")) toggleFavorite(entry);
                    else if ("Hide app".equals(action) || "Show in drawer".equals(action)) {
                        if (!hiddenApps.remove(entry.packageName)) hiddenApps.add(entry.packageName);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(HIDDEN_APPS_KEY, new LinkedHashSet<>(hiddenApps)).apply();
                        filterApps(appSearch == null ? "" : appSearch.getText().toString());
                    }
                    else if ("Change icon".equals(action)) chooseCustomIcon(entry);
                    else if ("Use app icon".equals(action)) clearCustomIcon(entry);
                    else openAppInfo(entry.packageName, origin);
                })
                .show();
    }

    private void toggleFavorite(AppEntry entry) {
        if (favorites.contains(entry.packageName)) {
            favorites.remove(entry.packageName);
        } else if (favorites.size() >= MAX_FAVORITES) {
            Toast.makeText(this, "The dock holds up to five apps", Toast.LENGTH_SHORT).show();
            return;
        } else {
            favorites.add(entry.packageName);
        }
        saveFavorites();
        renderDock();
        Toast.makeText(this, favorites.contains(entry.packageName)
                ? "Added to favorites" : "Removed from favorites", Toast.LENGTH_SHORT).show();
    }

    private void launchApp(AppEntry entry, View origin) {
        if (entry == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(entry.packageName);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(new ComponentName(entry.packageName, entry.activityName));
        }
        try {
            startExternalActivity(launch, origin);
        } catch (ActivityNotFoundException | SecurityException exception) {
            returnTransitionPending = false;
            Toast.makeText(this, "Could not open " + entry.label, Toast.LENGTH_SHORT).show();
            loadApps();
            renderDock();
            filterApps(appSearch == null ? "" : appSearch.getText().toString());
        }
    }

    private void openAppInfo(String packageName, View origin) {
        Intent info = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName));
        startExternalActivity(info, origin);
    }

    private void searchWeb(String query, View origin) {
        Intent webSearch = new Intent(Intent.ACTION_WEB_SEARCH);
        webSearch.putExtra(android.app.SearchManager.QUERY, query);
        try {
            startExternalActivity(webSearch, origin);
        } catch (ActivityNotFoundException exception) {
            Intent browser = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            try {
                startExternalActivity(browser, origin);
            } catch (ActivityNotFoundException ignored) {
                returnTransitionPending = false;
                Toast.makeText(this, "No browser is available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startExternalActivity(Intent intent, View origin) {
        returnTransitionPending = true;
        if (origin != null && origin.isAttachedToWindow()
                && origin.getWidth() > 0 && origin.getHeight() > 0) {
            Bundle options = ActivityOptions.makeScaleUpAnimation(origin, 0, 0,
                    origin.getWidth(), origin.getHeight()).toBundle();
            try {
                startActivity(intent, options);
            } catch (RuntimeException animationFailure) {
                startActivity(intent);
            }
        } else {
            startActivity(intent);
        }
    }

    private void showDrawer(boolean showKeyboard) {
        if (rootContainer != null) rootContainer.setBackgroundColor(Color.argb(150, 0, 0, 0));
        switchPage(1);
        if (showKeyboard) {
            appSearch.requestFocus();
            appSearch.postDelayed(() -> {
                InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (input != null) input.showSoftInput(appSearch, InputMethodManager.SHOW_IMPLICIT);
            }, 120L);
        }
    }

    private void captureDrawerBackdrop() {
        if (drawerBackdrop == null || homePage == null || homePage.getWidth() <= 0 || homePage.getHeight() <= 0) return;
        Bitmap snapshot = Bitmap.createBitmap(homePage.getWidth(), homePage.getHeight(), Bitmap.Config.ARGB_8888);
        homePage.draw(new Canvas(snapshot));
        drawerBackdrop.setImageBitmap(snapshot);
        drawerBackdrop.setColorFilter(Color.argb(115, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
        if (Build.VERSION.SDK_INT >= 31) {
            drawerBackdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(22), dp(22), Shader.TileMode.CLAMP));
        }
        drawerBackdrop.setVisibility(View.VISIBLE);
    }

    private void showHome() {
        if (appSearch != null) {
            InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (input != null) input.hideSoftInputFromWindow(appSearch.getWindowToken(), 0);
            appSearch.setText("");
        }
        switchPage(0);
    }

    private void switchPage(int target) {
        if (pages == null || currentPage == target) return;
        if (rootContainer != null) rootContainer.setBackgroundColor(target == 1
                ? Color.argb(150, 0, 0, 0) : Color.TRANSPARENT);
        if (target == 1 && currentPage == 0) captureDrawerBackdrop();
        View outgoing = pageAt(currentPage);
        View incoming = pageAt(target);
        if (outgoing == null || incoming == null) return;
        outgoing.animate().cancel();
        incoming.animate().cancel();
        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);
        incoming.setScaleX(target == 1 ? 0.965f : 0.985f);
        incoming.setScaleY(target == 1 ? 0.965f : 0.985f);
        incoming.setTranslationY(target == 1 ? pages.getHeight() * 0.075f : dp(12));
        long duration = target == 1 ? 255L : 190L;
        boolean openingDrawer = target == 1;
        currentPage = target;
        incoming.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(duration).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        outgoing.animate().translationY(openingDrawer ? -dp(14) : dp(8))
                .scaleX(openingDrawer ? 0.99f : 0.985f).scaleY(openingDrawer ? 0.99f : 0.985f)
                .alpha(openingDrawer ? 0.9f : 0f).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> {
                    if (currentPage != target) return;
                    outgoing.setVisibility(View.GONE);
                    if (target != 1 && drawerBackdrop != null) drawerBackdrop.setVisibility(View.GONE);
                    outgoing.setAlpha(1f);
                    outgoing.setScaleX(1f);
                    outgoing.setScaleY(1f);
                    outgoing.setTranslationY(0f);
                }).start();
    }

    private View pageAt(int index) {
        if (index == 0) return homePage;
        if (index == 1) return drawerPage;
        if (index == 2) return settingsPage;
        return null;
    }

    private void updateClock() {
        if (timeText == null || dateText == null) return;
        Date now = new Date();
        String timePattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        timeText.setText(new SimpleDateFormat(timePattern, Locale.getDefault()).format(now));
        String datePattern = DateFormat.is24HourFormat(this) ? "EEEE, d MMM" : "EEEE, MMM d";
        dateText.setText(new SimpleDateFormat(datePattern, Locale.getDefault()).format(now));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchStartY = event.getY();
            touchStartX = event.getX();
            touchStartRawY = event.getRawY();
            touchStartRawX = event.getRawX();
            touchStartPage = currentPage;
            swipeDeltaY = 0f;
            if (velocityTracker != null) velocityTracker.recycle();
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
        } else if (velocityTracker != null) {
            velocityTracker.addMovement(event);
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                boolean validDirection = touchStartPage == 0 ? dy < -dp(8)
                        : touchStartPage == 1 && startedInsideAppGrid() && appGridAtTop() && dy > dp(8);
                if (!interactiveSwipe && validDirection && Math.abs(dy) > Math.abs(dx) * 1.15f) {
                    beginInteractiveSwipe(touchStartPage == 0);
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                if (interactiveSwipe) {
                    swipeDeltaY = dy;
                    updateInteractiveSwipe();
                    return true;
                }
            } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && interactiveSwipe) {
                boolean opening = touchStartPage == 0;
                velocityTracker.computeCurrentVelocity(1000);
                float velocityY = velocityTracker.getYVelocity();
                float distance = Math.abs(swipeDeltaY);
                boolean fling = opening ? velocityY < -850f : velocityY > 850f;
                boolean commit = action == MotionEvent.ACTION_UP
                        && (distance > Math.min(dp(130), pages.getHeight() * 0.25f)
                        || (fling && distance > dp(35)));
                settleInteractiveSwipe(commit, opening);
                recycleVelocityTracker();
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                recycleVelocityTracker();
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void beginInteractiveSwipe(boolean opening) {
        if (pages == null || homePage == null || drawerPage == null) return;
        interactiveSwipe = true;
        int height = Math.max(1, pages.getHeight());
        if (opening) {
            drawerPage.setVisibility(View.VISIBLE);
            drawerPage.setTranslationY(height);
            drawerPage.setAlpha(0.72f);
            drawerPage.setScaleX(0.965f);
            drawerPage.setScaleY(0.965f);
            homePage.setVisibility(View.VISIBLE);
            homePage.setTranslationY(0f);
            homePage.setAlpha(1f);
            homePage.setScaleX(1f);
            homePage.setScaleY(1f);
        } else {
            homePage.setVisibility(View.VISIBLE);
            homePage.setTranslationY(-dp(18));
            homePage.setAlpha(0.86f);
            homePage.setScaleX(0.975f);
            homePage.setScaleY(0.975f);
            drawerPage.setVisibility(View.VISIBLE);
            drawerPage.setTranslationY(0f);
            drawerPage.setAlpha(1f);
            drawerPage.setScaleX(1f);
            drawerPage.setScaleY(1f);
        }
    }

    private boolean startedInsideAppGrid() {
        if (appGrid == null) return false;
        Rect bounds = new Rect();
        return appGrid.getGlobalVisibleRect(bounds)
                && bounds.contains(Math.round(touchStartRawX), Math.round(touchStartRawY));
    }

    private boolean appGridAtTop() {
        if (appGrid instanceof HorizontalScrollView) return !appGrid.canScrollHorizontally(-1);
        return appGrid == null || !appGrid.canScrollVertically(-1);
    }

    private void updateInteractiveSwipe() {
        int height = Math.max(1, pages.getHeight());
        boolean opening = touchStartPage == 0;
        float progress = Math.max(0f, Math.min(1f, Math.abs(swipeDeltaY) / height));
        int scrimAlpha = Math.round((opening ? progress : 1f - progress) * 150f);
        if (rootContainer != null) rootContainer.setBackgroundColor(Color.argb(scrimAlpha, 0, 0, 0));
        if (opening) {
            drawerPage.setTranslationY(height * (1f - progress));
            drawerPage.setAlpha(0.72f + 0.28f * progress);
            drawerPage.setScaleX(0.965f + 0.035f * progress);
            drawerPage.setScaleY(0.965f + 0.035f * progress);
            homePage.setTranslationY(-height * 0.025f * progress);
            homePage.setScaleX(1f - 0.025f * progress);
            homePage.setScaleY(1f - 0.025f * progress);
            homePage.setAlpha(1f - 0.12f * progress);
        } else {
            drawerPage.setTranslationY(height * progress);
            drawerPage.setAlpha(1f - 0.18f * progress);
            drawerPage.setScaleX(1f - 0.02f * progress);
            drawerPage.setScaleY(1f - 0.02f * progress);
            homePage.setTranslationY(-height * 0.025f * (1f - progress));
            homePage.setScaleX(0.975f + 0.025f * progress);
            homePage.setScaleY(0.975f + 0.025f * progress);
            homePage.setAlpha(0.86f + 0.14f * progress);
        }
    }

    private void settleInteractiveSwipe(boolean commit, boolean opening) {
        if (!interactiveSwipe) return;
        interactiveSwipe = false;
        if (!commit) {
            View restorePage = opening ? homePage : drawerPage;
            View cancelPage = opening ? drawerPage : homePage;
            if (rootContainer != null) rootContainer.setBackgroundColor(opening
                    ? Color.argb(150, 0, 0, 0) : Color.TRANSPARENT);
            restorePage.animate().cancel();
            cancelPage.animate().cancel();
            restorePage.setVisibility(View.VISIBLE);
            restorePage.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(170L).setInterpolator(new DecelerateInterpolator(2f)).start();
            cancelPage.animate().translationY(opening ? pages.getHeight() : dp(18))
                    .alpha(0f).scaleX(0.98f).scaleY(0.98f).setDuration(170L)
                    .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(() -> {
                        cancelPage.setVisibility(View.GONE);
                        resetPageTransform(cancelPage);
                    }).start();
            return;
        }
        if (opening) captureDrawerBackdrop();
        currentPage = opening ? 1 : 0;
        View incoming = opening ? drawerPage : homePage;
        View outgoing = opening ? homePage : drawerPage;
        if (rootContainer != null) rootContainer.setBackgroundColor(opening
                ? Color.argb(150, 0, 0, 0) : Color.TRANSPARENT);
        if (!opening) dismissDrawerSearch();
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(145L).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        outgoing.animate().translationY(opening ? -dp(18) : pages.getHeight())
                .alpha(opening ? 0.9f : 0f).scaleX(0.985f).scaleY(0.985f)
                .setDuration(145L).setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(() -> {
                    outgoing.setVisibility(View.GONE);
                    if (!opening && drawerBackdrop != null) drawerBackdrop.setVisibility(View.GONE);
                    resetPageTransform(outgoing);
                }).start();
    }

    private void resetPageTransform(View page) {
        page.setAlpha(1f);
        page.setScaleX(1f);
        page.setScaleY(1f);
        page.setTranslationY(0f);
    }

    private void dismissDrawerSearch() {
        if (appSearch == null) return;
        InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(appSearch.getWindowToken(), 0);
        appSearch.clearFocus();
        appSearch.setText("");
    }

    private void recycleVelocityTracker() {
        interactiveSwipe = false;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (pages != null && currentPage != 0) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    private TextView textView(String text, float sizeSp, int colorId, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(themeColor(colorId));
        view.setTypeface(style == TEXT_MEDIUM
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.create("sans-serif", style));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable glassSurface(int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{themeColor(R.color.glass_top), themeColor(R.color.glass_bottom)});
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), themeColor(R.color.glass_stroke));
        return drawable;
    }

    private GradientDrawable beautyHeroSurface(int radius) {
        boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
        int[] colors = dark
                ? new int[]{0xDB453A61, 0xCC28243B, 0xD13F304F}
                : new int[]{0xEAF8F2FF, 0xD7E9E0FF, 0xD6FBEAF4};
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), dark ? 0xB0FFFFFF : 0xF0FFFFFF);
        return drawable;
    }

    private GradientDrawable frostedDrawerSurface(int radius) {
        int canvas = themeColor(R.color.canvas);
        int[] colors = {Color.argb(0xD8, Color.red(canvas), Color.green(canvas), Color.blue(canvas)),
                Color.argb(0xCC, Color.red(canvas), Color.green(canvas), Color.blue(canvas))};
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), themeColor(R.color.glass_stroke));
        return drawable;
    }

    private int themeColor(int resourceId) {
        if ("midnight".equals(themeMode)) {
            if (resourceId == R.color.canvas || resourceId == R.color.surface) return Color.rgb(23, 24, 31);
            if (resourceId == R.color.text_primary) return Color.rgb(245, 243, 250);
            if (resourceId == R.color.text_secondary) return Color.rgb(193, 190, 203);
            if (resourceId == R.color.accent) return Color.rgb(178, 184, 255);
            if (resourceId == R.color.glass_top) return 0xB8333540;
            if (resourceId == R.color.glass_bottom) return 0x9930303A;
            if (resourceId == R.color.glass_stroke) return 0x88FFFFFF;
        } else if ("rose".equals(themeMode)) {
            if (resourceId == R.color.canvas || resourceId == R.color.surface) return Color.rgb(255, 241, 246);
            if (resourceId == R.color.text_primary) return Color.rgb(61, 36, 48);
            if (resourceId == R.color.text_secondary) return Color.rgb(119, 83, 100);
            if (resourceId == R.color.accent) return Color.rgb(183, 70, 116);
            if (resourceId == R.color.glass_top) return 0xD9FFF7FA;
            if (resourceId == R.color.glass_bottom) return 0xA6F4DFE8;
            if (resourceId == R.color.glass_stroke) return 0xDDFFFFFF;
        }
        return getColor(resourceId);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> visible = new ArrayList<>();

        void setItems(List<AppEntry> items) {
            visible.clear();
            visible.addAll(items);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return visible.size(); }
        @Override public AppEntry getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).packageName.hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppTile tile = convertView instanceof AppTile ? (AppTile) convertView : new AppTile(MainActivity.this);
            AppEntry entry = getItem(position);
            tile.bind(entry, false);
            tile.setOnClickListener(view -> launchApp(entry, tile));
            tile.setOnLongClickListener(view -> {
                showFavoriteMenu(entry, tile);
                return true;
            });
            return tile;
        }
    }

    private final class AppTile extends LinearLayout {
        private final ImageView icon;
        private final TextView label;

        AppTile(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(3), dp(6), dp(3), dp(6));
            setClickable(true);
            setFocusable(true);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            label = new TextView(context);
            label.setTextSize(11);
            label.setTextColor(themeColor(R.color.text_primary));
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(5);
            addView(label, labelParams);
        }

        void bind(AppEntry entry, boolean dockTile) {
            icon.setImageDrawable(iconFor(entry));
            setContentDescription(entry.label);
            int iconSize = dockTile ? 44 : 48;
            ViewGroup.LayoutParams iconParams = icon.getLayoutParams();
            iconParams.width = dp(iconSize);
            iconParams.height = dp(iconSize);
            icon.setLayoutParams(iconParams);
            label.setText(entry.label);
            label.setVisibility(View.VISIBLE);
            label.setTextSize(dockTile ? 10 : 11);
        }
    }

    private final class AppEntry {
        final String packageName;
        final String activityName;
        final String label;
        final android.graphics.drawable.Drawable icon;

        AppEntry(ResolveInfo info) {
            packageName = info.activityInfo.packageName;
            activityName = info.activityInfo.name;
            CharSequence loadedLabel = info.loadLabel(getPackageManager());
            label = loadedLabel == null ? packageName : loadedLabel.toString();
            icon = info.loadIcon(getPackageManager());
        }
    }
}
