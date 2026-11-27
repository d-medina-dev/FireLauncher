package com.ejemplo.launcher;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio "Launcher: lo que estás viendo".
 * <p>
 * Lee las sesiones de reproducción de las apps (las mismas que usan Alexa y los controles del sistema):
 * título, serie o artista, carátula, posición y duración. Así "Continuar viendo" muestra la última serie,
 * película o anime que viste, y el launcher muestra la última canción de Spotify. Solo funciona con las
 * apps que publican esa información mientras reproducen. No lee el texto de las notificaciones.
 */
public class MediaWatcherService extends NotificationListenerService {

    private static final String TAG = "Launcher";
    private static final String PREF_KEY = "media";
    private static final int MAX_APPS = 12;

    static volatile MediaWatcherService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Map<String, MediaController> controllers = new HashMap<>();
    private final Map<String, MediaController.Callback> callbacks = new HashMap<>();
    private final Map<String, Long> lastSave = new HashMap<>();
    private final Map<String, String> lastKey = new HashMap<>();
    private final Map<String, Long> triedArt = new HashMap<>();
    private final Map<String, String> lastArtSig = new HashMap<>();
    // Stand by con lo que estabas viendo: desde cuándo está en pausa cada video y si ya se mostró.
    private final Map<String, Long> pausedAt = new HashMap<>();
    private final Map<String, Long> sbFired = new HashMap<>();
    private final Runnable sbCheck = new Runnable() {
        @Override
        public void run() {
            checkVideoStandby();
            handler.postDelayed(this, 20_000);
        }
    };
    private MediaSessionManager msm;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessions;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, MediaWatcherService.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, handler);
            onSessions(msm.getActiveSessions(cn));
            handler.removeCallbacks(sbCheck);
            handler.postDelayed(sbCheck, 20_000);
            // Recomendaciones que las apps ya tienen publicadas (sus tarjetas de "seguir viendo").
            try { for (StatusBarNotification n : getActiveNotifications()) noteRecommendation(n); } catch (Exception ignored) { }
            Log.d(TAG, "Servicio de reproducción activo");
        } catch (Exception e) {
            Log.e(TAG, "No se pudieron leer las sesiones de reproducción", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(sbCheck);
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) {
        }
        for (Map.Entry<String, MediaController> e : controllers.entrySet()) {
            MediaController.Callback cb = callbacks.get(e.getKey());
            if (cb != null) e.getValue().unregisterCallback(cb);
        }
        controllers.clear();
        callbacks.clear();
        if (instance == this) instance = null;
        super.onListenerDisconnected();
    }

    private void onSessions(List<MediaController> list) {
        if (list == null) return;
        List<String> alive = new ArrayList<>();
        for (final MediaController mc : list) {
            final String pkg = mc.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) continue;
            alive.add(pkg);
            MediaController old = controllers.get(pkg);
            if (old != null && old.getSessionToken().equals(mc.getSessionToken())) continue;
            if (old != null && callbacks.get(pkg) != null) old.unregisterCallback(callbacks.get(pkg));
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    record(mc);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    record(mc);
                }
            };
            mc.registerCallback(cb, handler);
            controllers.put(pkg, mc);
            callbacks.put(pkg, cb);
            record(mc);
        }
        Iterator<Map.Entry<String, MediaController>> it = controllers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MediaController> e = it.next();
            if (alive.contains(e.getKey())) continue;
            MediaController.Callback cb = callbacks.remove(e.getKey());
            if (cb != null) e.getValue().unregisterCallback(cb);
            markStopped(e.getKey());
            it.remove();
        }
    }

    /** Guarda lo que suena o se ve (si de verdad se reproduce o quedó en pausa) y avisa al launcher. */
    private void record(MediaController mc) {
        try {
            String pkg = mc.getPackageName();
            MediaMetadata md = mc.getMetadata();
            PlaybackState ps = mc.getPlaybackState();
            if (md == null) return;
            String title = first(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), md.getString(MediaMetadata.METADATA_KEY_TITLE));
            if (title.isEmpty()) return;
            String sub = first(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), md.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION));
            int state = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
            if (!playing && state != PlaybackState.STATE_PAUSED) return;
            long dur = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            long pos = ps != null ? Math.max(0, ps.getPosition()) : 0;
            if (playing && ps.getLastPositionUpdateTime() > 0) {
                pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
            }
            if (dur > 0 && pos > dur) pos = dur;
            String key = title + "|" + sub;
            boolean changed = !key.equals(lastKey.get(pkg));
            // Firma de la imagen que trae la app ahora mismo (para notar cuando cambia la portada).
            final String artSig = artSignature(md);
            boolean artChanged = !artSig.isEmpty() && !artSig.equals(lastArtSig.get(pkg));
            long now = System.currentTimeMillis();
            if (!isMusic(pkg)) {
                if (playing) pausedAt.remove(pkg);
                else if (!pausedAt.containsKey(pkg)) pausedAt.put(pkg, now);
            }
            Long prev = lastSave.get(pkg);
            // Mientras avanza se guarda cada 10 s; un título nuevo, una pausa o un play se guardan al instante.
            JSONObject old = item(pkg);
            boolean stateChanged = old == null || old.optBoolean("playing") != playing;
            if (!changed && !stateChanged && !artChanged && prev != null && now - prev < 10_000) return;
            lastKey.put(pkg, key);
            lastSave.put(pkg, now);

            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("title", title);
            o.put("sub", sub);
            o.put("pos", pos);
            o.put("dur", dur);
            o.put("playing", playing);
            o.put("kind", isMusic(pkg) ? "music" : "video");
            o.put("ts", playing || old == null ? now : old.optLong("ts", now));
            // Con una canción o un capítulo nuevo se deja la portada anterior hasta que llegue la nueva (que se funde
            // encima); así la pantalla no queda en blanco un instante. Si en 8 s no llegó otra, se quita.
            o.put("art", old != null ? old.optString("art", "") : "");
            if (changed && (!o.optString("art", "").isEmpty() && !o.optBoolean("artStale"))) {
                o.put("artStale", true);
                final String sPkg = pkg, sKey = key;
                handler.postDelayed(() -> {
                    try {
                        JSONObject it = item(sPkg);
                        if (it != null && it.optBoolean("artStale") && sKey.equals(lastKey.get(sPkg))) { it.put("art", ""); it.remove("artStale"); save(it); }
                    } catch (Exception ignored) {
                    }
                }, 8000);
            }
            save(o);
            final String fPkg = pkg, fTitle = title, fSub = sub, fKey = key;
            final boolean music = isMusic(pkg);
            Long tried = triedArt.get(pkg + "|" + key);
            boolean retry = old != null && !changed && old.optString("art", "").isEmpty() && (tried == null || now - tried > 10 * 60_000L);
            if (changed && !artSig.isEmpty() && artSig.equals(lastArtSig.get(pkg))) {
                // Título nuevo con la portada anterior (Spotify manda primero el título y luego la imagen):
                // se espera la imagen nueva; si no llega, se vuelve a mirar al segundo y medio.
                triedArt.put(pkg + "|" + key, now);
                handler.postDelayed(() -> {
                    MediaController c = controllers.get(fPkg);
                    if (c == null || !fKey.equals(lastKey.get(fPkg))) return;
                    JSONObject it = item(fPkg);
                    MediaMetadata m2 = c.getMetadata();
                    if (it == null || m2 == null || (!it.optString("art", "").isEmpty() && !it.optBoolean("artStale"))) return;
                    lastArtSig.put(fPkg, artSignature(m2));
                    fetchArt(fPkg, m2, System.currentTimeMillis(), fTitle, fSub, music);
                }, 1500);
            } else if (changed || artChanged || retry) {
                triedArt.put(pkg + "|" + key, now);
                lastArtSig.put(pkg, artSig);
                fetchArt(pkg, md, now, title, sub, music);
            }
        } catch (Exception e) {
            Log.e(TAG, "No se pudo guardar lo que se reproduce", e);
        }
    }

    private void markStopped(String pkg) {
        try {
            JSONObject old = item(pkg);
            if (old == null || !old.optBoolean("playing")) return;
            old.put("playing", false);
            save(old);
        } catch (Exception ignored) {
        }
    }

    /**
     * Carátula / miniatura de lo que se ve, en este orden:
     * 1) la imagen que trae la sesión de reproducción;
     * 2) la dirección de imagen de la sesión (si la app deja leerla);
     * 3) búsqueda por el título: TVmaze (series, imagen horizontal), Kitsu (anime) e iTunes (películas).
     * Además, si la app publica una notificación de reproducción, su imagen grande (la miniatura real del
     * episodio) reemplaza a la encontrada por búsqueda. Queda guardada en un archivo por app.
     */
    private void fetchArt(final String pkg, MediaMetadata md, final long stamp, final String title, final String sub, final boolean music) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        // DISPLAY_ICON: muchas apps de TV ponen ahí su propio logo; solo sirve si no lo es.
        final Bitmap icon = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        final Bitmap given = b;
        final String uri = first(md.getString(MediaMetadata.METADATA_KEY_ART_URI), md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        bg.execute(() -> {
            StringBuilder why = new StringBuilder();
            Bitmap bmp = usable(pkg, given, music, why, "La imagen que manda la app");
            String src = "app";
            if (bmp == null && !uri.isEmpty()) {
                try {
                    bmp = usable(pkg, loadUri(uri), music, why, "La imagen enlazada por la app");
                } catch (Exception e) {
                    why.append("La app no deja leer su imagen. ");
                    Log.d(TAG, "La app no deja leer su imagen (" + pkg + "): " + e);
                }
            }
            if (bmp == null) bmp = usable(pkg, icon, music, why, "El ícono que manda la app");
            if (bmp == null && given == null && icon == null && uri.isEmpty()) why.append("La app no comparte ninguna imagen. ");
            boolean guess = false;
            if (bmp == null && !music) {
                String rp = recArt(pkg, title, sub);
                Bitmap rb = rp != null ? BitmapFactory.decodeFile(rp) : null;
                if (rb != null) { bmp = rb; src = "recomendación de la app"; }
            }
            if (bmp == null && !music) {
                String wu = tvProviderArt(pkg, title, sub);
                if (wu != null) {
                    try {
                        Bitmap wb = loadUri(wu);
                        if (wb != null && !looksLikeAppIcon(pkg, wb)) { bmp = wb; src = "sistema (Seguir viendo)"; }
                    } catch (Exception e) {
                        why.append("El sistema tiene la imagen pero no deja leerla. ");
                    }
                }
            }
            if (bmp == null && !music) {
                try {
                    bmp = lookupArt(title, sub);
                    guess = bmp != null;
                    src = "búsqueda";
                    if (bmp == null) why.append("No se encontró «").append(title).append(tmdbKey().isEmpty()
                            ? "» en Wikipedia, TVmaze, Kitsu ni iTunes (con una clave de TMDB se encuentran más; ver LEEME)."
                            : "» en TMDB, Wikipedia, TVmaze, Kitsu ni iTunes.");
                } catch (Exception e) {
                    why.append("La búsqueda por internet falló (").append(e.getClass().getSimpleName()).append(").");
                }
            }
            if (bmp != null) saveArt(pkg, bmp, stamp, guess, src);
            else noteWhy(pkg, why.toString().trim());
        });
    }

    /** Sirve como miniatura si existe y no es el logo de la app (para música vale cualquier portada). */
    private Bitmap usable(String pkg, Bitmap bmp, boolean music, StringBuilder why, String what) {
        if (bmp == null) return null;
        if (music) return bmp;
        if (Math.max(bmp.getWidth(), bmp.getHeight()) < 100 || looksLikeAppIcon(pkg, bmp)) {
            why.append(what).append(" es su logo, no la miniatura. ");
            return null;
        }
        return bmp;
    }

    /** Compara con el ícono y el banner de la app (huella de 8×8): parecidos = es el logo. */
    private boolean looksLikeAppIcon(String pkg, Bitmap bmp) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            List<Drawable> cands = new ArrayList<>();
            try { cands.add(pm.getApplicationIcon(pkg)); } catch (Exception ignored) { }
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                Drawable banner = ai.loadBanner(pm);
                if (banner != null) cands.add(banner);
            } catch (Exception ignored) { }
            long h = aHash(bmp);
            for (Drawable d : cands) {
                Bitmap ib = toBitmap(d);
                if (ib != null && Long.bitCount(h ^ aHash(ib)) <= 10) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static long aHash(Bitmap b) {
        Bitmap sm = Bitmap.createScaledBitmap(b, 8, 8, true);
        int[] px = new int[64];
        sm.getPixels(px, 0, 8, 0, 0, 8, 8);
        int[] g = new int[64];
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            int p = px[i], a = p >>> 24;
            int y = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
            y = (y * a + 128 * (255 - a)) / 255; // las partes transparentes cuentan como gris
            g[i] = y;
            sum += y;
        }
        long avg = sum / 64, h = 0;
        for (int i = 0; i < 64; i++) if (g[i] > avg) h |= 1L << i;
        return h;
    }

    /** Firma corta de la imagen actual: tamaño y 16 píxeles de muestra (o la dirección, si solo hay dirección). */
    private static String artSignature(MediaMetadata md) {
        try {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b != null) {
                int w = b.getWidth(), h = b.getHeight();
                long acc = w * 31L + h;
                for (int i = 1; i <= 4; i++) for (int j = 1; j <= 4; j++) acc = acc * 31 + b.getPixel(w * i / 5, h * j / 5);
                return "b" + Long.toHexString(acc);
            }
            String u = first(md.getString(MediaMetadata.METADATA_KEY_ART_URI), md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            return u.isEmpty() ? "" : "u" + u;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- enlace con las apps de streaming

    /**
     * Recomendaciones: en Fire TV y Android TV las apps publican sus tarjetas de "seguir viendo" como
     * notificaciones ocultas, con el título y la imagen de la serie o película. Se guardan por app.
     */
    private void noteRecommendation(StatusBarNotification sbn) {
        try {
            final String pkg = sbn.getPackageName();
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null || pkg.equals(getPackageName())) return;
            Bundle x = n.extras;
            boolean rec = Notification.CATEGORY_RECOMMENDATION.equals(n.category);
            if (!rec) for (String k : x.keySet()) if (k.startsWith("com.amazon.extra")) { rec = true; break; }
            if (!rec) return;
            final String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
            final String text = str(x.getCharSequence(Notification.EXTRA_TEXT));
            if (title.isEmpty()) return;
            Bitmap b = null;
            if (n.getLargeIcon() != null) b = toBitmap(n.getLargeIcon().loadDrawable(this));
            if (b == null) {
                Object li = x.get(Notification.EXTRA_LARGE_ICON);
                if (li instanceof Bitmap) b = (Bitmap) li;
            }
            String u = null;
            for (String k : new String[]{"android.backgroundImageUri", "com.amazon.extra.BACKGROUND_IMAGE_URI"}) {
                Object v = x.get(k);
                if (v != null) { u = v.toString(); break; }
            }
            final Bitmap b0 = b;
            final String uri = u;
            if (b0 == null && uri == null) return;
            bg.execute(() -> {
                try {
                    Bitmap bmp = b0 != null ? b0 : loadUri(uri);
                    if (bmp == null || looksLikeAppIcon(pkg, bmp)) return;
                    final File f = writeJpeg("rec_" + pkg.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + Integer.toHexString(norm(title).hashCode()), bmp);
                    if (f == null) return;
                    handler.post(() -> { addRec(pkg, title, text, f.getAbsolutePath()); applyRecs(pkg); });
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }

    private File writeJpeg(String name, Bitmap bmp) throws Exception {
        int w = bmp.getWidth(), h = bmp.getHeight(), max = 480;
        if (Math.max(w, h) > max) {
            float k = max / (float) Math.max(w, h);
            bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        }
        File dir = new File(getFilesDir(), "art");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File f = new File(dir, name + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 84, out);
        }
        return f;
    }

    private void addRec(String pkg, String title, String text, String path) {
        try {
            SharedPreferences p = getSharedPreferences("launcher_recs", MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString(pkg, "[]")), out = new JSONArray();
            JSONObject r = new JSONObject();
            r.put("t", title); r.put("x", text); r.put("f", path); r.put("ts", System.currentTimeMillis());
            out.put(r);
            for (int i = 0; i < old.length() && out.length() < 12; i++) {
                JSONObject o = old.getJSONObject(i);
                if (!o.optString("t").equals(title)) out.put(o);
            }
            p.edit().putString(pkg, out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Imagen de una recomendación de la app que corresponda a lo que se vio (por el título). */
    private String recArt(String pkg, String title, String sub) {
        try {
            JSONArray list = new JSONArray(getSharedPreferences("launcher_recs", MODE_PRIVATE).getString(pkg, "[]"));
            List<String> qs = queries(title, sub);
            qs.add(title);
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                for (String q : qs) {
                    if (sameName(r.optString("t"), q) || sameName(r.optString("x"), q)) {
                        if (new File(r.optString("f")).exists()) return r.optString("f");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Llegó una recomendación nueva: si lo último que se vio en esa app no tiene miniatura, se usa. */
    private void applyRecs(final String pkg) {
        JSONObject it = item(pkg);
        if (it == null || isMusic(pkg)) return;
        if ((!it.optString("art", "").isEmpty() && !it.optBoolean("artStale")) && !it.optBoolean("artGuess", false)) return;
        final String path = recArt(pkg, it.optString("title"), it.optString("sub"));
        if (path == null) return;
        bg.execute(() -> {
            Bitmap b = BitmapFactory.decodeFile(path);
            if (b != null) saveArt(pkg, b, System.currentTimeMillis(), false, "recomendación de la app");
        });
    }

    /**
     * Lo que el propio televisor ya sabe (Android TV / Google TV y Fire TV con Android 8+): la lista "Seguir viendo"
     * (Watch Next) y los programas de los canales de las apps. Traen la miniatura del capítulo exacto.
     * Coincide por serie, capítulo o temporada/episodio; si nada coincide, usa lo más reciente de esa app (6 h).
     */
    private String tvProviderArt(String pkg, String title, String sub) {
        if (Build.VERSION.SDK_INT < 26) return null;
        Ep ep = parseEp(title, sub);
        String best = null, recent = null;
        int bestScore = 0;
        long recentAt = 0;
        android.net.Uri[] uris = { android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI };
        for (int u = 0; u < uris.length; u++) {
            String[] cols = u == 0
                    ? new String[]{"package_name", "title", "episode_title", "season_display_number", "episode_display_number", "thumbnail_uri", "poster_art_uri", "last_engagement_time_utc_millis"}
                    : new String[]{"package_name", "title", "episode_title", "season_display_number", "episode_display_number", "thumbnail_uri", "poster_art_uri"};
            try (Cursor c = getContentResolver().query(uris[u], cols, null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    if (!pkg.equals(c.getString(0))) continue;
                    String img = c.getString(5) != null && !c.getString(5).isEmpty() ? c.getString(5) : c.getString(6);
                    if (img == null || img.isEmpty()) continue;
                    String t = c.getString(1), et = c.getString(2);
                    int sc = 0;
                    if (t != null && (sameName(t, ep.series) || sameName(t, title))) sc += 2;
                    if (et != null && !ep.episode.isEmpty() && sameName(et, ep.episode)) sc += 3;
                    if (ep.e > 0 && String.valueOf(ep.e).equals(c.getString(4))) sc += (ep.s <= 0 || String.valueOf(ep.s).equals(c.getString(3))) ? 3 : 1;
                    if (sc > bestScore) { bestScore = sc; best = img; }
                    if (u == 0) {
                        long at = c.isNull(7) ? 0 : c.getLong(7);
                        if (at > recentAt) { recentAt = at; recent = img; }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Programas del sistema no disponibles: " + e);
            }
        }
        if (best != null) return best;
        if (recent != null && System.currentTimeMillis() - recentAt < 6 * 3600_000L) return recent;
        return null;
    }

    // ---------------------------------------------------------------- stand by con el video en pausa

    /**
     * Si un video lleva en pausa el tiempo del stand by (por ejemplo, con el aviso de "¿sigues ahí?") y nadie
     * toca el mando, se abre el launcher en stand by mostrando lo que estabas viendo.
     */
    private void checkVideoStandby() {
        try {
            if (MainActivity.resumed || pausedAt.isEmpty()) return;
            SharedPreferences p = getSharedPreferences("launcher", MODE_PRIVATE);
            if (!p.getBoolean("sbOn", true)) return;
            long wait = Math.max(1, p.getInt("sbMin", 10)) * 60_000L;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
            long idle = HomeWatcherService.idleMillis();
            if (idle < 0 || idle < wait) return; // sin saber si usan el mando, no se interrumpe nada
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : pausedAt.entrySet()) {
                String pkg = e.getKey();
                long since = e.getValue();
                if (now - since < wait) continue;
                Long fired = sbFired.get(pkg);
                if (fired != null && fired == since) continue;
                MediaController c = controllers.get(pkg);
                PlaybackState ps = c != null ? c.getPlaybackState() : null;
                if (ps == null || ps.getState() != PlaybackState.STATE_PAUSED) continue;
                sbFired.put(pkg, since);
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sbVideo", pkg));
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "Stand by con video: " + e);
        }
    }

    /** Guarda por qué no hay miniatura (se muestra en Continuar viendo). */
    private void noteWhy(final String pkg, final String why) {
        handler.post(() -> {
            try {
                JSONObject o = item(pkg);
                if (o == null || (!o.optString("art", "").isEmpty() && !o.optBoolean("artStale"))) return;
                o.put("artWhy", why);
                    if (o.optBoolean("artStale")) { o.put("art", ""); o.remove("artStale"); }
                save(o);
            } catch (Exception ignored) {
            }
        });
    }

    /** Guarda la imagen (máx. 480 px) y la asocia a lo último que se vio en esa app. Se llama desde el hilo bg. */
    private void saveArt(final String pkg, Bitmap bmp, final long stamp, final boolean guess, final String src) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight(), max = 480;
            if (Math.max(w, h) > max) {
                float k = max / (float) Math.max(w, h);
                bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
            }
            File dir = new File(getFilesDir(), "art");
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, pkg.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
            // El sello de la dirección es la huella de la imagen: si la app manda la misma portada otra vez
            // (pasa al pausar), la dirección no cambia y la pantalla no recarga nada.
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 84, bytes);
            byte[] data = bytes.toByteArray();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            final String art = "file://" + f.getAbsolutePath() + "?v=" + Long.toHexString(crc.getValue());
            JSONObject cur = item(pkg);
            if (cur == null || !art.equals(cur.optString("art", "")) || !f.exists()) {
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            }
            handler.post(() -> {
                try {
                    JSONObject o = item(pkg);
                    if (o == null) return;
                    if (art.equals(o.optString("art", "")) && !o.optBoolean("artStale") && o.optBoolean("artGuess") == guess) return;
                    o.put("art", art);
                    o.remove("artStale");
                    o.put("artGuess", guess);
                    o.put("artSrc", src);
                    o.remove("artWhy");
                    save(o);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            Log.d(TAG, "No se pudo guardar la imagen de " + pkg + ": " + e);
        }
    }

    /** Notificación de reproducción: su imagen grande suele ser la miniatura real del episodio o video. */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        noteRecommendation(sbn);
        try {
            final String pkg = sbn.getPackageName();
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null || pkg.equals(getPackageName())) return;
            boolean media = n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || Notification.CATEGORY_TRANSPORT.equals(n.category);
            if (!media) return;
            JSONObject o = item(pkg);
            if (o == null || ((!o.optString("art", "").isEmpty() && !o.optBoolean("artStale")) && !o.optBoolean("artGuess", false))) return;
            Bitmap b = null;
            if (n.getLargeIcon() != null) b = toBitmap(n.getLargeIcon().loadDrawable(this));
            if (b == null) {
                Object li = n.extras.get(Notification.EXTRA_LARGE_ICON);
                if (li instanceof Bitmap) b = (Bitmap) li;
            }
            if (b == null) return;
            final Bitmap bmp = b;
            final boolean music = isMusic(pkg);
            bg.execute(() -> {
                if (usable(pkg, bmp, music, new StringBuilder(), "") != null) saveArt(pkg, bmp, System.currentTimeMillis(), false, "notificación");
            });
        } catch (Exception ignored) {
        }
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
        if (w <= 1 || h <= 1) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    // ---------------------------------------------------------------- búsqueda de imagen por título

    /** Serie, capítulo y números (temporada/episodio) a partir de lo que publica la app. */
    private static final class Ep { String series = "", episode = ""; int s = -1, e = -1; }

    private static final java.util.regex.Pattern EP_SE = java.util.regex.Pattern.compile(
            "(?i)\\b(?:t|s|temporada|season)\\s*(\\d+)\\s*[,·:.-]?\\s*(?:e|ep|episodio|episode|cap|capítulo)\\.?\\s*(\\d+)\\b|\\bs(\\d+)\\s*e(\\d+)\\b");
    private static final java.util.regex.Pattern EP_E = java.util.regex.Pattern.compile("(?i)\\b(?:e|ep|episodio|episode|capítulo|cap)\\.?\\s*(\\d+)\\b");

    private static String cleanEp(String x) {
        if (x == null) return "";
        String c = EP_SE.matcher(x).replaceAll(" ");
        c = EP_E.matcher(c).replaceAll(" ");
        return c.replaceAll("\\s+", " ").replaceAll("^[\\s·:,|.\\-–—]+|[\\s·:,|.\\-–—]+$", "").trim();
    }

    static Ep parseEp(String title, String sub) {
        Ep r = new Ep();
        String t = title == null ? "" : title.trim(), u = sub == null ? "" : sub.trim();
        for (String x : new String[]{t, u}) {
            java.util.regex.Matcher m = EP_SE.matcher(x);
            if (m.find()) { r.s = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3)); r.e = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(4)); break; }
        }
        if (r.e < 0) for (String x : new String[]{t, u}) { java.util.regex.Matcher m = EP_E.matcher(x); if (m.find()) { r.e = Integer.parseInt(m.group(1)); break; } }
        boolean tm = EP_SE.matcher(t).find() || EP_E.matcher(t).find(), um = EP_SE.matcher(u).find() || EP_E.matcher(u).find();
        if (!u.isEmpty() && !um && !t.isEmpty()) { r.series = u; r.episode = cleanEp(t); }          // título = capítulo, subtítulo = serie
        else if (um && !t.isEmpty()) { r.series = cleanEp(t); r.episode = cleanEp(u); }            // subtítulo = "T1 E14 · nombre"
        else if (!t.isEmpty()) {                                                                    // "Serie: capítulo" o "Serie - capítulo"
            String[] parts = t.split("\\s[-–—·|]\\s|:\\s", 2);
            r.series = cleanEp(parts[0]);
            r.episode = parts.length > 1 ? cleanEp(parts[1]) : (tm ? "" : "");
        } else r.series = cleanEp(u);
        if (r.episode.equalsIgnoreCase(r.series)) r.episode = "";
        return r;
    }

    /**
     * Busca la imagen por internet con la serie y el capítulo juntos:
     * 1) miniatura del capítulo exacto (TMDB con clave; TVmaze por número o por nombre del capítulo);
     * 2) Wikipedia con "serie + capítulo" (artículo del capítulo) y luego la serie;
     * 3) la serie en TMDB, TVmaze, Kitsu e iTunes. Cada búsqueda se recuerda para no repetirla.
     */
    private Bitmap lookupArt(String title, String sub) throws Exception {
        final Ep ep = parseEp(title, sub);
        List<String> series = new ArrayList<>();
        for (String q : new String[]{ep.series, ep.series.split(":")[0].trim()}) if (q.length() >= 2 && !series.contains(q)) series.add(q);
        if (series.isEmpty()) series = queries(title, sub);
        final String epKey = ep.s + "x" + ep.e + "|" + ep.episode;
        for (String q : series) {
            if (!tmdbKey().isEmpty() && ep.e > 0) { String u = cached("tme:" + q + "|" + epKey, () -> tmdbEpisode(q, ep)); if (!u.isEmpty()) return loadUri(u); }
            if (ep.e > 0 || !ep.episode.isEmpty()) { String u = cached("tve:" + q + "|" + epKey, () -> tvmazeEpisode(q, ep)); if (!u.isEmpty()) return loadUri(u); }
        }
        if (!ep.episode.isEmpty()) {
            for (String q : series) {
                String u = cached("wee:" + q + "|" + ep.episode, () -> wikiEpisode(q, ep.episode, "es"));
                if (u.isEmpty()) u = cached("wne:" + q + "|" + ep.episode, () -> wikiEpisode(q, ep.episode, "en"));
                if (!u.isEmpty()) return loadUri(u);
            }
        }
        List<String> qs = new ArrayList<>(series);
        for (String q : queries(title, sub)) if (!qs.contains(q)) qs.add(q);
        if (!tmdbKey().isEmpty()) {
            for (String q : qs) { String u = cached("tm:" + q, () -> tmdb(q)); if (!u.isEmpty()) return loadUri(u); }
        }
        for (String q : qs) { String u = cached("we:" + q, () -> wikipedia(q, "es")); if (!u.isEmpty()) return loadUri(u); }
        for (String q : qs) { String u = cached("wn:" + q, () -> wikipedia(q, "en")); if (!u.isEmpty()) return loadUri(u); }
        for (String q : qs) { String u = cached("tv:" + q, () -> tvmaze(q)); if (!u.isEmpty()) return loadUri(u); }
        for (String q : qs) { String u = cached("an:" + q, () -> kitsu(q)); if (!u.isEmpty()) return loadUri(u); }
        for (String q : qs) { String u = cached("mv:" + q, () -> itunes(q)); if (!u.isEmpty()) return loadUri(u); }
        return null;
    }

    /** TVmaze: imagen del capítulo por temporada y número, o buscando el nombre del capítulo en la lista de episodios. */
    private String tvmazeEpisode(String series, Ep ep) throws Exception {
        JSONObject show = new JSONObject(httpText("https://api.tvmaze.com/singlesearch/shows?q=" + URLEncoder.encode(series, "UTF-8")));
        if (!sameName(show.optString("name"), series)) return "";
        int id = show.getInt("id");
        if (ep.e > 0) {
            try {
                JSONObject e = new JSONObject(httpText("https://api.tvmaze.com/shows/" + id + "/episodebynumber?season=" + Math.max(1, ep.s) + "&number=" + ep.e));
                JSONObject im = e.optJSONObject("image");
                if (im != null) return im.optString("original", im.optString("medium", ""));
            } catch (Exception ignored) {
            }
        }
        if (!ep.episode.isEmpty()) {
            JSONArray eps = new JSONArray(httpText("https://api.tvmaze.com/shows/" + id + "/episodes"));
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i);
                JSONObject im = e.optJSONObject("image");
                if (im != null && sameName(e.optString("name"), ep.episode)) return im.optString("original", im.optString("medium", ""));
            }
        }
        return "";
    }

    /** TMDB (con clave): imagen del capítulo (still) por temporada y número. */
    private String tmdbEpisode(String series, Ep ep) throws Exception {
        String key = tmdbKey();
        if (key.isEmpty() || ep.e <= 0) return "";
        boolean bearer = key.length() > 60;
        String auth = bearer ? "" : "&api_key=" + URLEncoder.encode(key, "UTF-8");
        JSONObject r = new JSONObject(httpText("https://api.themoviedb.org/3/search/tv?language=es-MX&query=" + URLEncoder.encode(series, "UTF-8") + auth, bearer ? key : null));
        JSONArray res = r.optJSONArray("results");
        if (res == null || res.length() == 0) return "";
        JSONObject show = res.getJSONObject(0);
        if (!sameName(show.optString("name"), series) && !sameName(show.optString("original_name"), series)) return "";
        JSONObject e = new JSONObject(httpText("https://api.themoviedb.org/3/tv/" + show.getInt("id") + "/season/" + Math.max(1, ep.s) + "/episode/" + ep.e + "?language=es-MX" + auth, bearer ? key : null));
        String sp = e.optString("still_path", "");
        return sp.isEmpty() || "null".equals(sp) ? "" : "https://image.tmdb.org/t/p/w780" + sp;
    }

    /** Wikipedia: artículo del capítulo buscando "serie + capítulo"; el título debe tener el capítulo y la serie. */
    private String wikiEpisode(String series, String episode, String lang) throws Exception {
        String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrlimit=6"
                + "&prop=pageimages&piprop=thumbnail&pithumbsize=640&gsrsearch=" + URLEncoder.encode(series + " " + episode, "UTF-8");
        JSONObject qo = new JSONObject(httpText(url)).optJSONObject("query");
        JSONArray pages = qo != null ? qo.optJSONArray("pages") : null;
        if (pages == null) return "";
        String first = norm(series).split(" ")[0];
        for (int i = 0; i < pages.length(); i++) {
            JSONObject pg = pages.getJSONObject(i);
            JSONObject th = pg.optJSONObject("thumbnail");
            if (th == null) continue;
            String t = norm(pg.optString("title"));
            if (t.contains(norm(episode)) && (t.contains(first) || t.contains(norm(series)))) return th.optString("source", "");
        }
        return "";
    }

    private interface Finder { String find() throws Exception; }

    private String cached(String key, Finder f) {
        SharedPreferences p = getSharedPreferences("launcher_art", MODE_PRIVATE);
        if (p.contains(key)) return p.getString(key, "");
        String u = "";
        try {
            u = f.find();
            if (u == null) u = "";
        } catch (Exception e) {
            Log.d(TAG, "Sin imagen en " + key + ": " + e);
            if (!(e instanceof java.io.FileNotFoundException)) return ""; // error de red: se reintenta otra vez
        }
        p.edit().putString(key, u).apply();
        return u;
    }

    /**
     * Títulos a probar. Si un texto trae marcas de episodio ("T1 E14", "S02E03", "Episodio 3"…), lo que queda
     * suele ser el nombre del episodio y no el de la serie, así que no se usa (evita imágenes de otra serie).
     * De los demás se prueba el texto completo y lo que va antes de ":" o " - ".
     */
    private static List<String> queries(String title, String sub) {
        java.util.regex.Pattern ep = java.util.regex.Pattern.compile(
                "(?i)\\b(t|s|temporada|season)\\s*\\d+\\s*[,·:.-]?\\s*(e|ep|episodio|episode|cap|capítulo)\\.?\\s*\\d+\\b"
                        + "|\\b(s\\d+e\\d+|e\\d+|ep\\.?\\s*\\d+|episodio\\s*\\d+|episode\\s*\\d+|capítulo\\s*\\d+)\\b");
        List<String> out = new ArrayList<>();
        for (String raw : new String[]{title, sub}) {
            if (raw == null || raw.trim().isEmpty()) continue;
            if (ep.matcher(raw).find()) continue;
            String c = raw.replaceAll("\\s+", " ").replaceAll("^[\\s·:,|.\\-–—]+|[\\s·:,|.\\-–—]+$", "").trim();
            if (c.length() >= 2 && !out.contains(c)) out.add(c);
            String head = c.split("\\s[-–—·|]\\s|:")[0].replaceAll("[\\s·:,|.\\-–—]+$", "").trim();
            if (head.length() >= 2 && !out.contains(head)) out.add(head);
        }
        return out;
    }

    private static String norm(String t) {
        String n = Normalizer.normalize(t == null ? "" : t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Evita imágenes de otra serie: el nombre encontrado y el buscado tienen que coincidir. */
    private static boolean sameName(String found, String query) {
        String a = norm(found), b = norm(query);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.contains(b) || b.contains(a) || a.split(" ")[0].equals(b.split(" ")[0]) && a.split(" ").length > 1 && b.split(" ").length > 1;
    }

    /** Clave de TMDB: la de res/values/keys.xml (o una guardada por el launcher). Vacía = no se usa. */
    private String tmdbKey() {
        try {
            int id = getResources().getIdentifier("tmdb_key", "string", getPackageName());
            String k = id != 0 ? getString(id).trim() : "";
            if (!k.isEmpty()) return k;
        } catch (Exception ignored) {
        }
        return getSharedPreferences("launcher", MODE_PRIVATE).getString("tmdb_key", "").trim();
    }

    /**
     * TMDB: la base de datos que usan casi todas las apps de series y películas. Busca en español
     * ("Merlina" encuentra "Wednesday") y prefiere la imagen horizontal de fondo (backdrop).
     */
    private String tmdb(String q) throws Exception {
        String key = tmdbKey();
        if (key.isEmpty()) return "";
        boolean bearer = key.length() > 60; // token de lectura v4 en lugar de la clave v3
        String url = "https://api.themoviedb.org/3/search/multi?include_adult=false&language=es-MX&query=" + URLEncoder.encode(q, "UTF-8")
                + (bearer ? "" : "&api_key=" + URLEncoder.encode(key, "UTF-8"));
        JSONObject r = new JSONObject(httpText(url, bearer ? key : null));
        JSONArray res = r.optJSONArray("results");
        if (res == null) return "";
        for (int i = 0; i < res.length() && i < 6; i++) {
            JSONObject o = res.getJSONObject(i);
            String type = o.optString("media_type");
            if (!"tv".equals(type) && !"movie".equals(type)) continue;
            String n1 = o.optString("name", o.optString("title", "")), n2 = o.optString("original_name", o.optString("original_title", ""));
            if (!sameName(n1, q) && !sameName(n2, q)) continue;
            String bp = o.optString("backdrop_path", ""), pp = o.optString("poster_path", "");
            if (!bp.isEmpty() && !"null".equals(bp)) return "https://image.tmdb.org/t/p/w780" + bp;
            if (!pp.isEmpty() && !"null".equals(pp)) return "https://image.tmdb.org/t/p/w500" + pp;
        }
        return "";
    }

    /**
     * Wikipedia: busca el artículo y usa su imagen principal (póster o captura). Prefiere los artículos de
     * series, películas o anime, por ejemplo "Merlina (serie de televisión)".
     */
    private String wikipedia(String q, String lang) throws Exception {
        String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrlimit=6"
                + "&prop=pageimages&piprop=thumbnail&pithumbsize=640&gsrsearch=" + URLEncoder.encode(q, "UTF-8");
        JSONObject r = new JSONObject(httpText(url));
        JSONObject qo = r.optJSONObject("query");
        JSONArray pages = qo != null ? qo.optJSONArray("pages") : null;
        if (pages == null) return "";
        String best = "", any = "";
        int bestIdx = 99, anyIdx = 99;
        String[] kinds = lang.equals("es")
                ? new String[]{"serie", "película", "pelicula", "anime", "telenovela", "programa"}
                : new String[]{"series", "film", "anime", "tv", "show"};
        for (int i = 0; i < pages.length(); i++) {
            JSONObject pg = pages.getJSONObject(i);
            JSONObject th = pg.optJSONObject("thumbnail");
            if (th == null || th.optString("source").isEmpty()) continue;
            String title = pg.optString("title");
            String base = title.replaceAll("\\s*\\(.*\\)$", "");
            if (!sameName(base, q)) continue;
            int idx = pg.optInt("index", 50);
            String lt = norm(title);
            boolean media = false;
            for (String k : kinds) if (lt.contains(norm(k))) { media = true; break; }
            if (media && idx < bestIdx) { best = th.optString("source"); bestIdx = idx; }
            if (idx < anyIdx) { any = th.optString("source"); anyIdx = idx; }
        }
        return !best.isEmpty() ? best : any;
    }

    /** TVmaze (series): prefiere su imagen horizontal de fondo; si no, el póster. */
    private String tvmaze(String q) throws Exception {
        JSONObject show = new JSONObject(httpText("https://api.tvmaze.com/singlesearch/shows?q=" + URLEncoder.encode(q, "UTF-8")));
        if (!sameName(show.optString("name"), q)) return "";
        try {
            JSONArray imgs = new JSONArray(httpText("https://api.tvmaze.com/shows/" + show.getInt("id") + "/images"));
            for (int i = 0; i < imgs.length(); i++) {
                JSONObject im = imgs.getJSONObject(i);
                if (!"background".equals(im.optString("type"))) continue;
                JSONObject res = im.optJSONObject("resolutions");
                JSONObject o = res != null ? (res.optJSONObject("medium") != null ? res.optJSONObject("medium") : res.optJSONObject("original")) : null;
                if (o != null && !o.optString("url").isEmpty()) return o.optString("url");
            }
        } catch (Exception ignored) {
        }
        JSONObject im = show.optJSONObject("image");
        return im != null ? im.optString("original", im.optString("medium", "")) : "";
    }

    /** Kitsu (anime): portada horizontal; si no, el póster. */
    private String kitsu(String q) throws Exception {
        JSONObject r = new JSONObject(httpText("https://kitsu.io/api/edge/anime?page%5Blimit%5D=1&filter%5Btext%5D=" + URLEncoder.encode(q, "UTF-8")));
        JSONArray data = r.optJSONArray("data");
        if (data == null || data.length() == 0) return "";
        JSONObject at = data.getJSONObject(0).optJSONObject("attributes");
        if (at == null) return "";
        JSONObject titles = at.optJSONObject("titles");
        boolean ok = sameName(at.optString("canonicalTitle"), q)
                || (titles != null && (sameName(titles.optString("en"), q) || sameName(titles.optString("en_jp"), q)));
        if (!ok) return "";
        JSONObject cover = at.optJSONObject("coverImage"), poster = at.optJSONObject("posterImage");
        if (cover != null && !cover.optString("large").isEmpty()) return cover.optString("large");
        return poster != null ? poster.optString("large", poster.optString("medium", "")) : "";
    }

    /** iTunes (películas y series): portada en 600 px; prueba la tienda de México (títulos en español) y la de EE. UU. */
    private String itunes(String q) throws Exception {
        for (String country : new String[]{"MX", "US"}) for (String media : new String[]{"movie", "tvShow"}) {
            JSONObject r = new JSONObject(httpText("https://itunes.apple.com/search?limit=1&country=" + country + "&media=" + media
                    + "&term=" + URLEncoder.encode(q, "UTF-8")));
            JSONArray res = r.optJSONArray("results");
            if (res == null || res.length() == 0) continue;
            JSONObject o = res.getJSONObject(0);
            String name = o.optString("trackName", o.optString("collectionName", ""));
            String art = o.optString("artworkUrl100", "");
            if (!art.isEmpty() && sameName(name, q)) return art.replace("100x100bb", "600x600bb");
        }
        return "";
    }

    private static String httpText(String url) throws Exception {
        return httpText(url, null);
    }

    private static String httpText(String url, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", "FireLauncher/1.1 (Android TV)");
        c.setRequestProperty("Accept", "application/json, application/vnd.api+json");
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new java.io.FileNotFoundException(url);
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private Bitmap loadUri(String uri) throws Exception {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        }
        try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) {
            return in != null ? BitmapFactory.decodeStream(in) : null;
        }
    }

    // ---------------------------------------------------------------- datos guardados

    private SharedPreferences prefs() {
        return getSharedPreferences("launcher", MODE_PRIVATE);
    }

    private JSONObject data() {
        try {
            return new JSONObject(prefs().getString(PREF_KEY, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject item(String pkg) {
        JSONObject apps = data().optJSONObject("apps");
        return apps != null ? apps.optJSONObject(pkg) : null;
    }

    /** {video: último video, music: última canción, apps: {paquete: último de esa app}}. */
    private void save(JSONObject o) throws Exception {
        JSONObject d = data();
        JSONObject apps = d.optJSONObject("apps");
        if (apps == null) apps = new JSONObject();
        apps.put(o.getString("pkg"), o);
        // se quedan las 12 apps más recientes
        while (apps.length() > MAX_APPS) {
            String oldest = null;
            long t = Long.MAX_VALUE;
            Iterator<String> keys = apps.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                long ts = apps.getJSONObject(k).optLong("ts", 0);
                if (ts < t) { t = ts; oldest = k; }
            }
            apps.remove(oldest);
        }
        d.put("apps", apps);
        String kind = o.optString("kind");
        JSONObject cur = d.optJSONObject(kind);
        if (cur == null || cur.optString("pkg").equals(o.getString("pkg")) || o.optLong("ts") >= cur.optLong("ts")) d.put(kind, o);
        prefs().edit().putString(PREF_KEY, d.toString()).apply();
        MainActivity.onMediaChanged();
    }

    static String json(Context ctx) {
        try {
            JSONObject d = new JSONObject(ctx.getSharedPreferences("launcher", MODE_PRIVATE).getString(PREF_KEY, "{}"));
            d.put("access", isEnabled(ctx));
            return d.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isMusic(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("spotify") || p.contains("music") || p.contains("deezer") || p.contains("tidal") || p.contains("pandora")
                || p.contains("soundcloud") || p.contains("radio") || p.contains("podcast") || p.contains("tunein")
                || p.contains("iheart") || p.contains("amazon.mp3") || p.contains("audio")) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                return ai.category == ApplicationInfo.CATEGORY_AUDIO;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String first(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    // ---------------------------------------------------------------- controles y permisos

    /** Sesión activa de una app (para reanudar o pausar desde el launcher), o null. */
    static MediaController controllerFor(String pkg) {
        MediaWatcherService s = instance;
        return s != null ? s.controllers.get(pkg) : null;
    }

    /** ¿Hay un video reproduciéndose ahora? (para no tapar una película al encender la TV) */
    static boolean isPlayingVideo() {
        MediaWatcherService s = instance;
        if (s == null) return false;
        for (Map.Entry<String, MediaController> e : s.controllers.entrySet()) {
            PlaybackState ps = e.getValue().getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING && !s.isMusic(e.getKey())) return true;
        }
        return false;
    }

    static boolean isEnabled(Context ctx) {
        ComponentName cn = new ComponentName(ctx, MediaWatcherService.class);
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                if (nm != null) return nm.isNotificationListenerAccessGranted(cn);
            }
            String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(cn.flattenToString());
        } catch (Exception e) {
            return false;
        }
    }

    /** Con WRITE_SECURE_SETTINGS (dado una vez por ADB) el launcher se activa este servicio solo. */
    static boolean ensureEnabled(Context ctx) {
        if (isEnabled(ctx)) return true;
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false;
        try {
            ComponentName cn = new ComponentName(ctx, MediaWatcherService.class);
            String me = cn.flattenToString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            String next = (cur == null || cur.trim().isEmpty()) ? me : (cur.contains(me) ? cur : cur + ":" + me);
            Settings.Secure.putString(ctx.getContentResolver(), "enabled_notification_listeners", next);
            requestRebind(cn);
            Log.d(TAG, "Servicio de reproducción activado por el launcher");
            return isEnabled(ctx);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo activar el servicio de reproducción", e);
            return false;
        }
    }
}
