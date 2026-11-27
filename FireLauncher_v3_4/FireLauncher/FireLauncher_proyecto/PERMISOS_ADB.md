# Fire Launcher — comandos ADB para todos los permisos

Estos comandos se ejecutan una sola vez, desde una PC con ADB, con el televisor en la misma red.
En Fire TV, activa antes Configuración > Mi Fire TV > Opciones para desarrolladores > Depuración ADB.

## 1. Conectar e instalar o actualizar
```
adb connect IP_DEL_TELEVISOR:5555
adb install -r app-debug.apk
```

## 2. Permiso principal (recomendado)
Con este permiso el launcher activa solo sus dos servicios: el botón Inicio y "lo que estás viendo".
```
adb shell pm grant com.ejemplo.launcher android.permission.WRITE_SECURE_SETTINGS
```

## 3. Servicios (solo si no se activaron solos con el paso 2)
```
# Lo que estás viendo (Continuar viendo, portada de la canción, stand by con video)
adb shell cmd notification allow_listener com.ejemplo.launcher/com.ejemplo.launcher.MediaWatcherService

# Botón Inicio (servicio de accesibilidad).
# Atención: este comando reemplaza la lista de servicios de accesibilidad activos.
adb shell settings put secure enabled_accessibility_services com.ejemplo.launcher/com.ejemplo.launcher.HomeWatcherService
adb shell settings put secure accessibility_enabled 1
```

## 4. Permisos de uso y de pantalla
```
# Uso de apps (ordena por la última que usaste y Continuar viendo por app)
adb shell appops set com.ejemplo.launcher GET_USAGE_STATS allow

# Mostrar sobre otras apps (abrirse al encender, stand by con el video en pausa)
adb shell appops set com.ejemplo.launcher SYSTEM_ALERT_WINDOW allow
```

## 5. Permisos que también se piden desde el launcher (Ajustes > Permisos)
```
# Clima y hora por tu ubicación
adb shell pm grant com.ejemplo.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.ejemplo.launcher android.permission.ACCESS_FINE_LOCATION

# Miniaturas del televisor: lista "Seguir viendo" y canales de las apps (Android 8 o superior)
adb shell pm grant com.ejemplo.launcher com.android.providers.tv.permission.READ_EPG_DATA
```

## 6. Que el sistema no cierre el launcher (recomendado)
```
adb shell dumpsys deviceidle whitelist +com.ejemplo.launcher
```

## 7. Solo Android TV / Google TV (no en Fire TV): launcher predeterminado
```
adb shell cmd role add-role-holder android.app.role.HOME com.ejemplo.launcher
```

## Comprobar
```
adb shell dumpsys package com.ejemplo.launcher | grep -E "WRITE_SECURE_SETTINGS|LOCATION|READ_EPG_DATA"
adb shell appops get com.ejemplo.launcher
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure enabled_notification_listeners
```
