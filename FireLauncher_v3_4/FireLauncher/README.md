# Fire Launcher

Launcher para **Fire TV**, **Android TV** y **Google TV**, con efecto de cristal, burbuja y animaciones. La pantalla de inicio trae reloj, clima, «Continuar viendo» con la miniatura del capítulo y la canción que suena. Pensado para equipos con 2 GB de RAM o más.

El stand by muestra un reloj de cristal, la portada de la canción difuminada de fondo y tarjetas con lo que suena o el video en pausa. Las miniaturas salen primero del propio televisor y, si no las tiene, de internet (por serie y capítulo).

¿Tu equipo tiene 1 GB de RAM? Usa **Fire Launcher Lite**, la versión ligera con estilo Apple TV.

## Instalar (la forma fácil)

1. Descarga el zip de la [última versión](../../releases/latest) y descomprímelo.
2. En el televisor activa la **depuración ADB** y busca su **IP** (los pasos están en `instalador/LEEME.txt`).
3. Haz doble clic en **`instalador/Instalar.bat`** y escribe la IP.

El instalador descarga ADB de Google la primera vez, instala el launcher, le da todos los permisos y lo abre en el televisor. Funciona en Windows 10 y 11.

**Instalación manual:** `FireLauncher_proyecto/PERMISOS_ADB.md` tiene todos los comandos.

## Compatibilidad

- Fire TV con Fire OS 6, 7 u 8.
- Android TV 7.1 o superior y Google TV.
- **No** funciona en los Fire TV con Vega OS, porque no instalan apps Android.

Detalles en `FireLauncher_proyecto/REQUISITOS.md`.

En Fire TV, Amazon no permite cambiar su pantalla de inicio. Por eso el botón Inicio abre este launcher mediante un servicio de accesibilidad, y el inicio de Amazon puede verse un instante.

## Compilar

Abre `FireLauncher_proyecto` en Android Studio y usa **Build > Build APK(s)**. Las instrucciones y novedades de cada versión están en `LEEME_COMPILAR.md`.

## Publicar una versión (para el mantenedor)

1. **Compila el APK firmado con tu clave:** Build > Generate Signed App Bundle / APK > APK.
   - Usa siempre la misma clave, o las actualizaciones no se instalarán encima de la versión anterior.
   - La clave (`.jks`) nunca se sube al repositorio.
2. **Renómbralo** como `FireLauncher.apk`.
3. **Configura el instalador:** en `instalador/instalar.ps1`, pon tu repositorio en `$Repo = 'usuario/repositorio'`. Así el instalador puede bajar el APK de la última versión por su cuenta.
4. **Crea la versión en GitHub:** Releases > Draft a new release, con una etiqueta (por ejemplo `v3.4`). Adjunta:
   - el APK;
   - la carpeta `instalador` comprimida (con el APK dentro, para que funcione sin internet).

## Licencias

- Fuentes incluidas: SIL Open Font License (ver `app/src/main/assets/licencias/`, si las hay).
- ADB (Android Platform-Tools) no se incluye: el instalador lo descarga de Google y quien lo usa acepta sus términos.
- Código del proyecto: elige una licencia antes de publicar (por ejemplo, MIT) y agrégala como `LICENSE`.
