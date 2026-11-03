# Ranger Launcher

Launcher para la pantalla de la Ford Ranger (Android 13, 1280×720). La interfaz está hecha en HTML (`app/src/main/assets/www/index.html`) y se muestra dentro de un WebView. `MainActivity.kt` le da los datos reales y abre las apps.

## Cómo abrirlo y generar el APK

1. En Android Studio: **File ▸ Open** y elegí la carpeta `RangerLauncher`.
2. Esperá a que termine el *Gradle Sync* (la primera vez descarga cosas; hace falta internet).
   - Si Android Studio ofrece actualizar el plugin de Gradle (AGP) o Kotlin, aceptá.
   - Si pide instalar el SDK de Android 14 (API 34), aceptá.
3. Para generar el APK: **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)**. Queda en `app/build/outputs/apk/debug/app-debug.apk`.
4. Pasalo a la pantalla (pendrive o `adb install app-debug.apk`) e instalalo.

Para probarlo directo desde el notebook con el equipo conectado por USB (depuración USB activada): botón **Run ▶**.

## Usarlo como pantalla de inicio

Al apretar el botón Home del equipo, Android pregunta qué launcher usar: elegí **Ranger Launcher** y "Siempre". Si querés volver al original, se cambia en Ajustes ▸ Apps ▸ Apps predeterminadas ▸ App de inicio.

## Qué es real y qué no

| Parte | Estado |
|---|---|
| Velocidad (km/h) | Real, desde el GPS del equipo. Pide permiso de ubicación la primera vez. |
| Música (título, artista, carátula, barra, play/pausa/siguiente) | Real, desde la sesión multimedia activa (Spotify y otras apps compatibles). La carátula se muestra si la app de música publica el arte. Hay que tocar **"Activar acceso a la música"** y dar *Acceso a notificaciones* a Ranger Launcher. |
| Hora, fecha, saludo | Reales. |
| Clima | Real, por internet (Open-Meteo) usando la ubicación. Sin internet muestra "Sin datos". |
| Bluetooth / Wi‑Fi | Estado real (activado / conectado). |
| Botones de apps | Abren la app. Si no la encuentra (por ejemplo Radio, Cámara o CarPlay), muestra la lista de apps instaladas para que elijas. |
| **Luces bajas** | Siguen el **modo día/noche del equipo**: al pasar a noche se encienden (con efecto de encendido) y al pasar a día se apagan. Se detecta el cambio al instante (`onConfigurationChanged`) y también se revisa cada segundo. Todavía no lee la señal real de las luces de la camioneta (ver abajo). |
| Mini mapa GPS | Mapa real de OpenStreetMap centrado en la ubicación del equipo. Los botones Maps/Waze y la etiqueta del mapa permiten elegir qué app de navegación abrir. No incrusta la interfaz interna de Maps/Waze ni detecta automáticamente cuál app está en primer plano. |

### Atajos útiles
- **Mantener apretado** (0,6 s) cualquier botón de app —barra inferior, barra lateral (GPS, Teléfono, Radio, Ajustes), Maps, Waze, mapa, CarPlay— para elegir otra app de una lista con íconos. El botón pasa a mostrar el ícono y nombre de la app elegida. En esa misma lista hay **"Restaurar original"**. Cada botón es independiente (el Waze de la barra inferior no cambia el de la tarjeta GPS).
- **Transición:** al tocar un botón, el ícono se expande a pantalla completa y se abre la app con zoom + fundido; al volver al launcher la interfaz aparece con un zoom suave. Las animaciones nativas están en `res/anim/`.
- **Casa / Trabajo:** la primera vez piden una dirección; después abren la navegación hacia ahí. Mantené apretado para cambiarla.

## Notas sobre el panel Música y el minimapa

- La tarjeta derecha **Música** reemplaza a Conexiones y muestra la fuente, el título, el artista y la carátula que entregue la sesión multimedia activa. Algunas radios de fábrica no publican metadatos; en ese caso se verá la carátula genérica.
- El minimapa sigue la ubicación GPS en tiempo real. La selección Maps/Waze cambia la app que se abre al tocar el mapa; no cambia las teselas de OpenStreetMap por una captura o vista incrustada de la app elegida. Para detección automática de app en primer plano habría que añadir permisos de uso/accesibilidad y comprobar que el firmware del estéreo los permita.

## Conectar las luces de la camioneta (pendiente)

La señal de las luces llega al equipo por la caja CAN (Hiworld) y hoy la lee la app del fabricante. Si esa app deja la señal disponible (por ejemplo, como un aviso interno), se conecta así: desde `MainActivity.kt` llamar a

```kotlin
js("setLights(true)")   // luces encendidas
js("setLights(false)")  // luces apagadas
```

Lo que hay que averiguar con quien instaló el equipo o con el fabricante es **cómo la app original publica ese estado**. Además, el equipo está configurado como "Ford Explorer 2011‑2019", y puede que la señal de luces no se esté interpretando para una Ranger.

## Notas

- Ajustá el paquete de CarPlay en `defaults` (`MainActivity.kt`) si tu app es otra; si no lo encuentra, igual te deja elegirla.
- La imagen de la camioneta es `app/src/main/assets/www/truck.png`. Podés reemplazarla por otra con el mismo nombre.
- Este proyecto no se compiló ni se probó en un equipo real antes de entregarlo. Si el Gradle Sync o la compilación dan algún error, copiá el mensaje y lo corregimos.

## Optimizaciones de consumo

- La consulta de música se limita a una vez cada 2 segundos; la carátula se escala y codifica solo cuando cambia la canción o el álbum.
- La tarjeta Música conserva la imagen ya cargada y evita volver a decodificar la misma carátula en cada actualización.
- Wi‑Fi y Bluetooth se consultan cada 10 segundos, no en cada ciclo de la interfaz.
- El mapa no anima ni recentra en cada actualización GPS: solo se recentra cuando la posición se aleja más de unos 18 metros.
- La velocidad solo se manda al WebView cuando cambia el valor entero en km/h.
- Al abrir otra aplicación, se detienen las consultas periódicas y las actualizaciones GPS; se reanudan al volver al launcher.
- La frecuencia mínima solicitada al GPS se ajustó a 1,5 segundos para reducir consumo sin perder demasiada respuesta.


## Cambios de esta versión (interfaz y arranque)
- Se quitó el minimapa dinámico y la dependencia externa de Leaflet/tiles para reducir carga y tráfico.
- La tarjeta izquierda ahora muestra Música, con carátula/metadatos y controles multimedia.
- La tarjeta derecha ahora muestra Radio, con botones de frecuencias preseleccionadas y acceso a la aplicación de radio del estéreo.
- Las frecuencias se reflejan en la interfaz; la sintonización efectiva depende de que la aplicación de radio del equipo acepte el control externo. En esta versión, los presets abren la app de radio nativa.
- Se añadió una transformación inicial centrada al escenario y se eliminó el zoom/fundido de entrada Android para evitar el efecto de expansión desde una esquina.


## Versión 1.1: optimización, luces, radio y velocímetro

- **Equipos modestos (Android 10+, 2 GB):** `minSdk 29`; al salir del launcher se congelan el JavaScript, las animaciones, el GPS y las consultas; se libera caché si falta memoria; el APK *release* usa R8 (`Build > Generate Signed Bundle / APK` o `gradlew assembleRelease`, firmado con la clave debug para poder instalarlo).
- **Luces:** mantené apretado el indicador de luces para elegir el origen: tema día/noche del equipo, hora del día por GPS (sol), siempre encendidas/apagadas, o **por ajuste del equipo**: el launcher registra qué ajustes del sistema cambian y podés elegir el que cambia al prender las luces de la camioneta.
- **Radio:** los botones ‹ › de la tarjeta Radio usan la sesión multimedia de la app de radio; si no publica sesión, mandan teclas multimedia del sistema. Al encender, si no suena nada, abre la radio un instante y vuelve al launcher (se puede desactivar en Herramientas). Herramientas > "Radio: ver comandos de la app" lista los comandos que declara la app de radio.
- **Velocímetro:** usa la velocidad del GPS del equipo (no la del CAN de la camioneta). Si el GPS no informa velocidad, la calcula entre posiciones. Herramientas > Velocímetro permite sumar 2, 3 o 5 % para igualar el tablero del auto.


## Versión 1.2: radio real (frecuencia y botones ‹ ›)

Reemplaza lo dicho antes sobre la radio (frecuencia "decorativa" y presets que solo abrían la app). Detalle completo de los mensajes en **`RADIO_PROTOCOL.md`**.

- **Frecuencia real:** el launcher escucha el aviso `ACTION_SEND_RADIO_FREQUENCE_NEW` que emite la radio del estéreo (frecuencia + banda) y muestra, por ejemplo, `FM 88.3` / `AM 810` con el chip de banda (FM1‑3, AM1‑3). Al volver al launcher le pide a la radio que la repita. Como respaldo lee los ajustes `nwd_radio_current_freq/band`. Si la radio no es la fuente activa muestra la última sintonía atenuada ("radio no activa").
- **‹ ›:** tocar = buscar emisora anterior/siguiente; mantener apretado = un paso de frecuencia. Mandan la misma tecla que el kernel emite al apretar el botón físico. Solo funciona con la radio como fuente activa; si no, avisa. Sin equipo NWD (celular de prueba) cae a los controles multimedia de antes.
- **Presets:** si la radio está activa en FM sintonizan de verdad (`ACTION_SET_RADIO_FREQUENCE`); si no, abren la radio del estéreo.
- **Diagnóstico** (Herramientas) ahora incluye el estado de la radio (fuente, frecuencia, si llegaron avisos).
- Sin cambios en el equipo: solo se leen datos y se envían los mismos avisos que ya usa el sistema.
- Igual que antes, no se pudo compilar ni probar en un equipo real; si algo falla, el Diagnóstico dice qué parte.


## Versión 1.2.1: reinicios de la interfaz

- **WebView resistente a poca memoria:** si Android mata el proceso de renderizado del WebView (típico en equipos de 2 GB al abrir apps), antes se caía toda la app y el launcher arrancaba de cero. Ahora se detecta (`onRenderProcessGone`) y se reconstruye la pantalla sin cerrar la app.
- **Menos recreaciones:** la pantalla ahora declara que maneja idioma, tamaño de letra, teclado, navegación, etc., para que un cambio de configuración del equipo no la recree.
- **La radio ya no se abre sola al reiniciarse el launcher:** el "abrir la radio al encender" solo corre si la radio no es ya la fuente activa y no se hizo en los últimos 30 minutos de este mismo arranque.
- **Diagnóstico → sección "Reinicios":** muestra por qué Android cerró el proceso las últimas veces (LOW_MEMORY, SIGNALED, CRASH…) y un registro propio de eventos (arranque con edad del proceso, destrucción de pantalla, WebView muerto, avisos de poca memoria).
- Nota: el kernel NWD tiene una "limpieza con un toque" (`ProcessManager.ClearAllTask`) que elimina las tareas de todas las apps salvo las que contienen "nwd", la navegación elegida, el teclado y una lista `OneClearConfig.xml` del equipo. Ranger Launcher no está en esa lista; si el Diagnóstico muestra que el cierre viene de ahí, hay que pedirle al instalador que lo agregue (no se modifica desde la app).
