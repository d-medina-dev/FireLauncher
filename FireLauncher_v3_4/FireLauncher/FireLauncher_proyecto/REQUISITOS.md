# Fire Launcher — requisitos y dispositivos

## Requisitos mínimos
- Sistema: Android 7.1 (API 25) o superior. En Fire TV, Fire OS 6, 7 u 8. También Android TV 7.1+ y Google TV.
- Memoria: 1 GB de RAM. Con menos de 3 GB el launcher entra solo en modo ligero (menos efectos de cristal).
- Procesador: cuatro núcleos a 1,5 GHz o más (ARM Cortex-A53 o equivalente).
- WebView del sistema basado en Chromium 76 o superior, para el cristal y la refracción. Con versiones anteriores funciona, pero sin esos efectos.
- Almacenamiento: unos 40 MB libres (aplicación más caché de imágenes).
- Pantalla: diseñada para 1080p; se adapta a 720p y 4K.
- Internet: opcional. Solo hace falta para el clima, la hora por ubicación y las miniaturas.
- Configuración inicial por ADB (una vez): permiso WRITE_SECURE_SETTINGS (activa los servicios), acceso de uso y superposición. Ver LEEME_COMPILAR.md.

## Requisitos recomendados (todos los efectos y fluidez)
- Android 9 o superior: Fire OS 7/8 o Google TV.
- 2 GB de RAM o más (3 GB o más para todos los efectos sin modo ligero).
- Procesador a 1,8 GHz o más (Cortex-A55 o mejor), con GPU Mali-G31/G52, PowerVR GE8300 o superior.
- WebView actualizado (Chromium 100 o superior).
- Wi-Fi de 5 GHz o Ethernet.
- Los dos servicios activados: "Fire Launcher: botón Inicio" y "Fire Launcher: lo que estás viendo".
- Opcional: clave de TMDB para más miniaturas (ver LEEME).

## Dispositivos listos para usarlo
### Fire TV (instalan APK de Android)
- Fire TV Stick 4K (1.ª gen, 2018) — Fire OS 6. Es el dispositivo para el que se diseñó; usa el modo ligero.
- Fire TV Stick (3.ª gen, 2020) y Fire TV Stick Lite (2020) — Fire OS 7. Usan el modo ligero.
- Fire TV Stick 4K Max (1.ª gen, 2021) — Fire OS 7.
- Fire TV Stick 4K y 4K Max (2.ª gen, 2023) — Fire OS 8 (Android 11).
- Fire TV Stick HD (2024) — Fire OS 8.
- Fire TV Cube (2.ª gen 2019 y 3.ª gen 2022).
- Televisores con Fire TV integrado y Fire OS 7 u 8 (Fire TV Omni, serie 4, serie 2, Insignia, Toshiba, etc.).

### Google TV y Android TV
- Chromecast con Google TV (4K de 2020 y HD de 2022) y Google TV Streamer (2024).
- NVIDIA Shield TV y Shield TV Pro (2019).
- Xiaomi Mi Box S y Xiaomi TV Stick 4K, onn. 4K y otros con Android TV 9 o superior.
- Televisores Sony, TCL, Hisense y Philips con Android TV 9 o superior, o Google TV.

### No compatibles
- Fire TV Stick 4K Select y los demás con Vega OS: no instalan aplicaciones Android.
- Fire TV con Fire OS 5 (Android 5.1): están por debajo de Android 7.1.
