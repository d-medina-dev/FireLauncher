# Compilar el APK de Fire Launcher (versión 3.4)

1. Instala Android Studio (https://developer.android.com/studio) y ábrelo.
2. File > Open > selecciona la carpeta `FireLauncher_proyecto` de este paquete.
3. Espera a que termine la sincronización de Gradle.
4. Build > Build Bundle(s) / APK(s) > Build APK(s). El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.
5. Instala en el Fire TV con ADB (activa "Opciones de desarrollador" y "Depuración ADB" en el Fire TV):
   adb connect IP_DEL_FIRETV:5555
   adb install -r app-debug.apk
6. Abre el launcher. Primero sale el aviso de permiso de ubicación: el launcher espera a que respondas (aceptes o no) antes de terminar de abrir.
7. Después aparece la pantalla "Permisos del launcher" (también está en Personalización > Permisos). Activa lo que puedas desde ahí; en Fire TV, lo que no tenga ajuste en el menú se activa con el comando del paso 1 de abajo.
8. Haz los pasos de "Configuración en el Fire TV" (abajo). Sin ellos el launcher funciona, pero el botón Inicio sigue abriendo el de Amazon y "Continuar viendo" no sabe qué viste.


## Novedades de la versión 3.4
- Parpadeo al pausar o cambiar la canción. En el inicio, la portada aparece en varias imágenes a la vez. Cada vez que cambiaba, el Fire TV las dejaba en blanco uno o varios cuadros mientras cargaba la nueva, y se veía como un pestañeo de todo el launcher. Ahora:
  - Las portadas nuevas se cargan y decodifican antes de mostrarse; la pantalla se actualiza cuando ya están listas, así que el cambio es instantáneo. Si una imagen tarda más de 2 s o no existe, se muestra igual.
  - Las imágenes de portada y miniaturas ya no usan carga diferida (lazy) y se decodifican al dibujar.

## Novedades de la versión 3.3
- Parpadeo al pausar o cambiar la canción:
  - El WebView ya no usa una capa de hardware forzada, que hacía parpadear la pantalla completa en algunos equipos al cambiar imágenes.
  - Al cambiar de canción o de capítulo, la portada anterior se queda hasta que llega la nueva, en vez de quedar en blanco. Si en 8 s no llega otra, o no se encuentra, se quita.
  - La dirección de la portada solo cambia si la imagen cambió de verdad (el sello es la huella de la imagen). Si la app reenvía la misma portada, nada se recarga.
  - Si los datos de reproducción no cambiaron, no se mandan a la pantalla.
- Reflejo del reloj de cristal: ciclo continuo a velocidad constante. Termina exactamente donde empieza, sin corte.

## Novedades de la versión 3.2
- Personalización > "Stand by: ancho" (0 a 10; 5 es el tamaño de antes), justo debajo de "Stand by: alto".
  - En Cristal se ensanchan las cifras; en Redondeado y Neón se agranda el reloj.
  - Si hay una tarjeta a la derecha, el ancho se frena para no pisarla.
- Tarjetas del stand by: el texto es blanco (título) y gris (artista, estado) sólidos, sin el efecto translúcido.
- Tarjeta de la canción más grande cuando el stand by no entró desde una serie en pausa: 600 px de ancho y portada de 548 px.
- Fondo con la portada: fundido suave de 1,4 s.
  - Al cambiar de canción, la portada nueva aparece sobre la anterior.
  - Al pausar o quitar la canción, la imagen se desvanece en vez de cortarse de golpe.

## Novedades de la versión 3.1
- Stand by: la portada de la canción sale difuminada de fondo solo mientras está sonando. Si la música se pausa o no hay música, vuelve el fondo normal.
- Reloj de cristal del stand by: destello que cruza cada cifra cada 9 s, igual que en Fire Launcher Lite.

## Novedades de la versión 3.0
- Reloj de cristal:
  - Las terminaciones de las cifras siguen planas, pero tienen su borde de luz en el corte, como el resto de los bordes.
  - Los dos puntos se separan con la altura (35 % y 65 %).
- Redondeado y Neón: los dos puntos se dibujan como círculos que no se deforman al alargar el reloj.
- Pantalla de carga minimalista: logo, nombre y una línea fina.
- Para equipos más ligeros existe Fire Launcher Lite, un proyecto aparte (FireLauncherLite_proyecto).

## Novedades de la versión 2.9
- Reloj de cristal más plano: terminaciones rectas como en el iPhone y cuerpo de vidrio parejo (sin efecto de tubo). Lo redondeado queda solo en los bordes, con un bisel fino de luz.
- Ajustes > Permisos: dos permisos nuevos que el launcher pide directamente, con la ventana del sistema:
  - "Clima por tu ubicación".
  - "Miniaturas del televisor" (lista "Seguir viendo" del sistema, Android 8 o superior).
- Todos los comandos ADB de permisos están en PERMISOS_ADB.md, dentro del zip de cada versión.

## Novedades de la versión 2.8
- Reloj de cristal: vidrio translúcido y lechoso con el fondo visible a través. Los bordes brillan suave (máscara degradada) y lleva reflejos fijos: un brillo difuso arriba a la izquierda y un destello diagonal. No tiene animaciones, así que no consume nada en reposo.
- Reloj del inicio:
  - Clásico vuelve a mostrar la hora (el texto dentro de un SVG no se rellenaba).
  - "Alto" y "Ancho" de Personalización funcionan también en Clásico y Redondeado. Más pequeño, el reloj queda abajo.
- Las ventanas de Personalización y Rendimiento se desplazan al bajar, sin mover la página.
- El sol del pronóstico por horas tiene sus 8 rayos.
- Miniaturas de Continuar viendo:
  - Primero se busca en el propio televisor: la lista "Seguir viendo" (Watch Next) y los programas de los canales de las apps, que traen la miniatura del capítulo exacto. Se compara por serie, capítulo y temporada/episodio; si nada coincide, se usa lo más reciente de esa app en las últimas 6 horas.
  - El permiso para leerlos se pide una vez en todos los equipos, también en Fire TV. Por ADB:
    adb shell pm grant com.ejemplo.launcher com.android.providers.tv.permission.READ_EPG_DATA
  - Por internet se separan la serie y el capítulo ("T1 E14", "S02E03", "Capítulo 4: El cuerpo" + "Stranger Things"). Se busca primero la imagen del capítulo exacto (TVmaze por número o por nombre, y TMDB con clave), luego Wikipedia con "serie + capítulo" y al final la serie sola.

## Novedades de la versión 2.7 (sobre la 2.6)
- Reloj de cristal calcado del iPhone (iOS 26):
  - Cifras "elásticas": trazo grueso de grosor constante. Al alargarlas solo crecen los tramos rectos y las curvas conservan su forma, como la tipografía del iPhone.
  - Vidrio esmerilado blanco con los colores del fondo brillando a través (refracción), bordes de luz suaves y un reflejo arriba a la izquierda.
  - Se usa en el stand by (estilo Cristal) y en el inicio (estilo Cristal).
- Reloj del inicio:
  - Sin la palabra "HORA".
  - En Cristal ocupa todo el recuadro. Personalización tiene "Reloj del inicio: alto" (0 = proporción normal, 10 = todo el alto) y "Reloj del inicio: ancho" (55 % a 100 % del recuadro, sin cambiar el alto). Si es más pequeño, queda abajo.
- Burbuja de las apps: choca contra el borde del recuadro como contra una pared. Llega al panel, se aplasta contra él y rebota. La pared se mide al moverse, no en cada cuadro (antes obligaba a recalcular la maquetación 60 veces por segundo).

## Novedades de la versión 2.1
- Reloj de cristal como el del iPhone (iOS 26, Liquid Glass):
  - Los números tienen la forma del reloj del iPhone: altos, de peso medio, con los dos puntos redondos (Nunito 600, OFL).
  - Efecto de vidrio translúcido: cuerpo esmerilado con refracción, bisel de luz, sombra interior, brillo especular, borde luminoso y un destello que cruza los números.
  - Transparencia ajustable de 0 (transparente) a 10 (esmerilado), como el control del iPhone.
  - La fecha, la temperatura y el AM/PM del stand by también son de cristal.
- Reloj del inicio: nuevo diseño "Cristal".
- Ajustes > Personalización (ventana propia, con ◀ valor ▶):
  - Apariencia: nombres de las apps y modo oscuro.
  - Relojes: del inicio (6 diseños), del stand by (5) y transparencia del cristal.
  - Clima: °C o °F, widget (pronóstico de 5 días, próximas horas o temperatura grande) y animación del cielo.
- Más rápido: las listas de las pantallas cerradas (clima, stand by, reloj) ya no se generan en cada redibujado. En pruebas, los elementos de la página bajaron de 3364 a 1473 y el tiempo de cada redibujado un 29 %.
- Requisitos y dispositivos compatibles: ver REQUISITOS.md.

## Novedades de la versión 2.0 (sobre la 1.9.3)
- Corregido: dos textos decían "Fire Fire Launcher: botón Inicio".
- Stand by con letra de cristal (Liquid Glass):
  - Cada número tiene cuerpo esmerilado (con la refracción de la 1.9.3), bisel de luz arriba a la izquierda, sombra interior abajo a la derecha, brillo especular y borde luminoso.
  - Cada 9 segundos un destello cruza los números. Se apaga con "Brillo al volver a las apps" en Rendimiento.
  - Los bordes son vectoriales: reemplazan al filtro sbGlassFx (erosión + desenfoque), más nítidos y baratos.
  - Modo claro: fondo con los colores del launcher, más profundos, para que el cristal blanco resalte. Los textos del stand by son blancos y legibles.
- Personalización > Relojes (ventana propia):
  - Reloj del inicio: Clásico, Grande, Apilado, Analógico o Redondeado.
  - Stand by: Cristal, Redondeado, Neón, Fino o Analógico.
  - Los analógicos tienen segundero continuo, animado por la tarjeta gráfica.
- Miniaturas: se buscan también en Wikipedia (español e inglés, sin clave), que cubre casi todas las series y películas por su título en español. También se aceptan miniaturas pequeñas de las apps (desde 100 px), que antes se descartaban como logos.

## Novedades de la versión 1.5
- Segundero exacto (antes iba un segundo atrasado).
- Burbuja:
  - Al llegar al borde del recuadro (dock o Todas las apps) choca y rebota un poco.
  - El reflejo tiene inercia: se queda atrás al acelerar, se pasa al frenar y rebota con la misma intensidad que la burbuja.
- Stand by:
  - Sin el widget del clima (la temperatura ya está bajo el reloj). Si no hay música ni video, el reloj queda centrado.
  - Sigue el tema del launcher: oscuro (azul casi negro) o claro (el fondo de colores del launcher).
- Miniaturas de Continuar viendo, resultado de la investigación:
  - Fire TV: la fila "Continuar viendo" de Amazon se arma en la nube. Las apps que participan (Prime Video, Disney+, Max, Hulu, etc.) mandan su historial con el SDK de Amazon y el launcher de Amazon lo descarga con tu cuenta. Ni esos datos ni los del launcher de Amazon se pueden leer desde otra app, y Netflix no participa.
  - Google TV / Android TV: la fila "Watch Next" está en la base de datos de TV del sistema. El launcher la lee con el permiso READ_EPG_DATA, que pide solo una vez (o por ADB, ver abajo).
  - Por eso en Fire TV la imagen se busca por el título. Ahora también en TMDB, que tiene la mejor cobertura y busca en español ("Merlina" encuentra "Wednesday").

### Activar TMDB (recomendado para las miniaturas en Fire TV)
1. Crea una cuenta gratis en https://www.themoviedb.org y ve a Ajustes > API para pedir una clave (uso personal).
2. Copia la "Clave de la API" (v3) o el "Token de acceso de lectura" (v4).
3. Pégala en app/src/main/res/values/keys.xml, dentro de <string name="tmdb_key">…</string>, y compila.
4. Si la red filtra páginas, permite api.themoviedb.org e image.tmdb.org.

Si aun así no aparece la miniatura, la sección Continuar viendo dice el motivo ("Sin miniatura: …").

## Novedades de la versión 1.4
- Cambio de modo claro a oscuro sin lag: un velo aparece en 0,16 s, el tema cambia debajo de una vez y el velo se va. Antes se animaban el fondo, el borde y la sombra de cada cristal durante 0,6 s.
- Burbuja de las apps:
  - Vuelve la luz suave de atrás, que la sigue con un pequeño retraso.
  - El reflejo se desliza según su posición y su velocidad.
  - El rebote es más intenso y se ajusta en Rendimiento de 0 (nada) a 10 (casi líquido), con ◀ ▶.
  - Todo lo nuevo se puede apagar en Rendimiento ("Luz detrás de la burbuja", "Reflejos de la burbuja", "Color de la app de fondo").
- El color de fondo de la app enfocada ahora se funde suavemente (dos capas que cambian de opacidad).
- Stand by:
  - La hora con AM/PM pequeño al lado, abajo; la temperatura y el estado, pequeños debajo del reloj.
  - Si entra por inactividad: la canción en grande (o el clima si no hay música).
  - Si dejaste un video en pausa (por ejemplo, con el aviso de "¿sigues ahí?") y no tocas el mando durante el tiempo del stand by, el launcher entra en stand by mostrando lo que estabas viendo. Cualquier tecla te devuelve a esa app.
  - Esto necesita activos los dos servicios ("Launcher: botón Inicio" y "Launcher: lo que estás viendo"). Si el salvapantallas del Fire TV salta antes, pon su tiempo más largo que el del stand by.
- Reloj: el segundero avanza desde que entras (antes esperaba al minuto siguiente).
- Botón Inicio:
  - Al volver de una app, el launcher queda exactamente donde lo dejaste.
  - Con el launcher abierto, Inicio lleva al menú principal desde Todas las apps o una sección, sin perder la app seleccionada.
  - Si el sistema cerró el launcher mientras usabas otra app, al volver recupera la pantalla y la selección, sin la animación de inicio.
- El pronóstico del widget ya no se ve aplastado: el tamaño de letra del sistema ya no agranda el texto del launcher (diseño fijo para 1920×1080).
- Los nombres largos de apps se cortan con "…" dentro de su columna.
- Miniaturas, enlace con las apps de streaming:
  - El launcher lee las recomendaciones que las apps publican en Fire TV y Android TV (sus tarjetas de "seguir viendo", con título e imagen) y las usa como miniatura de lo que viste.
  - En Google TV también lee la lista "Watch Next" del sistema. Para eso, concede el permiso una vez:
    adb shell pm grant com.ejemplo.launcher com.android.providers.tv.permission.READ_EPG_DATA

## Novedades de la versión 1.3
- Miniaturas de Continuar viendo:
  - Muchas apps de TV mandan su propio logo como "imagen". Ahora el launcher lo detecta (lo compara con el ícono y el banner de la app) y en ese caso busca la imagen real de la serie o película.
  - Si aun así no hay miniatura, la sección Continuar viendo dice por qué: por ejemplo, "la app no comparte ninguna imagen" o "no se encontró «Título» en TVmaze, Kitsu ni iTunes".
  - Si la búsqueda falló, se reintenta cada 10 minutos.
- Spotify ya no muestra la portada de la canción anterior. Spotify avisa primero el título nuevo con la portada vieja; ahora el launcher espera la portada nueva, y también la actualiza si cambia sin que cambie el título.
- Burbuja:
  - Vuelven el rebote visible (se pasa un poco y regresa) y su sombra, ambos de bajo consumo: se calculan cuadro a cuadro sin repintar, y la sombra se pinta una sola vez.
  - La burbuja se oculta cuando el foco sale de las apps (widgets, barra, pestañas, búsqueda) y reaparece directamente sobre la app al volver.
- Vuelve el marco blanco con brillo de los widgets (en la 1.1 quedaba recortado).
- Personalización: los efectos de rendimiento, incluido "Fondo con movimiento", están en su propia ventana ("Rendimiento ›").

## Novedades de la versión 1.2
- Fecha como en el teléfono ("Mar nov 24") encima de la hora, en el reloj del inicio y en el stand by. En el stand by, a la derecha, van la temperatura y el estado del tiempo.
- El clima cambia durante el día:
  - Se consulta cada 15 minutos (antes cada 30), que es cada cuánto Open-Meteo actualiza el dato actual.
  - El estado usa la nubosidad real: despejado, mayormente despejado, parcialmente nublado, mayormente nublado o nublado. También hay niebla, llovizna, lluvia, lluvia fuerte, tormenta y nieve.
  - Cada estado tiene su escena animada ligera, con variantes de noche (luna y estrellas). Las animaciones se pausan con la pantalla cerrada.
- Pantalla del clima estilo teléfono, con la estética del launcher. Se recorre con el mando:
  - Arriba: la ubicación, la temperatura, el estado y la máxima y mínima.
  - Pronóstico por hora: las próximas 25 horas con ícono, probabilidad de lluvia y amanecer o atardecer. Izquierda y derecha recorren las horas, y las que ya pasaron se quitan solas.
  - Pronóstico de 10 días: cada fila con su probabilidad de lluvia y una barra de temperatura de fresco a caluroso; en "Hoy", un punto marca la temperatura actual. Abajo y arriba recorren los días, y la vista se desplaza sola.
  - Al final: sensación térmica, humedad, viento y la próxima salida o puesta del sol.
- Open-Meteo ahora se consulta con 10 días, datos por hora, nubosidad, sensación térmica, humedad y viento (mismo servicio, sin clave).

## Novedades de la versión 1.1
- Pantalla de carga: ícono de cristal con un anillo girando, el nombre y una barra de carga. Al terminar, los widgets bajan y las apps suben en cascada a su lugar.
- Secciones que se agrandan (entrar y salir de Todas las apps, abrir un widget):
  - Son dos cristales fijos que se transforman uno en el otro con transform y opacidad. Lo anima la tarjeta gráfica, sin recalcular ni repintar en cada cuadro.
  - Las sombras grandes son fijas y se funden entre sí.
  - Ya no hay desenfoque animado y las duraciones bajaron de unos 0,9 s a unos 0,55 s.
- Animaciones ocultas pausadas: la lluvia, las nubes y el sol del clima, el cursor del teclado y el stand by ya no corren mientras están cerrados. Antes repintaban la pantalla entera en cada cuadro, todo el tiempo.
- Moverse entre apps:
  - No se redibuja la interfaz, así que la flecha responde al instante.
  - La burbuja desliza suave (unos 0,4 s), con un leve rebote que se apaga en Rendimiento > "Rebote de la burbuja".
  - Se quitó el segundo marco que la seguía por detrás.
  - El color de fondo de la app se aplica cuando te detienes.
- Cristal: el desenfoque usa la mitad de radio (se ve igual y cuesta bastante menos).
- Continuar viendo:
  - Las tarjetas de "Tus apps de streaming" muestran la miniatura de fondo.
  - Tienen el mismo marco blanco con brillo y la misma animación que el resto del launcher (también Reanudar y el botón de música).
- Miniaturas de series, películas y anime. Si la app no entrega la imagen, el launcher la busca en este orden:
  1. La imagen grande de su notificación de reproducción.
  2. Por el título, en TVmaze (series), Kitsu (anime) e iTunes (películas; tienda de México y de EE. UU.).
  - Las búsquedas se recuerdan. Si el título está en otro idioma que la base de datos (por ejemplo "Merlina" frente a "Wednesday"), no se pone imagen, para no mostrar una equivocada.
  - Si la red filtra páginas, permite api.tvmaze.com, kitsu.io e itunes.apple.com.
- Stand by:
  - Números con M PLUS Rounded (redondeada, estrechada).
  - Colores del launcher: degradado lavanda a lila sobre azul muy oscuro, con brillos tenues y tarjetas de cristal.
  - Licencias en assets/licencias.

## Novedades de la versión 1.0
- Movimiento entre apps más fluido:
  - La burbuja se mueve con un muelle calculado cuadro a cuadro: conserva la velocidad aunque pulses otra flecha a mitad de camino, así que ya no da tirones. El rebote y el estiramiento salen de esa misma velocidad.
  - El ícono enfocado y sus vecinos crecen con CSS al instante, sin redibujar la pantalla.
  - La pantalla completa solo se vuelve a dibujar cuando la burbuja se detiene, no en cada pulsación. Con la CPU 3 veces más lenta (similar a un Fire TV), los cuadros perdidos bajaron de 21 a 5 y el bloqueo del hilo principal de 879 ms a 8 ms.
  - "Rebote de la burbuja" y "Rebote de los íconos" siguen en Personalización > Rendimiento.
- Stand by con números propios: cifras altas, gruesas y redondeadas como en iPhone, dibujadas como trazos SVG (se ven iguales en cualquier TV, sin depender de sus fuentes). El reloj se agranda para llenar su espacio ("2:18" sale más grande que "12:58"). Los textos usan Nunito, incluida dentro de la app (licencia OFL en assets/licencias).
- Los círculos de color del fondo se dibujan a media resolución y se amplían: se ven igual y usan un 75 % menos de memoria gráfica.

## Configuración en el Fire TV (una sola vez, por ADB)
Fire OS no deja elegir otro launcher como principal ni desactivar el de Amazon. Este launcher usa el mismo método que Projectivy y FireFrame: un servicio de accesibilidad que lo trae al frente, en su menú principal, cada vez que se pulsa Inicio o aparece el inicio de Amazon.

1. Un solo comando para lo principal (recomendado). Con él, el launcher activa por sí mismo sus dos servicios y los vuelve a activar tras actualizar la app o Fire OS:
   - "Launcher: botón Inicio": Inicio vuelve al launcher y el launcher se abre al encender o despertar la TV.
   - "Launcher: lo que estás viendo": Continuar viendo con la serie, película o anime real, y la última canción.
   adb shell pm grant com.ejemplo.launcher android.permission.WRITE_SECURE_SETTINGS
   Después abre el launcher (o Personalización > Permisos).
2. "Continuar viendo" según la última app de streaming que usaste en la TV:
   adb shell appops set com.ejemplo.launcher GET_USAGE_STATS allow
3. Para que se abra solo al reiniciar en Fire OS 8 (Android 11):
   adb shell appops set com.ejemplo.launcher SYSTEM_ALERT_WINDOW allow

Para comprobarlo, abre Personalización (botón arriba a la derecha). Abajo dice si el botón Inicio ya abre este launcher.

Alternativa manual para "lo que estás viendo" (Fire OS 7 u 8):
   adb shell cmd notification allow_listener com.ejemplo.launcher/com.ejemplo.launcher.MediaWatcherService

Alternativa manual para el botón Inicio (si no quieres dar ese permiso):
   adb shell settings get secure enabled_accessibility_services
   Si sale `null` o vacío:
   adb shell settings put secure enabled_accessibility_services com.ejemplo.launcher/com.ejemplo.launcher.HomeWatcherService
   adb shell settings put secure accessibility_enabled 1
   Si sale algo, ponlo delante separado con dos puntos para no apagarlo:
   adb shell settings put secure enabled_accessibility_services LO_QUE_SALIO:com.ejemplo.launcher/com.ejemplo.launcher.HomeWatcherService
   Con este método, tras una actualización de Fire OS a veces hay que repetirlo.

## Android TV / Google TV
- Al abrirlo por primera vez pide ser la app de inicio principal: acéptalo.
- El servicio de accesibilidad se activa con "Activar botón Inicio" en Personalización, que abre Ajustes > Accesibilidad > "Launcher: botón Inicio". También sirve el comando `pm grant ... WRITE_SECURE_SETTINGS` del Fire TV. En Android 13 o superior, primero ve a Ajustes > Apps > Launcher > menú ⋮ > "Permitir ajustes restringidos".

## Mando a distancia
- Flechas: mueven el foco. Al final de una fila el foco se queda donde está.
- OK: abre la app, el widget, la pestaña o el botón que tenga el foco.
- OK mantenido (más de medio segundo) sobre una app: abre un menú con "Fijar en el inicio" (o "Quitar del inicio"), "Mover" y "Cancelar".
- OK mantenido sobre un widget: lo levanta para moverlo. Flechas para cambiarlo de lugar, OK o Volver para soltarlo.
- Volver: suelta lo que estés moviendo, cierra el panel abierto o regresa al menú principal. El launcher nunca se cierra con Volver.
- Volver mantenido 10 segundos: sale del launcher al inicio del sistema. Aparece un aviso con cuenta atrás; si sueltas antes, te quedas.
- Inicio: siempre vuelve al menú principal del launcher, no al menú de la TV (con el servicio activado). En Android TV / Google TV también se puede mantener Inicio 10 segundos para salir; en Fire TV no, porque Fire OS no deja que ninguna app lea ese botón.
- Menú (≡): abre o cierra Personalización.
- En "Todas las apps", la flecha arriba sube por las filas, luego a las pestañas, luego a la barra de búsqueda; arriba desde la búsqueda vuelve al menú principal.
- Barra de búsqueda: OK abre el teclado del launcher (letras, números, Ñ, Espacio, Borrar, Limpiar y Listo), que se maneja con las flechas y OK. No depende del teclado del sistema.
  - La lista se filtra con cada letra, sin importar mayúsculas ni acentos.
  - Arriba desde el teclado va a los resultados; "Listo" va a la primera app encontrada; OK la abre.
  - Volver cierra el teclado. Un teclado Bluetooth también sirve para escribir.
  - Al salir de "Todas las apps" la búsqueda se borra.
- Después de salir, el launcher vuelve con la próxima pulsación de Inicio.

## Qué hace el launcher
- Apps instaladas (hasta 24) con su logo real:
  - Si el marco del logo es una figura geométrica simple (cuadrado, cuadrado redondeado, círculo, óvalo, squircle o polígono regular como un hexágono), ocupa todo el mosaico y lo que falta se rellena con los píxeles exactos del borde del logo: el círculo verde de Spotify queda como un cuadrado del mismo verde.
  - Si la forma es compleja (un escudo, un globo de chat, una nube, una estrella, una silueta) o es un logo suelto como Play Store, va entero sobre un fondo de su color en tono oscuro (o claro si el logo es oscuro), para que resalte.
  - En modo oscuro el logo y su relleno se oscurecen juntos.
- Inicio: 6 apps fijas (pines) que eliges tú. Se quedan en el lugar donde las pongas: reordenar "Todas las apps" no las mueve.
  - La primera vez son las mismas 6 que ya estaban en el inicio.
  - Fijar: OK mantenido sobre una app de "Todas las apps" > "Fijar en el inicio". Si los 6 lugares están ocupados, eliges en el inicio a cuál reemplaza.
  - Quitar: OK mantenido sobre una app del inicio > "Quitar del inicio". Su lugar queda como "+ Fijar app"; OK ahí te lleva a elegir qué app va en ese lugar.
  - Cambiar de lugar: OK mantenido > "Mover" (o Personalización > Mover apps y widgets) y flechas.
  - En "Todas las apps", las fijadas llevan una chincheta. Si desinstalas una app fijada, su lugar se reserva y vuelve si la reinstalas.
- Personalización > Disposición:
  - Mi orden: el orden que tú elijas (al principio, alfabético).
  - Por categoría: agrupa video, música, juegos y utilidades.
  - Mover apps y widgets: modo mover. Elige con OK, mueve con las flechas, OK para soltar y Volver para terminar. En el inicio cambia de lugar los 6 pines; en "Todas las apps", el orden de la lista; y los tres widgets entre sí.
  - Restablecer: vuelve al orden alfabético, a los 6 pines por defecto y a la posición original de los widgets.
  - Salir del launcher: igual que mantener Volver 10 segundos.
- Continuar viendo: muestra la última serie, película o anime que viste, con su carátula, el episodio, cuándo lo viste y cuánto falta. Reanudar abre la app y, si la reproducción seguía en pausa, le da play.
  - Lo lee de las sesiones de reproducción que publican las apps mientras reproducen (las mismas que usa Alexa). Funciona con las apps que publican esa información; casi todas las grandes lo hacen. Las que no, aparecen solo como "Abierta hace…".
  - Solo sabe lo que se reproduzca después de activar el servicio "Launcher: lo que estás viendo".
  - Debajo, la última canción de Spotify (u otra app de música) con su carátula. En el detalle, el botón Pausar/Reproducir la controla sin salir del launcher.
  - "Tus apps de streaming": lo último que viste en cada app.
- Reloj: usa la zona horaria de la ubicación del aparato (la misma que el clima) y el formato de 12/24 h de la TV. Si el reloj de la TV está desfasado más de 90 segundos, lo corrige con la hora de internet. Antes de tener ubicación usa la zona configurada en la TV.
- Red: el nombre del Wi-Fi o "Ethernet", con IP, dominio y señal. Se actualiza sola.
- Clima real con pronóstico de 6 días (Open-Meteo), cada 30 minutos. Usa la ubicación del aparato o, si no hay, la aproximada por la red.
- Modo oscuro: oscurece el fondo, los marcos de los widgets, los paneles y las apps. Se guarda junto con el orden.
- Al encender o despertar la TV, el launcher se abre solo (con el servicio del botón Inicio activo), salvo que haya un video reproduciéndose. Al reiniciar, también (y en Fire OS 8 sin el servicio hace falta el permiso "Abrirse al reiniciar").

## Servicios de internet que usa
- api.open-meteo.com (pronóstico).
- ipwho.is e ipapi.co (ubicación aproximada, solo si no hay ubicación del aparato).
- api.bigdatacloud.net (nombre de la ciudad, solo si el sistema no tiene Geocoder).
Si la red filtra páginas, permite estos dominios para que aparezca el clima.

## Compatibilidad
- Fire TV Stick 4K de 1.ª generación (2018, AFTMM): Android 7.1 (API 25), Fire OS 6. Es el mínimo del proyecto (`minSdk 25`).
- Fire OS 7 y 8: el botón Inicio se redirige con el servicio de accesibilidad.
- Los Fire TV con Vega OS (Fire TV Stick 4K Select y modelos desde finales de 2025) no instalan apps Android.
- Fire OS muestra el inicio de Amazon un instante antes de que aparezca este launcher; no se puede evitar sin root.

## Si Android Studio se queda cargando al 80 %
1. Mira la pestaña **Build** o **Build Output** abajo. Si dice "Downloading" o no cambia en varios minutos, es descarga lenta, no un error: espera o revisa tu conexión.
2. Acepta la ventana **Trust Project** si aparece.
3. Ve a **Tools > SDK Manager** y confirma que están instalados **Android SDK Platform 34** y **Android SDK Build-Tools**. Si falta alguno, instálalo y vuelve a sincronizar con el icono del elefante.
4. Si sigue igual: **File > Invalidate Caches > Invalidate and Restart**, y luego **File > Sync Project with Gradle Files**.

## Si algo no se ve o no responde
1. En Android Studio abre **View > Tool Windows > Logcat** y escribe `Launcher` en el filtro.
2. Al activarse, el servicio escribe "Servicio del botón Inicio activo" y la lista de inicios del sistema que vigila.
3. En Chrome, `chrome://inspect` > **inspect** bajo "Launcher" muestra la consola de la página.
