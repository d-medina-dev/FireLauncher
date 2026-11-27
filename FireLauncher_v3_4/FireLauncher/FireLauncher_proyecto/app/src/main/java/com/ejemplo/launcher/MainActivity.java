package com.ejemplo.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Launcher para Fire TV / Android TV.
 * Muestra las apps instaladas con su logo real, la red (Wi-Fi o Ethernet), el clima real,
 * la hora del sistema y las apps de streaming por uso. Pasa las teclas del mando a la interfaz:
 * OK corto abre, OK mantenido mueve, Volver mantenido 10 s sale del launcher.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Launcher";
    private static final int MAX_APPS = 24;
    private static final int SRC_PX = 192;   // se dibuja grande para recortar sin perder nitidez
    private static final int ICON_PX = 144;  // tamaño final del logo
    private static final int REQ_LOCATION = 7, REQ_EPG = 8, REQ_LOC2 = 9;
    private static final String EPG = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final int REQ_ROLE = 8;
    private static final long OK_LONG_MS = 650;
    private static final long EXIT_HOLD_MS = 10000;
    private static final int MAX_LOCATION_ASKS = 3;
    // Open-Meteo actualiza el tiempo "actual" cada 15 minutos: así el estado cambia durante el día (como en el teléfono).
    private static final long WX_MAX_AGE = 15L * 60L * 1000L;
    private static final long WX_RETRY = 2L * 60L * 1000L;

    private WebView web;

    private boolean pageReady = false;
    private String lastSig = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    /** Por paquete: {icono en data URL, color 1, color 2}. */
    private final Map<String, String[]> iconCache = new HashMap<>();
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    /** Hay un aviso del sistema abierto (permisos o launcher principal): nadie debe relanzar el launcher. */
    static volatile boolean awaiting = false;
    /** El launcher está en primer plano. */
    static volatile boolean resumed = false;
    private boolean pageLoaded = false;
    private volatile long lastHttpDate = 0;
    private volatile long lastHttpAt = 0;

    /** Pantalla mientras esperas responder al aviso de permisos. */
    private static final String WAIT_HTML = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=1280'></head>"
            + "<body style='margin:0;background:#131640;color:#fff;font-family:sans-serif;height:100vh;"
            + "display:flex;align-items:center;justify-content:center'><div style='text-align:center'>"
            + "<div style='font-size:40px;font-weight:600'>Preparando el launcher</div>"
            + "<div style='font-size:24px;opacity:.8;margin-top:14px'>Responde al aviso de permisos para continuar.</div>"
            + "</div></body></html>";

    private ConnectivityManager.NetworkCallback netCallback;
    private BroadcastReceiver wifiReceiver;
    private BroadcastReceiver timeReceiver;
    private boolean okLongFired = false;
    private long backDownAt = 0;
    private boolean exitFired = false;
    private final Runnable okLong = () -> {
        okLongFired = true;
        js("window.__okLong&&window.__okLong()");
    };
    private final Runnable backHoldTick = new Runnable() {
        @Override
        public void run() {
            long held = SystemClock.uptimeMillis() - backDownAt;
            if (held >= EXIT_HOLD_MS) {
                exitFired = true;
                showHold(-1, "Volver");
                exitLauncher();
            } else {
                showHold(held, "Volver");
                handler.postDelayed(this, 200);
            }
        }
    };
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            pushClock(); // también cubre el cambio de horario de verano
            handler.postDelayed(this, 60L * 60L * 1000L);
        }
    };
    private boolean wxRunning = false;
    private boolean wxAgain = false;

    private final Runnable netPush = this::pushNet;
    private final Runnable wxRetry = () -> refreshWeather(true);
    private final Runnable wxTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather(false);
            handler.postDelayed(this, WX_MAX_AGE);
        }
    };

    private static class AppInfo {
        String label, pkg, icon, c1, c2, bg;
        boolean full, stream;
        int cat;
    }

    // ------------------------------------------------------------------ ciclo de vida

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE); // sin la barra negra "Launcher"
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF131640));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        // El diseño es para 1920×1080: el tamaño de letra del sistema (Accesibilidad) no debe agrandar el texto,
        // porque deforma los widgets (el pronóstico se veía aplastado en algunos Fire TV).
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) Log.e(TAG, "Error al cargar la página: " + error.getDescription());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                handler.postDelayed(MainActivity.this::askWatchNext, 20_000);
                if (pendingSbVideo == null && getIntent() != null) pendingSbVideo = getIntent().getStringExtra("sbVideo");
                handler.postDelayed(MainActivity.this::flushSbVideo, 1500);
                pushNet();
                pushClock();
                pushUsage();
                pushSys();
                pushMedia();
                String wx = prefs().getString("wx", null);
                if (wx != null) pushWx(wx);
            }
        });
        // Los mensajes de la página van solo al Logcat (filtro "Launcher"); ya no se dibuja ninguna barra.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String line = "JS: " + m.message() + " (línea " + m.lineNumber() + ")";
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, line);
                else Log.d(TAG, line);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Bridge");
        // Sin capa de hardware propia: el WebView ya dibuja con la tarjeta gráfica, y esa capa extra hacía
        // parpadear toda la pantalla en algunos Fire TV al cambiar imágenes (portada de la canción, etc.).
        web.setBackgroundColor(0xFF131640);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.requestFocus();

        registerNetworkWatch();
        registerTimeWatch();
        HomeWatcherService.ensureEnabled(this);
        MediaWatcherService.ensureEnabled(this);
        if (requestLocationIfNeeded()) {
            // El launcher no termina de abrir hasta que respondas el aviso (aceptes o no).
            awaiting = true;
            web.loadDataWithBaseURL(null, WAIT_HTML, "text/html", "utf-8", null);
        } else {
            loadPage();
            requestDefaultHomeOnce();
        }
        handler.postDelayed(wxTick, 1500);
        handler.postDelayed(clockTick, 60L * 60L * 1000L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (web == null || !pageLoaded) return; // todavía esperando la respuesta a los permisos
        web.requestFocus();
        HomeWatcherService.ensureEnabled(this);
        MediaWatcherService.ensureEnabled(this);
        if (!signature().equals(lastSig)) {
            loadPage(); // se instaló o desinstaló una app
        } else {
            js("window.__resume&&window.__resume()");
            pushNet();
            pushClock();
            pushUsage();
            pushSys();
            pushMedia();
            flushSbVideo();
        }
        refreshWeather(false);
    }

    /** Google TV / Android TV: permiso para leer la lista "Watch Next" (miniaturas de lo que viste). Se pide una vez. */
    private void askWatchNext() {
        try {
            final String perm = "com.android.providers.tv.permission.READ_EPG_DATA";
            if (Build.VERSION.SDK_INT < 26 || awaiting || !resumed) return;
            if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            if (prefs().getBoolean("askedEpg", false)) return;
            prefs().edit().putBoolean("askedEpg", true).apply();
            requestPermissions(new String[]{perm}, 77);
        } catch (Exception ignored) {
        }
    }

    /** Stand by con el video que dejaste en pausa (lo pide el servicio de reproducción). */
    private String pendingSbVideo = null;

    private void flushSbVideo() {
        if (pendingSbVideo == null || !pageReady) return;
        String p = pendingSbVideo.replaceAll("[^A-Za-z0-9._]", "");
        pendingSbVideo = null;
        js("window.__standbyVideo&&window.__standbyVideo('" + p + "')");
    }

    @Override
    protected void onPause() {
        resumed = false;
        js("window.__pause&&window.__pause()"); // en segundo plano no corre el stand by
        super.onPause();
    }

    private final Runnable mediaPush = this::pushMedia;

    /** El servicio de reproducción guardó algo nuevo (serie, película, canción): se actualiza la pantalla. */
    static void onMediaChanged() {
        MainActivity a = current.get();
        if (a == null) return;
        a.handler.removeCallbacks(a.mediaPush);
        a.handler.postDelayed(a.mediaPush, 300);
    }

    private String lastMediaJson = "";

    private void pushMedia() {
        if (!pageReady) return;
        String j = MediaWatcherService.json(this);
        if (j.equals(lastMediaJson)) return; // nada cambió: no se redibuja la pantalla
        lastMediaJson = j;
        js("window.__setMedia&&window.__setMedia(" + safeJson(j) + ")");
    }

    /** Lleva la interfaz al menú principal (lo usa el servicio del botón Inicio). */
    static void goMain() {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> a.js("window.__home&&window.__home(false)"));
    }

    /** Botón Inicio con el launcher ya abierto: volver al menú principal. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String vid = intent != null ? intent.getStringExtra("sbVideo") : null;
        if (vid != null) pendingSbVideo = vid; // se muestra al terminar onResume (si no, __resume lo cerraría)
        else js("window.__home&&window.__home(true)"); // vuelves de una app: todo queda como lo dejaste
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        handler.removeCallbacksAndMessages(null);
        try {
            if (timeReceiver != null) unregisterReceiver(timeReceiver);
        } catch (Exception ignored) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && netCallback != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        try {
            if (wifiReceiver != null) unregisterReceiver(wifiReceiver);
        } catch (Exception ignored) {
        }
        bg.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ mando a distancia

    /**
     * OK, Volver y Menú se mandan directo a la interfaz, aunque ningún botón de la página tenga el foco.
     * OK corto abre; OK mantenido 0,65 s activa "mover". Volver corto vuelve; Volver mantenido 10 s
     * sale del launcher. Las flechas las maneja la página.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (web == null) return super.dispatchKeyEvent(ev);
        int action = ev.getAction();
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    js("window.__kdown&&window.__kdown()"); // despierta el stand by si estaba activo
                    okLongFired = false;
                    handler.postDelayed(okLong, OK_LONG_MS);
                } else if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(okLong);
                    if (!okLongFired) js("window.__ok&&window.__ok()");
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    js("window.__kdown&&window.__kdown()");
                    backDownAt = SystemClock.uptimeMillis();
                    exitFired = false;
                    handler.postDelayed(backHoldTick, 1000);
                } else if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(backHoldTick);
                    long held = SystemClock.uptimeMillis() - backDownAt;
                    showHold(-1, "Volver");
                    // Si lo soltaste antes de 1 s es un Volver normal; entre 1 y 10 s se cancela la salida.
                    if (!exitFired && held < 1000) js("window.__back&&window.__back()");
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    js("window.__kdown&&window.__kdown()");
                    js("window.__menu&&window.__menu()");
                }
                return true;
            default:
                return super.dispatchKeyEvent(ev);
        }
    }

    /** Muestra u oculta (ms = -1) el aviso "Saliendo del launcher" con su barra. */
    static void showHold(long ms, String button) {
        MainActivity a = current.get();
        if (a == null) return;
        a.runOnUiThread(() -> a.js("window.__exitHold&&window.__exitHold(" + ms + ",'" + button + "')"));
    }

    /** Sale al inicio del sistema (Fire TV / Google TV) hasta el próximo Inicio. */
    void exitLauncher() {
        HomeWatcherService.goToSystemHome(this);
    }

    /** Un launcher nunca se cierra con Volver. */
    @Override
    public void onBackPressed() {
        js("window.__back&&window.__back()");
    }

    // ------------------------------------------------------------------ página

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    /** Carga la interfaz con las apps, la red, el último clima guardado y el modo ligero. */
    private void loadPage() {
        pageReady = false;
        lastMediaJson = "";
        pageLoaded = true;
        List<AppInfo> apps = queryApps();
        String html = readAsset("index.html")
                .replace("__NET_JSON__", safeJson(netJson()))
                .replace("__WX_JSON__", safeJson(prefs().getString("wx", "null")))
                .replace("__USAGE_JSON__", safeJson(usageJson()))
                .replace("__CLOCK_JSON__", safeJson(clockJson()))
                .replace("__SYS_JSON__", safeJson(sysJson()))
                .replace("__MEDIA_JSON__", safeJson(MediaWatcherService.json(this)))
                .replace("__LITE__", String.valueOf(isLowMemory()))
                .replace("__APPS_JSON__", appsJson(apps));
        lastSig = signature();
        try {
            File f = new File(getFilesDir(), "index.html");
            FileOutputStream out = new FileOutputStream(f);
            out.write(html.getBytes("UTF-8"));
            out.close();
            web.loadUrl("file://" + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "No se pudo guardar la página", e);
            web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null);
        }
    }

    private static String safeJson(String json) {
        return json == null ? "null" : json.replace("<", "\\u003c");
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer " + name, e);
            return "<html><body style='background:#131640;color:#fff'>Error al leer la interfaz</body></html>";
        }
    }

    // ------------------------------------------------------------------ apps

    private Map<String, ResolveInfo> resolveApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> map = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String c : categories) {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(c);
            for (ResolveInfo ri : pm.queryIntentActivities(it, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                map.put(pkg, ri);
            }
        }
        return map;
    }

    /** Huella de las apps instaladas: si cambia, se vuelve a cargar la página. */
    private String signature() {
        List<String> pkgs = new ArrayList<>(resolveApps().keySet());
        Collections.sort(pkgs);
        return android.text.TextUtils.join(";", pkgs);
    }

    private List<AppInfo> queryApps() {
        PackageManager pm = getPackageManager();
        List<AppInfo> out = new ArrayList<>();
        for (ResolveInfo ri : resolveApps().values()) {
            AppInfo a = new AppInfo();
            a.pkg = ri.activityInfo.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.cat = categoryOf(ri, a.label);
            String[] ic = iconFor(ri, pm);
            a.icon = ic[0];
            a.c1 = ic[1];
            a.c2 = ic[2];
            a.full = "1".equals(ic[3]);
            a.bg = ic[4];
            a.stream = a.cat == 0; // apps de video y streaming: alimentan "Continuar viendo"
            out.add(a);
        }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        if (out.size() > MAX_APPS) out = new ArrayList<>(out.subList(0, MAX_APPS));
        return out;
    }

    /** 0 video, 1 música, 2 juegos, 3 utilidades y el resto. */
    private int categoryOf(ResolveInfo ri, String label) {
        String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
        String lbl = label.toLowerCase(Locale.ROOT);
        String t = pkg + " " + lbl;
        // Antes "tv" en el paquete metía Ajustes (com.android.tv.settings) en Video.
        if (containsAny(t, "settings", "ajustes", "configuraci")) return 3;
        if (Build.VERSION.SDK_INT >= 26) {
            int c = ri.activityInfo.applicationInfo.category;
            if (c == ApplicationInfo.CATEGORY_VIDEO) return 0;
            if (c == ApplicationInfo.CATEGORY_AUDIO) return 1;
            if (c == ApplicationInfo.CATEGORY_GAME) return 2;
        }
        if (containsAny(t, "video", "netflix", "youtube", "prime", "disney", "hulu", "hbo", "plex", "kodi",
                "cine", "movie", "stream", "crunchyroll", "paramount", "pluto", "tubi", "vix", "twitch",
                "vimeo", "jellyfin", "emby", "iptv", "tivimate", "vlc", "smarters", "peacock", "flix", "anime")
                || lbl.matches(".*\\btv\\b.*")) return 0;
        if (containsAny(t, "music", "música", "spotify", "radio", "podcast", "audio", "deezer", "tidal", "pandora")) return 1;
        if (containsAny(t, "game", "juego")) return 2;
        return 3;
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    /**
     * Logo real de la app y cómo encajarlo en el mosaico: {icono, color 1, color 2, "1" si va a sangre, fondo}.
     * Si el logo trae su propio fondo (de color, oscuro o con degradado) ocupa todo el mosaico con su marco.
     * Si es un logo suelto, con fondo blanco o circular, va centrado sobre un color sacado del logo.
     */
    private String[] iconFor(ResolveInfo ri, PackageManager pm) {
        String key = ri.activityInfo.packageName;
        String[] cached = iconCache.get(key);
        if (cached != null) return cached;
        String[] result = {"", "", "", "", ""};
        try {
            Drawable d = ri.loadIcon(pm);
            Bitmap out = null;
            int bg = 0;
            boolean full = false;
            if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
                // Icono adaptable: su capa de fondo es su marco. Se dibuja completo, sin la máscara del sistema.
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
                Drawable back = ad.getBackground();
                if (back != null) {
                    Bitmap layer = renderLayers(back, null, ICON_PX);
                    int[] info = layerInfo(layer);
                    layer.recycle();
                    if (info[0] >= 95) {
                        full = true;
                        bg = info[1];
                        out = renderLayers(back, ad.getForeground(), ICON_PX);
                    }
                }
                if (out == null) {
                    // Fondo con transparencias: se trata como un icono normal ya recortado por el sistema.
                    Bitmap src = renderPlain(d, SRC_PX);
                    out = fillShape(src);
                    if (out != null) full = true;
                    else out = squareLogo(src, opaqueBounds(src));
                    src.recycle();
                }
            } else {
                Bitmap src = renderPlain(d, SRC_PX);
                // Logo con marco de figura simple (cuadrado, redondeado, círculo de Spotify…): se agranda hasta
                // llenar el mosaico y lo que falta se pinta con los píxeles exactos del borde del logo.
                // Si la forma es compleja, va como Play Store: entero, sobre un fondo de su color que resalte.
                out = fillShape(src);
                if (out != null) full = true;
                else out = squareLogo(src, opaqueBounds(src)); // logo suelto (Play Store): sobre su color
                src.recycle();
            }
            if (full) bg = edgeAverage(out);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.PNG, 100, bos);
            result[0] = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            String[] cols = tileColors(out, !full);
            if (cols != null) {
                result[1] = cols[0];
                result[2] = cols[1];
            }
            result[3] = full ? "1" : "";
            result[4] = full ? hex(bg) : "";
            out.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Icono no disponible para " + key, e);
        }
        iconCache.put(key, result);
        return result;
    }

    private static Bitmap renderPlain(Drawable d, int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, px, px);
        d.draw(canvas);
        return bmp;
    }

    /** Capas de un icono adaptable: miden 108 dp y se ve el centro de 72 dp (por eso el margen de 1/4). */
    private static Bitmap renderLayers(Drawable back, Drawable fore, int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        int m = px / 4;
        if (back != null) {
            back.setBounds(-m, -m, px + m, px + m);
            back.draw(canvas);
        }
        if (fore != null) {
            fore.setBounds(-m, -m, px + m, px + m);
            fore.draw(canvas);
        }
        return bmp;
    }

    /** {% de píxeles opacos, color promedio} de una capa. */
    private static int[] layerInfo(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long r = 0, g = 0, bl = 0;
        int opaque = 0, total = 0;
        for (int i = 0; i < px.length; i += 7) {
            total++;
            int p = px[i];
            if (Color.alpha(p) < 220) continue;
            opaque++;
            r += Color.red(p);
            g += Color.green(p);
            bl += Color.blue(p);
        }
        if (opaque == 0) return new int[]{0, 0};
        return new int[]{opaque * 100 / total, Color.rgb((int) (r / opaque), (int) (g / opaque), (int) (bl / opaque))};
    }

    /** Rectángulo que ocupa el dibujo (alfa ≥ 128). */
    private static Rect opaqueBounds(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bo = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) >= 128) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > bo) bo = y;
                }
            }
        }
        if (r < 0) return null;
        return new Rect(l, t, r + 1, bo + 1);
    }

    /**
     * Si el marco del logo es una figura geométrica simple (círculo, elipse, cuadrado, cuadrado redondeado,
     * squircle o polígono regular) sólida y con un dibujo encima, devuelve un cuadrado de ICON_PX totalmente
     * opaco: la figura ocupa todo el cuadrado y cada píxel que faltaba toma el color exacto del borde del
     * logo en esa dirección. Así el círculo verde de Spotify queda como un cuadrado del mismo verde.
     * Si la forma es compleja (globo, escudo, estrella, silueta…) devuelve null y el logo va, como el de
     * Play Store, sobre un fondo de su color que lo resalta.
     */
    private static Bitmap fillShape(Bitmap src) {
        Rect box = opaqueBounds(src);
        if (box == null) return null;
        // 1) Recortar al dibujo y centrarlo en un cuadrado (las formas anchas no se deforman).
        int side = Math.max(box.width(), box.height());
        Bitmap sq = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        new Canvas(sq).drawBitmap(src, box, new Rect((side - box.width()) / 2, (side - box.height()) / 2,
                (side - box.width()) / 2 + box.width(), (side - box.height()) / 2 + box.height()), null);
        Bitmap sc = Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true);
        if (sc != sq) sq.recycle();
        int n = ICON_PX;
        int[] px = new int[n * n];
        sc.getPixels(px, 0, n, 0, 0, n, n);
        // 2) Centro de la silueta y, en cada dirección, dónde está su borde exterior y de qué color es.
        double sx = 0, sy = 0;
        int cnt = 0;
        for (int i = 0; i < n * n; i++) {
            if ((px[i] >>> 24) >= 128) {
                sx += i % n;
                sy += i / n;
                cnt++;
            }
        }
        if (cnt == 0) { sc.recycle(); return null; }
        final double cx = sx / cnt, cy = sy / cnt;
        final int rays = 360;
        int[] cols = new int[rays];
        double[] hits = new double[rays];
        boolean[] ok = new boolean[rays];
        double rmax = n * 0.75;
        for (int k = 0; k < rays; k++) {
            double ang = 2 * Math.PI * k / rays, dx = Math.cos(ang), dy = Math.sin(ang);
            double hit = -1;
            for (double r = rmax; r >= 0; r -= 0.5) {
                int x = (int) Math.round(cx + dx * r), y = (int) Math.round(cy + dy * r);
                if (x >= 0 && x < n && y >= 0 && y < n && (px[y * n + x] >>> 24) >= 128) {
                    hit = r;
                    break;
                }
            }
            hits[k] = hit;
            if (hit < 0) continue;
            double rs = Math.max(0, hit - 2);
            int xs = (int) Math.round(cx + dx * rs), ys = (int) Math.round(cy + dy * rs);
            if (xs < 0 || xs >= n || ys < 0 || ys >= n) continue;
            int p = px[ys * n + xs];
            if ((p >>> 24) >= 200) {
                cols[k] = p | 0xFF000000;
                ok[k] = true;
            }
        }
        for (int k = 0; k < rays; k++) {
            if (ok[k]) continue;
            for (int dd = 1; dd < rays; dd++) {
                if (ok[(k - dd + rays) % rays]) { cols[k] = cols[(k - dd + rays) % rays]; break; }
                if (ok[(k + dd) % rays]) { cols[k] = cols[(k + dd) % rays]; break; }
            }
            if (cols[k] == 0) { sc.recycle(); return null; }
        }
        long rr = 0, gg = 0, bb = 0;
        for (int col : cols) { rr += Color.red(col); gg += Color.green(col); bb += Color.blue(col); }
        int ref = Color.rgb((int) (rr / rays), (int) (gg / rays), (int) (bb / rays));
        // 3) Solo si es una forma sólida con un logo que se distinga (si no, se perdería el dibujo).
        Rect box2 = opaqueBounds(sc);
        if (box2 == null || !hasLogoInside(sc, box2, ref)) { sc.recycle(); return null; }
        // 3b) Solo figuras geométricas simples se rellenan; las formas complejas van sobre un fondo.
        for (double h : hits) if (h < 0) { sc.recycle(); return null; }
        if (!isSimpleShape(hits, box2.width() / 2.0, box2.height() / 2.0)) { sc.recycle(); return null; }
        // 4) Rellenar: transparente -> color del borde; semitransparente -> mezcla con ese color.
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int i = y * n + x, p = px[i], a = p >>> 24;
                if (a == 255) continue;
                int k = (int) Math.round(Math.atan2(y - cy, x - cx) / (2 * Math.PI) * rays);
                int f = cols[((k % rays) + rays) % rays];
                int r = (Color.red(p) * a + Color.red(f) * (255 - a)) / 255;
                int g = (Color.green(p) * a + Color.green(f) * (255 - a)) / 255;
                int bl = (Color.blue(p) * a + Color.blue(f) * (255 - a)) / 255;
                px[i] = Color.rgb(r, g, bl);
            }
        }
        Bitmap out = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        sc.recycle();
        return out;
    }

    /**
     * ¿La silueta es una figura geométrica simple? Se compara su borde (360 direcciones desde su centro) con:
     * una superelipse |x/a|^n + |y/b|^n = 1 (círculo, elipse, cuadrado, cuadrado redondeado, squircle) y un
     * polígono regular de 3 a 8 lados en cualquier rotación. Si se parece a alguna (error medio ≤ 3,5 % o 3 %),
     * es simple; un globo con cola, un escudo, una gota o una nube no.
     */
    private static boolean isSimpleShape(double[] hits, double a, double b) {
        final int rays = hits.length, step = 2; // 180 direcciones bastan para la comparación
        double best = 9;
        double[] exps = {2, 2.3, 2.6, 3, 3.5, 4, 5, 6, 8, 12, 20, 40};
        for (double e : exps) {
            double err = 0;
            int m = 0;
            for (int k = 0; k < rays; k += step) {
                double t = 2 * Math.PI * k / rays, cc = Math.abs(Math.cos(t)), ss = Math.abs(Math.sin(t));
                double r = Math.pow(Math.pow(cc / a, e) + Math.pow(ss / b, e), -1.0 / e);
                err += Math.abs(hits[k] - r) / r;
                m++;
            }
            best = Math.min(best, err / m);
        }
        if (best <= 0.035) return true;
        double bestPoly = 9;
        for (int sides = 3; sides <= 8; sides++) {
            double al = 2 * Math.PI / sides;
            for (int deg = 0; deg <= 360 / sides; deg += 2) {
                double phi = Math.toRadians(deg), sum = 0;
                int m = 0;
                for (int k = 0; k < rays; k += step) {
                    double t = 2 * Math.PI * k / rays, u = ((t - phi) % al + al) % al - al / 2;
                    sum += hits[k] * Math.cos(u);
                    m++;
                }
                double rin = sum / m, err = 0;
                for (int k = 0; k < rays; k += step) {
                    double t = 2 * Math.PI * k / rays, u = ((t - phi) % al + al) % al - al / 2;
                    double r = rin / Math.cos(u);
                    err += Math.abs(hits[k] - r) / r;
                }
                bestPoly = Math.min(bestPoly, err / m);
            }
        }
        return bestPoly <= 0.03;
    }

    /** Color promedio del borde del cuadrado ya relleno (va detrás, bajo el aro del mosaico). */
    private static int edgeAverage(Bitmap b) {
        int n = b.getWidth();
        long r = 0, g = 0, bl = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            int[] ps = {b.getPixel(i, 0), b.getPixel(i, n - 1), b.getPixel(0, i), b.getPixel(n - 1, i)};
            for (int p : ps) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p); cnt++; }
        }
        return Color.rgb((int) (r / cnt), (int) (g / cnt), (int) (bl / cnt));
    }

    /** Dentro de la forma no hay huecos y sí hay un dibujo que se distingue del fondo (si no, se perdería el logo). */
    private static boolean hasLogoInside(Bitmap b, Rect box, int bg) {
        float cx = box.left + (box.width() - 1) / 2f, cy = box.top + (box.height() - 1) / 2f;
        float rx = box.width() / 2f * 0.8f, ry = box.height() / 2f * 0.8f;
        int total = 0, holes = 0, diff = 0;
        for (int y = box.top; y < box.bottom; y += 2) {
            for (int x = box.left; x < box.right; x += 2) {
                float ex = (x - cx) / rx, ey = (y - cy) / ry;
                if (ex * ex + ey * ey > 1f) continue;
                total++;
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) {
                    holes++;
                } else {
                    double d = Math.sqrt(Math.pow(Color.red(p) - Color.red(bg), 2)
                            + Math.pow(Color.green(p) - Color.green(bg), 2) + Math.pow(Color.blue(p) - Color.blue(bg), 2));
                    if (d > 60) diff++;
                }
            }
        }
        return total > 0 && holes / (float) total < 0.12f && diff / (float) total >= 0.02f;
    }

    /** Logo suelto: se recorta a lo que ocupa el dibujo y se centra en un cuadrado, para mostrarlo más grande. */
    private static Bitmap squareLogo(Bitmap src, Rect box) {
        if (box == null) return Bitmap.createScaledBitmap(src, ICON_PX, ICON_PX, true);
        int side = Math.round(Math.max(box.width(), box.height()) * 1.04f);
        Bitmap sq = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sq);
        c.drawBitmap(src, box, new Rect((side - box.width()) / 2, (side - box.height()) / 2,
                (side - box.width()) / 2 + box.width(), (side - box.height()) / 2 + box.height()), null);
        Bitmap out = Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true);
        if (out != sq) sq.recycle();
        return out;
    }

    /**
     * Color dominante con saturación del logo (si es gris o blanco, null). Para un logo suelto, el mosaico
     * usa ese mismo color en tono oscuro si el logo es claro, o en tono claro si el logo es oscuro,
     * para que el logo no se pierda sobre su propio color.
     */
    private static String[] tileColors(Bitmap b, boolean logoMode) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        double[] weight = new double[12], rs = new double[12], gs = new double[12], bs = new double[12];
        float[] hsv = new float[3];
        int opaque = 0;
        double lum = 0;
        for (int i = 0; i < px.length; i += 3) {
            int p = px[i];
            if (Color.alpha(p) < 200) continue;
            opaque++;
            lum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0;
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;
            int bin = ((int) (hsv[0] / 30f)) % 12;
            double wt = hsv[1] * hsv[2];
            weight[bin] += wt;
            rs[bin] += Color.red(p) * wt;
            gs[bin] += Color.green(p) * wt;
            bs[bin] += Color.blue(p) * wt;
        }
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (opaque == 0 || weight[best] / opaque < 0.06) return null;
        int c = Color.rgb((int) (rs[best] / weight[best]), (int) (gs[best] / weight[best]), (int) (bs[best] / weight[best]));
        Color.colorToHSV(c, hsv);
        float sat = Math.min(Math.max(hsv[1], 0.45f), 0.85f);
        if (logoMode) {
            boolean brightLogo = lum / opaque > 0.42;
            int l1 = brightLogo ? Color.HSVToColor(new float[]{hsv[0], sat, 0.38f}) : Color.HSVToColor(new float[]{hsv[0], sat * 0.22f, 0.97f});
            int l2 = brightLogo ? Color.HSVToColor(new float[]{hsv[0], Math.min(sat + 0.08f, 0.95f), 0.22f}) : Color.HSVToColor(new float[]{hsv[0], sat * 0.32f, 0.84f});
            return new String[]{hex(l1), hex(l2)};
        }
        int c1 = Color.HSVToColor(new float[]{hsv[0], sat, 0.80f});
        int c2 = Color.HSVToColor(new float[]{hsv[0], Math.min(sat + 0.08f, 0.95f), 0.58f});
        return new String[]{hex(c1), hex(c2)};
    }

    private static String hex(int c) {
        return String.format(Locale.ROOT, "#%06x", c & 0xFFFFFF);
    }

    private String appsJson(List<AppInfo> apps) {
        JSONArray arr = new JSONArray();
        try {
            for (AppInfo a : apps) {
                JSONObject o = new JSONObject();
                o.put("label", a.label);
                o.put("pkg", a.pkg);
                o.put("cat", a.cat);
                o.put("icon", a.icon);
                o.put("c1", a.c1);
                o.put("c2", a.c2);
                o.put("full", a.full);
                o.put("bg", a.bg);
                o.put("stream", a.stream);
                arr.put(o);
            }
        } catch (Exception e) {
            Log.e(TAG, "No se pudo armar la lista de apps", e);
        }
        return safeJson(arr.toString());
    }

    // ------------------------------------------------------------------ red (Wi-Fi / Ethernet)

    private WifiManager wifi() {
        return (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private boolean isWifiOn() {
        WifiManager wm = wifi();
        return wm != null && wm.isWifiEnabled();
    }

    /** Avisa a la interfaz cada vez que cambia la red, sin recargar la página. */
    private void registerNetworkWatch() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleNet();
                }

                @Override
                public void onLost(Network network) {
                    scheduleNet();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleNet();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    scheduleNet();
                }
            };
            if (cm != null) cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo vigilar la red", e);
        }
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                scheduleNet();
            }
        };
        registerReceiver(wifiReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
    }

    private void scheduleNet() {
        handler.removeCallbacks(netPush);
        handler.postDelayed(netPush, 400);
    }

    private void pushNet() {
        if (!pageReady) return;
        String json = netJson();
        js("window.__setNet&&window.__setNet(" + safeJson(json) + ")");
        // Si la red volvió y no hay clima guardado, se pide ahora.
        if (!json.contains("\"type\":\"none\"") && prefs().getString("wx", null) == null) refreshWeather(false);
    }

    /** Tipo de conexión (wifi / ethernet / other / none), nombre de la red, IP y dominio. */
    private String netJson() {
        JSONObject o = new JSONObject();
        try {
            String type = "none", name = "", ip = "", domain = "";
            int level = -1;
            boolean ssidHidden = false;
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "ethernet";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "wifi";
                else type = "other";
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address) {
                            ip = la.getAddress().getHostAddress();
                            break;
                        }
                    }
                    if (lp.getDomains() != null) domain = lp.getDomains();
                }
            }
            if ("wifi".equals(type)) {
                WifiManager wm = wifi();
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                if (info != null) {
                    name = cleanSsid(info.getSSID());
                    level = WifiManager.calculateSignalLevel(info.getRssi(), 5);
                }
                if (name.isEmpty()) ssidHidden = true;
            } else if ("ethernet".equals(type)) {
                name = "Ethernet";
            }
            o.put("type", type);
            o.put("name", name);
            o.put("ip", ip);
            o.put("domain", domain);
            o.put("level", level);
            o.put("wifiOn", isWifiOn());
            o.put("ssidHidden", ssidHidden);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer la red", e);
        }
        return o.toString();
    }

    /** Android sin permiso de ubicación devuelve "<unknown ssid>": en ese caso, vacío. */
    private static String cleanSsid(String ssid) {
        if (ssid == null || ssid.equals("<unknown ssid>") || ssid.equals("0x")) return "";
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    // ------------------------------------------------------------------ hora del sistema

    /** Avisa a la interfaz si cambian la hora, la fecha o la zona horaria de la TV. */
    private void registerTimeWatch() {
        timeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                pushClock();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        registerReceiver(timeReceiver, f);
    }

    /**
     * Hora del launcher: zona horaria de la ubicación del aparato (la da Open-Meteo con sus coordenadas)
     * y, si el reloj de la TV está desfasado más de 90 s, la corrección según la hora de internet.
     * Sin ubicación todavía, usa la zona configurada en la TV.
     */
    private String clockJson() {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            String locTz = prefs().getString("locTz", null);
            TimeZone tz = TimeZone.getDefault();
            boolean fromLocation = false;
            if (locTz != null) {
                TimeZone t = TimeZone.getTimeZone(locTz);
                if (!"GMT".equals(t.getID()) || "GMT".equals(locTz)) {
                    tz = t;
                    fromLocation = true;
                }
            }
            o.put("off", tz.getOffset(now));
            o.put("tz", tz.getID());
            o.put("src", fromLocation ? "location" : "system");
            o.put("skew", prefs().getLong("skew", 0));
            o.put("h24", DateFormat.is24HourFormat(this));
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer la hora", e);
        }
        return o.toString();
    }

    private void pushClock() {
        if (pageReady) js("window.__setTz&&window.__setTz(" + safeJson(clockJson()) + ")");
    }

    // ------------------------------------------------------------------ uso de apps (Continuar viendo)

    /** Acceso de uso: en Fire TV se da con ADB (appops GET_USAGE_STATS allow). */
    private boolean hasUsageAccess() {
        try {
            AppOpsManager ao = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Última vez que se usó cada app en la TV (últimos 30 días), venga de donde venga. */
    private String usageJson() {
        JSONObject o = new JSONObject();
        try {
            JSONObject last = new JSONObject();
            boolean granted = hasUsageAccess();
            if (granted) {
                UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                List<UsageStats> list = usm != null
                        ? usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24L * 3600L * 1000L, now)
                        : null;
                if (list != null) {
                    for (UsageStats u : list) {
                        if (u.getTotalTimeInForeground() <= 0 || u.getLastTimeUsed() <= 0) continue;
                        String pkg = u.getPackageName();
                        if (u.getLastTimeUsed() > last.optLong(pkg, 0)) last.put(pkg, u.getLastTimeUsed());
                    }
                }
            }
            o.put("granted", granted);
            o.put("last", last);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer el uso de las apps", e);
        }
        return o.toString();
    }

    /** La consulta de uso puede tardar: se hace en segundo plano y luego se manda a la pantalla. */
    private void pushUsage() {
        if (!pageReady) return;
        try {
            bg.execute(() -> {
                final String json = usageJson();
                handler.post(() -> js("window.__setUsage&&window.__setUsage(" + safeJson(json) + ")"));
            });
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer el uso de las apps", e);
        }
    }

    /** Estado que se muestra en Personalización: si Inicio ya abre este launcher. */
    private String sysJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("homeService", HomeWatcherService.isEnabled(this));
            o.put("media", MediaWatcherService.isEnabled(this));
            o.put("overlay", Settings.canDrawOverlays(this));
            o.put("secure", checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED);
            o.put("defaultHome", isDefaultHome());
            o.put("usage", hasUsageAccess());
            o.put("fireTv", isFireTv());
            o.put("loc", checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
            o.put("epgAvail", Build.VERSION.SDK_INT >= 26);
            o.put("epg", checkSelfPermission(EPG) == PackageManager.PERMISSION_GRANTED);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo leer el estado del sistema", e);
        }
        return o.toString();
    }

    private void pushSys() {
        if (pageReady) js("window.__setSys&&window.__setSys(" + safeJson(sysJson()) + ")");
    }

    // ------------------------------------------------------------------ permiso de ubicación

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Pide la ubicación (para el clima de tu zona y el nombre del Wi-Fi). Máximo 3 veces. */
    private boolean requestLocationIfNeeded() {
        if (hasLocation()) return false;
        int asked = prefs().getInt("locAsks", 0);
        if (asked >= MAX_LOCATION_ASKS) return false;
        prefs().edit().putInt("locAsks", asked + 1).apply();
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        return true;
    }

    private boolean isFireTv() {
        return getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")
                || "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * En Android TV / Google TV pide una vez ser la app de inicio principal.
     * En Fire TV no: Fire OS ignora ese ajuste y se usa el servicio del botón Inicio.
     */
    private void requestDefaultHomeOnce() {
        if (Build.VERSION.SDK_INT < 29 || isFireTv() || isDefaultHome()) return;
        if (prefs().getBoolean("askedRole", false)) return;
        try {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                prefs().edit().putBoolean("askedRole", true).apply();
                awaiting = true;
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "No se pudo pedir ser el launcher principal", e);
        }
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE) {
            awaiting = false;
            pushSys();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_EPG || requestCode == REQ_LOC2) { // pedido desde la sección Permisos: sin recargar
            awaiting = false;
            pushSys();
            if (requestCode == REQ_LOC2) refreshWeather(true);
            return;
        }
        if (requestCode != REQ_LOCATION) return;
        awaiting = false;
        loadPage(); // ya decidiste: ahora sí termina de abrir
        refreshWeather(true);
        requestDefaultHomeOnce();
    }

    // ------------------------------------------------------------------ clima (Open-Meteo)

    private SharedPreferences prefs() {
        return getSharedPreferences("launcher", MODE_PRIVATE);
    }

    private long wxAge() {
        return System.currentTimeMillis() - prefs().getLong("wxTs", 0);
    }

    /** Trae el clima en segundo plano. Sin forzar, solo si el guardado tiene más de 30 minutos. */
    private void refreshWeather(boolean force) {
        if (!force && prefs().getString("wx", null) != null && wxAge() < WX_MAX_AGE) return;
        if (wxRunning) {
            wxAgain = true;
            return;
        }
        wxRunning = true;
        handler.removeCallbacks(wxRetry);
        bg.execute(() -> {
            String json = null;
            try {
                json = fetchWeather();
            } catch (Exception e) {
                Log.e(TAG, "No se pudo obtener el clima: " + e);
            }
            final String result = json;
            handler.post(() -> {
                wxRunning = false;
                if (result != null) {
                    prefs().edit().putString("wx", result).putLong("wxTs", System.currentTimeMillis()).apply();
                    pushWx(result);
                } else {
                    if (prefs().getString("wx", null) == null) {
                        pushWx("{\"error\":\"No se pudo obtener el clima\"}");
                    }
                    handler.postDelayed(wxRetry, WX_RETRY);
                }
                if (wxAgain) {
                    wxAgain = false;
                    refreshWeather(true);
                }
            });
        });
    }

    private void pushWx(String json) {
        if (pageReady) js("window.__setWx&&window.__setWx(" + safeJson(json) + ")");
    }

    /** Ubicación del aparato o, si no hay, la aproximada por la red; luego el pronóstico de 6 días. */
    private String fetchWeather() throws Exception {
        double lat, lon;
        String city = "";
        String src;
        Location loc = deviceLocation();
        if (loc != null) {
            lat = loc.getLatitude();
            lon = loc.getLongitude();
            src = "device";
            city = cityFor(lat, lon);
        } else {
            JSONObject ip = ipLocation();
            lat = ip.getDouble("latitude");
            lon = ip.getDouble("longitude");
            city = ip.optString("city", "");
            src = "ip";
        }

        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,cloud_cover,wind_speed_10m"
                        + "&hourly=temperature_2m,weather_code,precipitation_probability,cloud_cover,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=10", lat, lon);
        long sent = System.currentTimeMillis();
        JSONObject w = new JSONObject(httpGet(url));
        saveTimeFromWeather(w.optString("timezone", ""), sent);
        JSONObject cur = w.getJSONObject("current");
        JSONObject daily = w.getJSONObject("daily");
        JSONArray time = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        JSONArray sunrise = daily.optJSONArray("sunrise");
        JSONArray sunset = daily.optJSONArray("sunset");

        JSONArray ppMax = daily.optJSONArray("precipitation_probability_max");
        long off = w.optLong("utc_offset_seconds", 0) * 1000L;
        int curCode = cur.optInt("weather_code", codes.optInt(0));
        double curCloud = cur.optDouble("cloud_cover", Double.NaN);

        JSONArray days = new JSONArray();
        for (int i = 0; i < time.length() && i < 10; i++) {
            int code = i == 0 ? curCode : codes.optInt(i);
            // Hoy: estado actual con la nubosidad real ("Mayormente nublado"…); demás días: código del día.
            String kind = i == 0 ? kind(curCode, curCloud) : kind(code, Double.NaN);
            double hi = max.optDouble(i, Double.NaN), lo = min.optDouble(i, Double.NaN);
            double t = i == 0 ? cur.optDouble("temperature_2m", hi) : hi;
            JSONObject d = new JSONObject();
            d.put("d", i == 0 ? "Hoy" : dayAbbr(time.getString(i)));
            d.put("t", Double.isNaN(t) ? JSONObject.NULL : Math.round(t));
            d.put("hi", Double.isNaN(hi) ? JSONObject.NULL : Math.round(hi));
            d.put("lo", Double.isNaN(lo) ? JSONObject.NULL : Math.round(lo));
            d.put("c", coarse(kind));
            d.put("k", kind);
            d.put("n", kindText(kind, code));
            if (ppMax != null && !ppMax.isNull(i)) d.put("pp", ppMax.optInt(i, 0));
            // Amanecer y atardecer en minutos desde la medianoche (hora local del lugar); la página los usa para día/noche.
            if (sunrise != null && sunset != null) {
                int sr = minutesOfDay(sunrise.optString(i, ""));
                int ss = minutesOfDay(sunset.optString(i, ""));
                if (sr >= 0 && ss >= 0) {
                    d.put("sr", sr);
                    d.put("ss", ss);
                }
            }
            days.put(d);
        }
        // Ahora: sensación térmica, humedad, viento y si es de día.
        JSONObject now = new JSONObject();
        now.put("t", Math.round(cur.optDouble("temperature_2m", 0)));
        if (!cur.isNull("apparent_temperature")) now.put("feels", Math.round(cur.optDouble("apparent_temperature", 0)));
        if (!cur.isNull("relative_humidity_2m")) now.put("hum", cur.optInt("relative_humidity_2m", 0));
        if (!cur.isNull("wind_speed_10m")) now.put("wind", Math.round(cur.optDouble("wind_speed_10m", 0)));
        now.put("night", cur.optInt("is_day", 1) == 0);
        String nk = kind(curCode, curCloud);
        now.put("k", nk);
        now.put("c", coarse(nk));
        now.put("n", kindText(nk, curCode));

        // Próximas 26 horas desde la hora actual (con la hora en milisegundos), más amanecer y atardecer.
        JSONArray hours = new JSONArray();
        JSONObject hourly = w.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray ht = hourly.getJSONArray("time");
            JSONArray htemp = hourly.optJSONArray("temperature_2m"), hcode = hourly.optJSONArray("weather_code");
            JSONArray hpp = hourly.optJSONArray("precipitation_probability"), hcloud = hourly.optJSONArray("cloud_cover");
            JSONArray hday = hourly.optJSONArray("is_day");
            String curHour = cur.optString("time", "");
            if (curHour.length() >= 13) curHour = curHour.substring(0, 13);
            int i0 = 0;
            for (int i = 0; i < ht.length(); i++) if (ht.getString(i).startsWith(curHour)) { i0 = i; break; }
            for (int i = i0; i < ht.length() && i < i0 + 26; i++) {
                long ts = isoLocalMs(ht.getString(i)) - off;
                int code = hcode != null ? hcode.optInt(i, 0) : 0;
                String k = kind(code, hcloud != null && !hcloud.isNull(i) ? hcloud.optDouble(i) : Double.NaN);
                JSONObject h = new JSONObject();
                h.put("ts", ts);
                h.put("t", htemp != null ? Math.round(htemp.optDouble(i, 0)) : 0);
                h.put("k", k);
                h.put("pp", hpp != null && !hpp.isNull(i) ? hpp.optInt(i, 0) : 0);
                h.put("night", hday != null && hday.optInt(i, 1) == 0);
                hours.put(h);
            }
            if (hours.length() > 0 && sunrise != null && sunset != null) {
                long a = hours.getJSONObject(0).getLong("ts"), b = a + 26L * 3600000L;
                for (int i = 0; i < time.length(); i++) {
                    for (String[] ev : new String[][]{{"rise", sunrise.optString(i, "")}, {"set", sunset.optString(i, "")}}) {
                        if (ev[1].isEmpty()) continue;
                        long ts = isoLocalMs(ev[1]) - off;
                        if (ts > a && ts < b) {
                            JSONObject e = new JSONObject();
                            e.put("ts", ts);
                            e.put("ev", ev[0]);
                            hours.put(e);
                        }
                    }
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("city", city);
        out.put("src", src);
        out.put("now", now);
        out.put("hours", hours);
        out.put("ts", System.currentTimeMillis());
        out.put("days", days);
        return out.toString();
    }

    /** Última ubicación conocida; si no hay, pide una lectura (máx. 8 s). Muchos Fire TV no tienen. */
    private Location deviceLocation() {
        if (!hasLocation()) return null;
        final LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException e) {
            return null;
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 6L * 3600L * 1000L) return best;

        final String provider;
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
        else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
        else return best;

        final Location[] got = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        // Se implementan los 4 métodos: en Android 7–10 no tienen versión por defecto.
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                got[0] = location;
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String p, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
        handler.post(() -> {
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        handler.post(() -> {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        });
        return got[0] != null ? got[0] : best;
    }

    /** Nombre de la ciudad: primero el Geocoder del sistema y, si no existe, BigDataCloud. */
    private String cityFor(double lat, double lon) {
        try {
            if (Geocoder.isPresent()) {
                List<Address> list = new Geocoder(this, new Locale("es")).getFromLocation(lat, lon, 1);
                if (list != null && !list.isEmpty()) {
                    Address a = list.get(0);
                    String c = firstNonEmpty(a.getLocality(), a.getSubAdminArea(), a.getAdminArea());
                    if (!c.isEmpty()) return c;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject j = new JSONObject(httpGet(String.format(Locale.US,
                    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=es",
                    lat, lon)));
            return firstNonEmpty(j.optString("city"), j.optString("locality"), j.optString("principalSubdivision"));
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Ubicación aproximada por la IP pública (funciona aunque se niegue el permiso). */
    private JSONObject ipLocation() throws Exception {
        try {
            JSONObject j = new JSONObject(httpGet("https://ipwho.is/?lang=es"));
            if (j.optBoolean("success", false) && j.has("latitude")) return j;
        } catch (Exception e) {
            Log.d(TAG, "ipwho.is no respondió: " + e);
        }
        JSONObject j = new JSONObject(httpGet("https://ipapi.co/json/"));
        if (!j.has("latitude")) throw new IOException("sin ubicación por IP");
        return j;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    /** Guarda la zona horaria de la ubicación y cuánto se desvía el reloj de la TV de la hora de internet. */
    private void saveTimeFromWeather(String tzId, long sent) {
        SharedPreferences.Editor ed = prefs().edit();
        if (!tzId.isEmpty() && !"GMT".equals(tzId) && !"auto".equals(tzId)) ed.putString("locTz", tzId);
        if (lastHttpDate > 0 && lastHttpAt >= sent) {
            long rtt = lastHttpAt - sent;
            long skew = lastHttpDate + rtt / 2 - lastHttpAt;
            ed.putLong("skew", Math.abs(skew) > 90_000 ? skew : 0);
        }
        ed.apply();
        handler.post(this::pushClock);
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FireLauncher/1.0 (Android)");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " en " + url);
            long date = c.getDate(); // cabecera Date del servidor (hora de internet)
            if (date > 0) {
                lastHttpDate = date;
                lastHttpAt = System.currentTimeMillis();
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** "2026-11-24T06:31" -> 391 (minutos desde la medianoche); -1 si no se entiende. */
    private static int minutesOfDay(String iso) {
        try {
            int t = iso.indexOf('T');
            if (t < 0) return -1;
            String[] hm = iso.substring(t + 1).split(":");
            return Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String dayAbbr(String isoDate) {
        String[] names = {"Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb"};
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate));
            return names[cal.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "—";
        }
    }

    /** Fecha y hora local de Open-Meteo ("2026-11-24T16:00") como si fuera UTC, en milisegundos. */
    private static long isoLocalMs(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() > 16 ? iso.substring(0, 16) : iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Estado detallado, como en el teléfono. Con cielo sin lluvia (códigos 0–3) se usa la nubosidad real:
     * despejado, mayormente despejado, parcialmente nublado, mayormente nublado o nublado.
     */
    static String kind(int c, double cloud) {
        if (c <= 3) {
            if (!Double.isNaN(cloud)) {
                if (cloud < 12) return "clear";
                if (cloud < 35) return "mclear";
                if (cloud < 62) return "partly";
                if (cloud < 88) return "mcloudy";
                return "cloudy";
            }
            return c == 0 ? "clear" : c == 1 ? "mclear" : c == 2 ? "partly" : "cloudy";
        }
        if (c == 45 || c == 48) return "fog";
        if (c >= 51 && c <= 57) return "drizzle";
        if (c == 65 || c == 67 || c == 82) return "heavy";
        if ((c >= 61 && c <= 66) || (c >= 80 && c <= 81)) return "rain";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "snow";
        if (c >= 95) return "storm";
        return "cloudy";
    }

    /** Ícono sencillo de versiones anteriores (sol, parcial, nube, lluvia, tormenta). */
    static String coarse(String k) {
        switch (k) {
            case "clear": return "sun";
            case "mclear":
            case "partly": return "partly";
            case "drizzle":
            case "rain":
            case "heavy":
            case "snow": return "rain";
            case "storm": return "storm";
            default: return "cloud";
        }
    }

    static String kindText(String k, int code) {
        switch (k) {
            case "clear": return "Despejado";
            case "mclear": return "Mayormente despejado";
            case "partly": return "Parcialmente nublado";
            case "mcloudy": return "Mayormente nublado";
            case "cloudy": return "Nublado";
            default: return description(code);
        }
    }

    /** Códigos WMO de Open-Meteo → icono de la interfaz. */
    private static String condition(int c) {
        if (c == 0 || c == 1) return "sun";
        if (c == 2) return "partly";
        if (c >= 95) return "storm";
        if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82)) return "rain";
        return "cloud"; // nublado, niebla y nieve
    }

    private static String description(int c) {
        switch (c) {
            case 0: return "Despejado";
            case 1: return "Mayormente despejado";
            case 2: return "Parcialmente nublado";
            case 3: return "Nublado";
            case 45:
            case 48: return "Niebla";
            case 51:
            case 53:
            case 55: return "Llovizna";
            case 56:
            case 57: return "Llovizna helada";
            case 61: return "Lluvia ligera";
            case 63: return "Lluvia";
            case 65: return "Lluvia fuerte";
            case 66:
            case 67: return "Lluvia helada";
            case 71:
            case 73:
            case 75:
            case 77: return "Nieve";
            case 80:
            case 81: return "Chubascos";
            case 82: return "Chubascos fuertes";
            case 85:
            case 86: return "Chubascos de nieve";
            case 95: return "Tormenta";
            case 96:
            case 99: return "Tormenta con granizo";
            default: return "Nublado";
        }
    }

    // ------------------------------------------------------------------ memoria

    /** Poca memoria (como el Fire TV Stick 4K de 2 GB): la interfaz quita los desenfoques para ir más fluida. */
    private boolean isLowMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return true;
        if (am.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem < 3L * 1024L * 1024L * 1024L;
    }

    // ------------------------------------------------------------------ puente con la página

    /** Lo que la interfaz puede pedir: abrir apps, Wi-Fi, ajustes de red y clima. */
    public class Bridge {
        @JavascriptInterface
        /** Ajustes del stand by (los usa el servicio de reproducción para el caso del video en pausa). */
        public void setStandby(boolean on, int minutes) {
            prefs().edit().putBoolean("sbOn", on).putInt("sbMin", Math.max(1, minutes)).apply();
        }

        /** Salir del stand by que abrió un video en pausa: vuelve a esa app tal como estaba. */
        @JavascriptInterface
        public void returnToApp(final String pkg) {
            runOnUiThread(() -> {
                if (moveTaskToBack(true)) return;
                try {
                    Intent li = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                    if (li == null) li = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (li != null) startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void launch(final String pkg) {
            runOnUiThread(() -> {
                PackageManager pm = getPackageManager();
                Intent it = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (it == null) it = pm.getLaunchIntentForPackage(pkg);
                if (it == null) {
                    Log.e(TAG, "No se puede abrir " + pkg);
                    return;
                }
                try {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Exception e) {
                    Log.e(TAG, "Error al abrir " + pkg, e);
                }
            });
        }

        @JavascriptInterface
        public void toggleWifi() {
            runOnUiThread(() -> {
                WifiManager wm = wifi();
                boolean ok = false;
                if (wm != null) {
                    try {
                        ok = wm.setWifiEnabled(!wm.isWifiEnabled());
                    } catch (SecurityException e) {
                        Log.e(TAG, "Sin permiso para cambiar el Wi-Fi", e);
                    }
                }
                // Android 10 o superior no deja que la app cambie el Wi-Fi: se abre la configuración.
                if (!ok) openSettings(Settings.ACTION_WIFI_SETTINGS);
                handler.postDelayed(MainActivity.this::pushNet, 1500);
            });
        }

        @JavascriptInterface
        public void openNetSettings() {
            runOnUiThread(() -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        }

        @JavascriptInterface
        public void requestWeather() {
            runOnUiThread(() -> refreshWeather(false));
        }

        /** Abre el ajuste de acceso de uso. Devuelve false si esta TV no lo tiene en su menú. */
        @JavascriptInterface
        public boolean openUsageAccess() {
            Intent it = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (it.resolveActivity(getPackageManager()) == null) return false;
            runOnUiThread(() -> {
                try {
                    startActivity(it);
                } catch (Exception e) {
                    Log.e(TAG, "No se pudo abrir el acceso de uso", e);
                }
            });
            return true;
        }

        @JavascriptInterface
        public void exitLauncher() {
            runOnUiThread(MainActivity.this::exitLauncher);
        }

        /** Reanudar: abre la app de video y, si su reproducción sigue en pausa, le da play. */
        @JavascriptInterface
        public void resumeVideo(final String pkg) {
            launch(pkg);
            handler.postDelayed(() -> {
                android.media.session.MediaController mc = MediaWatcherService.controllerFor(pkg);
                if (mc != null && mc.getPlaybackState() != null
                        && mc.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PAUSED) {
                    mc.getTransportControls().play();
                }
            }, 1800);
        }

        /**
         * Última canción: play/pausa sin salir del launcher si Spotify sigue con su sesión abierta;
         * si no, abre la app. Devuelve "play", "pause" o "launch".
         */
        @JavascriptInterface
        public String musicToggle(final String pkg) {
            android.media.session.MediaController mc = MediaWatcherService.controllerFor(pkg);
            if (mc == null) {
                launch(pkg);
                return "launch";
            }
            android.media.session.PlaybackState ps = mc.getPlaybackState();
            boolean playing = ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
            runOnUiThread(() -> {
                if (playing) mc.getTransportControls().pause();
                else mc.getTransportControls().play();
            });
            return playing ? "pause" : "play";
        }

        /**
         * Pantalla de permisos: "home" (Inicio y abrir al encender), "media" (Continuar viendo y canción),
         * "overlay" (abrirse al reiniciar) y "usage" (uso de apps). Devuelve "ok" si ya quedó activo,
         * "settings" si abrió el ajuste, o "adb" si esta TV no tiene ese ajuste en el menú.
         */
        @JavascriptInterface
        public String grant(final String which) {
            Intent it;
            switch (which) {
                case "home":
                    if (HomeWatcherService.ensureEnabled(MainActivity.this)) return done();
                    it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    break;
                case "media":
                    if (MediaWatcherService.ensureEnabled(MainActivity.this)) return done();
                    it = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                    break;
                case "overlay":
                    if (Settings.canDrawOverlays(MainActivity.this)) return done();
                    it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()));
                    break;
                case "usage":
                    if (hasUsageAccess()) return done();
                    it = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    break;
                case "loc":
                case "epg": {
                    // Permisos que la app puede pedir directamente: aparece la ventana del sistema.
                    final String[] ask = which.equals("epg") ? new String[]{EPG}
                            : new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION};
                    if (checkSelfPermission(ask[ask.length - 1]) == PackageManager.PERMISSION_GRANTED) return done();
                    runOnUiThread(() -> {
                        awaiting = true;
                        try { requestPermissions(ask, which.equals("epg") ? REQ_EPG : REQ_LOC2); } catch (Exception e) { awaiting = false; }
                    });
                    return "asked";
                }
                default:
                    return "adb";
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (it.resolveActivity(getPackageManager()) == null) return "adb";
            final Intent go = it;
            runOnUiThread(() -> {
                try {
                    startActivity(go);
                } catch (Exception e) {
                    Log.e(TAG, "No se pudo abrir el ajuste " + which, e);
                }
            });
            return "settings";
        }

        private String done() {
            runOnUiThread(() -> {
                pushSys();
                pushMedia();
            });
            return "ok";
        }

        /** Compatibilidad con el botón "Activar botón Inicio" de Personalización. */
        @JavascriptInterface
        public String setupHome() {
            return grant("home");
        }
    }

    private void openSettings(String action) {
        String[] tries = {action, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String a : tries) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
        Log.e(TAG, "No se pudo abrir los ajustes de red");
    }
}
