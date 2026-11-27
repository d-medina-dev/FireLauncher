package com.ejemplo.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Al encender la TV abre el launcher. Espera unos segundos para quedar por encima del inicio
 * del sistema (en Fire TV no se puede reemplazar). En Android 10 o superior el sistema solo lo
 * permite si la app tiene "Mostrar sobre otras apps" o el servicio del botón Inicio activo.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Si ya está abierto o esperando que respondas un aviso de permisos, no se toca.
                if (!MainActivity.resumed && !MainActivity.awaiting) {
                    app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } catch (Exception e) {
                Log.e("Launcher", "No se pudo abrir el launcher al encender", e);
            } finally {
                pending.finish();
            }
        }, 4000);
    }
}
