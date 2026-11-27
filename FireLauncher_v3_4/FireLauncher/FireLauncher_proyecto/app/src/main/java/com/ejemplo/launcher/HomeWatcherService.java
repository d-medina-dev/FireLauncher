package com.ejemplo.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Servicio de accesibilidad "Launcher: botón Inicio".
 * <p>
 * En Fire TV no se puede cambiar el launcher principal ni leer el botón Inicio: Fire OS lo reserva.
 * Este servicio detecta cuándo aparece el inicio del sistema (por la señal HOME_PRESSED que manda
 * el launcher de Amazon, o porque su ventana pasa al frente) y vuelve a abrir este launcher encima.
 * Así funciona también al encender o despertar la TV. En Android TV / Google TV, donde el sistema sí
 * deja leer la tecla Inicio, además se puede mantener 10 s para salir.
 * <p>
 * No lee el contenido de la pantalla (canRetrieveWindowContent="false").
 */
public class HomeWatcherService extends AccessibilityService {

    private static final String TAG = "Launcher";
    private static final long EXIT_HOLD_MS = 10000;
    /** Inicios conocidos de Amazon y Google, además de los que encuentre el sistema. */
    private static final Set<String> KNOWN_HOMES = new HashSet<>(Arrays.asList(
            "com.amazon.tv.launcher", "com.amazon.firehomestarter",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher"));

    static volatile HomeWatcherService instance;
    /** El usuario salió al inicio del sistema: no se le devuelve al launcher hasta el próximo Inicio. */
    static volatile boolean bypass = false;
    static volatile long ignoreUntil = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> systemHomes = new HashSet<>();
    private BroadcastReceiver amazonHome;
    private BroadcastReceiver screenOn;
    private String lastPkg = "";
    private long homeDownAt = 0;
    private boolean homeExitFired = false;

    private final Runnable recheck = () -> {
        if (!bypass && systemHomes.contains(lastPkg)) openLauncher(false);
    };

    private final Runnable homeHoldTick = new Runnable() {
        @Override
        public void run() {
            long held = SystemClock.uptimeMillis() - homeDownAt;
            if (held >= EXIT_HOLD_MS) {
                homeExitFired = true;
                MainActivity.showHold(-1, "Inicio");
                goToSystemHome(HomeWatcherService.this);
            } else {
                MainActivity.showHold(held, "Inicio");
                handler.postDelayed(this, 200);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        startedAt = SystemClock.uptimeMillis();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        }
        refreshHomes();
        // El launcher de Amazon avisa con esta señal cada vez que se pulsa Inicio (llega antes que la ventana).
        amazonHome = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onHomeSignal();
            }
        };
        IntentFilter f = new IntentFilter("com.amazon.tv.launcher.HOME_PRESSED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(amazonHome, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(amazonHome, f);
        // Al encender o despertar la TV: el launcher sube, salvo que se esté reproduciendo un video.
        screenOn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handler.postDelayed(() -> {
                    if (!bypass && !MediaWatcherService.isPlayingVideo()) openLauncher(false);
                }, 2000);
            }
        };
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        Log.d(TAG, "Servicio del botón Inicio activo. Inicios del sistema: " + systemHomes);
    }

    /** Última tecla del mando (en cualquier app) y desde cuándo está activo este servicio. */
    static volatile long lastKeyAt = 0, startedAt = 0;
    static volatile boolean running = false;

    /** Cuánto lleva el mando sin usarse; -1 si este servicio no está activo (no se sabe). */
    static long idleMillis() {
        if (!running) return -1;
        return SystemClock.uptimeMillis() - Math.max(lastKeyAt, startedAt);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        try {
            if (amazonHome != null) unregisterReceiver(amazonHome);
            if (screenOn != null) unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    /** Se pulsó Inicio (señal de Amazon): termina la salida temporal y vuelve al menú principal del launcher. */
    private void onHomeSignal() {
        if (SystemClock.uptimeMillis() < ignoreUntil) return;
        bypass = false;
        openLauncher(true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        lastPkg = pkg;
        if (pkg.equals(getPackageName()) || bypass || !systemHomes.contains(pkg)) return;
        // Apareció el inicio del sistema (Inicio, encendido, fin de una app): se trae el launcher encima.
        long now = SystemClock.uptimeMillis();
        if (now >= ignoreUntil) openLauncher(false);
        else {
            handler.removeCallbacks(recheck);
            handler.postDelayed(recheck, ignoreUntil - now + 60);
        }
    }

    /** Solo llega donde el sistema deja leer la tecla Inicio (Android TV / Google TV). */
    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        lastKeyAt = SystemClock.uptimeMillis(); // cualquier tecla, en cualquier app: alguien usa el mando
        if (ev.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() == 0) {
                homeDownAt = SystemClock.uptimeMillis();
                homeExitFired = false;
                bypass = false;
                openLauncher(true);
                handler.postDelayed(homeHoldTick, 1000);
            }
        } else if (ev.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeHoldTick);
            if (!homeExitFired) MainActivity.showHold(-1, "Inicio");
        }
        return true;
    }

    /**
     * Trae el launcher al frente en su menú principal. Si el aviso de permisos está abierto, solo una
     * pulsación de Inicio (force) lo interrumpe; la aparición de una ventana nunca lo tapa.
     */
    private void openLauncher(boolean force) {
        if (MainActivity.awaiting && !force) return;
        ignoreUntil = SystemClock.uptimeMillis() + 700; // un solo Inicio no lo abre dos veces
        if (MainActivity.resumed) {
            MainActivity.goMain(); // ya está al frente: solo vuelve al menú principal
            return;
        }
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fromHome", true);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Log.e(TAG, "No se pudo abrir el launcher", ex);
        }
    }

    /**
     * Launchers del sistema que se vigilan. Se excluyen los inicios de respaldo (FallbackHome de Ajustes,
     * con prioridad negativa) y las apps de ajustes y permisos: si no, al abrir Ajustes para dar un permiso
     * el launcher saltaba encima y no dejaba terminar.
     */
    private void refreshHomes() {
        systemHomes.clear();
        systemHomes.addAll(KNOWN_HOMES);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : getPackageManager().queryIntentActivities(home, 0)) {
            String pkg = r.activityInfo.packageName;
            if (r.priority < 0 || isNeverHome(pkg)) continue;
            systemHomes.add(pkg);
        }
        systemHomes.remove(getPackageName());
    }

    private static boolean isNeverHome(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        return p.contains("settings") || p.contains("permissioncontroller") || p.contains("packageinstaller")
                || p.contains("provision") || p.contains("setupwizard") || p.contains("systemui");
    }

    /**
     * Si se dio "adb shell pm grant com.ejemplo.launcher android.permission.WRITE_SECURE_SETTINGS",
     * el launcher activa este servicio solo (y lo vuelve a activar tras actualizar la app o Fire OS).
     */
    static boolean ensureEnabled(Context ctx) {
        if (isEnabled(ctx)) return true;
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false;
        try {
            String me = ctx.getPackageName() + "/" + HomeWatcherService.class.getName();
            String cur = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String next = (cur == null || cur.trim().isEmpty()) ? me : (cur.contains(me) ? cur : cur + ":" + me);
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.d(TAG, "Servicio del botón Inicio activado por el launcher");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "No se pudo activar el servicio del botón Inicio", e);
            return false;
        }
    }

    /** ¿Está activado este servicio en Accesibilidad? */
    static boolean isEnabled(Context ctx) {
        if (instance != null) return true;
        try {
            String list = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return list != null && list.contains(ctx.getPackageName() + "/") && list.contains("HomeWatcherService");
        } catch (Exception e) {
            return false;
        }
    }

    /** Salida del launcher: abre el inicio del sistema y no se le devuelve hasta el próximo Inicio. */
    static void goToSystemHome(Context ctx) {
        bypass = true;
        ignoreUntil = SystemClock.uptimeMillis() + 3000;
        Intent target = systemHomeIntent(ctx);
        if (target != null) {
            try {
                ctx.startActivity(target);
                return;
            } catch (Exception e) {
                Log.e(TAG, "No se pudo abrir el inicio del sistema", e);
            }
        }
        HomeWatcherService s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** El otro launcher instalado, preferiblemente el de fábrica (Amazon o Google). */
    private static Intent systemHomeIntent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        ResolveInfo best = null;
        for (ResolveInfo r : list) {
            if (r.activityInfo == null || ctx.getPackageName().equals(r.activityInfo.packageName)) continue;
            boolean system = (r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (best == null || system) best = r;
            if (system) break;
        }
        if (best == null) return null;
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(best.activityInfo.packageName, best.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
