package com.ranger.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Base64
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import java.io.File
import android.provider.MediaStore
import android.os.Environment
import android.content.ContentValues
import android.view.KeyEvent
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.xmlpull.v1.XmlPullParser
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Launcher de la Ranger. La interfaz vive en assets/www/index.html (WebView)
 * y esta clase le da los datos reales: velocidad por GPS, música en reproducción,
 * estado de conexiones, y abre las aplicaciones.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var lastFixAt = 0L
    private var locationStarted = false
    private var askedPermission = false
    /** true si salimos hacia otra app; al volver se reproduce la animación de regreso. */
    private var leftForApp = false
    private var lastNight: Boolean? = null
    private var controller: MediaController? = null
    private var lastMediaCheckAt = 0L
    private var lastStatusCheckAt = 0L
    private var lastMediaArtworkKey: String? = null
    private var cachedArtworkData: String? = null
    private var lastMediaStateKey: String? = null
    private var lastSpeedSent = -1
    private var prevLoc: Location? = null
    private var lastLat = -34.65   // por defecto Gran Buenos Aires hasta tener GPS
    private var lastLon = -58.62
    private val changes = ArrayList<String>()
    private val lastSeen = HashMap<String, Long>()

    // Estado de la radio del estéreo (frecuencia cruda tal como la entrega la radio NWD).
    @Volatile private var radioFreq = -1          // FM: MHz x100 (8830 = 88.3) · AM: kHz
    @Volatile private var radioBand = -1          // 0-2 FM1-3 · 3-5 AM1-3 · 6-8 FM local 1-3
    private var gotRadioBroadcast = false
    private var lastRadioPushKey = ""
    private var lastRadioPollAt = 0L
    private var radioReceiverOn = false

    companion object {
        private var autoRadioDone = false

        /*
         * Protocolo de la radio NWD. Todo esto sale de leer com.nwd.radio y com.nwd.kernel:
         *  - La radio escucha (receptor dinámico, sin permiso) com.nwd.action.ACTION_KEY_VALUE con
         *    el byte extra_key_value: es el mismo aviso que manda el kernel cuando se aprieta una tecla
         *    física. Solo actúa si Settings.System mcu_current_source == 4 (la radio es la fuente activa).
         *  - Escucha ACTION_SET_RADIO_FREQUENCE (int extra_radio_frequence) para sintonizar en la banda actual.
         *  - Responde a radiowidget_request_frequence y avisa cada cambio con ACTION_SEND_RADIO_FREQUENCE_NEW
         *    (int extra_frequence + byte extra_band). Solo emite mientras la radio es la fuente activa.
         */
        const val ACT_KEY_VALUE = "com.nwd.action.ACTION_KEY_VALUE"
        const val ACT_SET_FREQ = "com.nwd.action.ACTION_SET_RADIO_FREQUENCE"
        const val ACT_REQ_FREQ = "com.nwd.action.radiowidget_request_frequence"
        const val ACT_FREQ_NEW = "com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW"
        const val X_KEY_VALUE = "extra_key_value"
        const val X_KEY_TYPE = "extra_key_type"
        const val X_FREQ_SET = "extra_radio_frequence"
        const val X_FREQ_NEW = "extra_frequence"
        const val X_BAND_NEW = "extra_band"
        const val SET_SOURCE = "mcu_current_source"
        const val SET_FREQ = "nwd_radio_current_freq"
        const val SET_BAND = "nwd_radio_current_band"
        const val SOURCE_RADIO = 4
        /** Pedido al kernel NWD para cambiar de fuente (la misma orden que usa la app de radio). */
        const val ACT_REQ_SOURCE = "com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE"
        const val X_SOURCE_ID = "extra_source_id"
        const val KEY_FM: Byte = 72

        // Valores de KernelConstant.McuKeyValue
        const val KEY_SEEK_UP: Byte = 16        // paso + (0,1 MHz / 9-10 kHz)
        const val KEY_SEEK_DOWN: Byte = 17      // paso -
        const val KEY_SEARCH_DOWN: Byte = 59    // buscar emisora anterior
        const val KEY_SEARCH_UP: Byte = 60      // buscar emisora siguiente
        const val KEY_EVENT_UP: Byte = 0        // KernelConstant.KeyAndTouchEvent
    }

    /** Registra qué ajustes del sistema cambian (sirve para descubrir cuál cambia con las luces). */
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val u = uri ?: return
            val table = u.pathSegments.firstOrNull() ?: return
            val key = u.lastPathSegment ?: return
            if (key == table) return
            val now = SystemClock.elapsedRealtime()
            if (now - (lastSeen[key] ?: 0L) < 400) return
            lastSeen[key] = now
            val entry = "$table:$key = ${readSetting("$table:$key") ?: "?"}"
            synchronized(changes) {
                changes.add(0, entry)
                while (changes.size > 30) changes.removeAt(changes.size - 1)
            }
            tickLights()
        }
    }
    private val prefs by lazy { getSharedPreferences("ranger_launcher", Context.MODE_PRIVATE) }

    /** Paquetes conocidos para cada acceso. Si no está instalado, se te pide elegir la app. */
    private val defaults: Map<String, List<String>> = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "spotify" to listOf("com.spotify.music"),
        "music" to listOf("com.nwd.android.music.ui"),
        "playstore" to listOf("com.android.vending"),
        "carplay" to emptyList(),
        "flow" to emptyList(),
        "nav" to listOf("com.waze", "com.google.android.apps.maps"),
        "stereogps" to emptyList(),
        "maps" to listOf("com.google.android.apps.maps"),
        "waze" to listOf("com.waze"),
        "browser" to listOf("com.android.chrome"),
        "camera" to emptyList(),
        "radio" to emptyList()
    )

    private val names: Map<String, String> = mapOf(
        "youtube" to "YouTube", "spotify" to "Spotify", "playstore" to "Play Store",
        "carplay" to "CarPlay", "maps" to "Maps", "waze" to "Waze",
        "browser" to "Navegador", "nav" to "Navegación", "stereogps" to "GPS del equipo", "camera" to "Cámara", "radio" to "Radio", "flow" to "Flow",
        "phone" to "Teléfono", "settings" to "Ajustes", "music" to "Música del estéreo"
    )

    /**
     * Cada botón de la pantalla es un "slot" que se puede cambiar con pulsación larga.
     * Los de la barra inferior se llaman dock_xxx (así no pisan al mismo acceso de otra parte).
     */
    private val dockKeys = listOf("dock_waze", "dock_spotify", "dock_carplay", "dock_radio", "dock_youtube", "dock_flow")
    private val otherKeys = listOf("stereogps", "phone", "radio", "settings", "maps", "waze", "carplay", "nav")

    private fun base(slot: String) = slot.removePrefix("dock_")

    /** Si no hay app guardada, se busca una instalada cuyo nombre contenga este texto. */
    private val hints = mapOf("radio" to "radio", "flow" to "flow")

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastFixAt = SystemClock.elapsedRealtime()
            lastLat = l.latitude; lastLon = l.longitude
            val p = prevLoc
            val ms = if (l.hasSpeed()) l.speed.toDouble()
            else if (p != null && l.time > p.time) p.distanceTo(l) / ((l.time - p.time) / 1000.0) else 0.0
            prevLoc = l
            var kmh = ms * 3.6 * (1 + prefs.getInt("speed_pct", 0) / 100.0)
            if (kmh < 3.0) kmh = 0.0 // evita el "temblor" del GPS estando detenido
            val roundedSpeed = kmh.toInt()
            if (roundedSpeed != lastSpeedSent) {
                lastSpeedSent = roundedSpeed
                js("setSpeed($roundedSpeed)")
            }
            js("setLocation(${l.latitude},${l.longitude})")
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lastCrash = installCrashLogger()
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (_: Throwable) {
        }

        safeMode = checkSafeMode()
        logEvent("onCreate proceso=${processAgeSec()}s guardado=${savedInstanceState != null} seguro=$safeMode ${memInfo()}")
        showSplash()
        ui.post {
            try {
                buildWeb()
            } catch (t: Throwable) {
                showFatal("No se pudo iniciar la pantalla (WebView):\n\n" + Log.getStackTraceString(t))
            }
        }

        if (lastCrash != null) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("La app se cerró la vez anterior")
                .setMessage(lastCrash)
                .setPositiveButton("OK", null)
                .show()
        }

        try {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
            contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver)
            contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
            registerMusicReceiver()
        } catch (_: Throwable) { }
        startLocation()
        ui.post(ticker)
    }

    /** Crea el WebView y carga la interfaz. Se reutiliza para reconstruirlo si Android mata su proceso. */
    private var buildAt = 0L
    private var rootFrame: android.widget.FrameLayout? = null
    private var splashView: View? = null

    /** Pantalla oscura inmediata: se ve al instante mientras el WebView se crea y carga. */
    private fun showSplash() {
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#101214"))
        val tv = TextView(this).apply {
            text = "Ford Ranger"
            setTextColor(Color.parseColor("#6f7a83"))
            textSize = 30f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101214"))
        }
        root.addView(tv, android.widget.FrameLayout.LayoutParams(-1, -1))
        rootFrame = root
        splashView = tv
        setContentView(root)
    }

    private fun buildWeb() {
        buildAt = SystemClock.elapsedRealtime()
        web = WebView(this)
        logEvent("WebView creado en ${SystemClock.elapsedRealtime() - buildAt} ms")
        web.setBackgroundColor(Color.parseColor("#101214"))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                logEvent("pantalla lista en ${SystemClock.elapsedRealtime() - buildAt} ms")
                splashView?.let { v ->
                    splashView = null
                    rootFrame = null
                    v.animate().alpha(0f).setDuration(250).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
                }
                pageReady = true
                lastNight = null
                scheduleAutoRadio()
                tick()
                try { pushDock() } catch (_: Throwable) { }
                try { restoreRadio() } catch (_: Throwable) { }
                sendLastKnownLocation()
            }

            /**
             * Con poca memoria Android mata el proceso de renderizado del WebView. Si no se maneja,
             * se cae TODA la app y el launcher arranca de cero. Devolver true la mantiene viva:
             * se descarta el WebView muerto y se crea uno nuevo.
             */
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                logEvent("WebView muerto (crash=${detail?.didCrash()}, prioridad=${detail?.rendererPriorityAtExit()})")
                pageReady = false
                lastMediaArtworkKey = null
                lastSpeedSent = -1
                lastRadioPushKey = ""
                ui.post {
                    try {
                        buildWeb()
                        try { view?.destroy() } catch (_: Throwable) { }
                    } catch (t: Throwable) {
                        showFatal("No se pudo reconstruir la pantalla:\n\n" + Log.getStackTraceString(t))
                    }
                }
                return true
            }
        }
        val rf = rootFrame
        if (rf != null) rf.addView(web, 0, android.widget.FrameLayout.LayoutParams(-1, -1)) else setContentView(web)
        hideBars()
        web.loadUrl("file:///android_asset/www/index.html" + if (safeMode) "?safe=1" else "")
    }

    // ---------------------------------------------------------------- registro de reinicios

    private fun memInfo(): String = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val d = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(d)
        val graf = (d.getMemoryStat("summary.graphics") ?: "0").toLongOrNull()?.div(1024) ?: 0
        val nat = (d.getMemoryStat("summary.native-heap") ?: "0").toLongOrNull()?.div(1024) ?: 0
        val adj = try { java.io.File("/proc/self/oom_score_adj").readText().trim() } catch (_: Throwable) { "?" }
        "libre=${mi.availMem / 1048576}/${mi.totalMem / 1048576}MB umbral=${mi.threshold / 1048576}MB pss=${d.totalPss / 1024}MB graf=${graf}MB nativo=${nat}MB adj=$adj"
    } catch (_: Throwable) {
        ""
    }

    private var safeMode = false

    /** Si el proceso arranca 3 veces en 90 s (bucle de reinicios), se entra 10 min en modo seguro: sin animaciones. */
    private fun checkSafeMode(): Boolean {
        val now = System.currentTimeMillis()
        val starts = (prefs.getString("starts", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            .filter { now - it < 90_000 } + now
        val e = prefs.edit().putString("starts", starts.joinToString(","))
        if (starts.size >= 3) e.putLong("safe_until", now + 10 * 60_000L)
        e.commit()
        return prefs.getLong("safe_until", 0L) > now
    }

    private var webReleased = false
    private var stopped = false
    private val releaseWeb = Runnable { releaseWebView() }

    /**
     * El WebView (y su proceso de renderizado) es lo que más memoria usa. Con otra app al frente se
     * descarta para que el launcher quede liviano y Android no lo mate y reinicie en bucle;
     * se vuelve a crear al volver a la pantalla de inicio.
     */
    private fun releaseWebView() {
        if (!::web.isInitialized || webReleased || !stopped) return
        webReleased = true
        pageReady = false
        lastMediaArtworkKey = null
        lastSpeedSent = -1
        lastRadioPushKey = ""
        logEvent("WebView liberado ${memInfo()}")
        try {
            val old = web
            setContentView(View(this).apply { setBackgroundColor(Color.parseColor("#101214")) })
            old.stopLoading()
            old.destroy()
        } catch (_: Throwable) { }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        ui.removeCallbacks(releaseWeb)
        if (webReleased) {
            webReleased = false
            try {
                buildWeb()
            } catch (t: Throwable) {
                showFatal("No se pudo reconstruir la pantalla:\n\n" + Log.getStackTraceString(t))
            }
        }
    }

    override fun onStop() {
        stopped = true
        ui.removeCallbacks(releaseWeb)
        ui.postDelayed(releaseWeb, 10 * 60_000L)
        super.onStop()
    }

    private fun processAgeSec(): Long =
        (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000

    /** Guarda los últimos eventos (en disco) para poder ver en Diagnóstico qué provocó un reinicio. */
    private fun logEvent(msg: String) {
        try {
            val t = java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(java.util.Date())
            val old = (prefs.getString("evlog", "") ?: "").split("\n").filter { it.isNotBlank() }
            prefs.edit().putString("evlog", (listOf("$t $msg") + old).take(14).joinToString("\n")).commit()
        } catch (_: Throwable) { }
    }

    private fun exitName(r: Int) = when (r) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED (lo mataron)"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"
        6 -> "ANR"; 7 -> "INIT_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE"
        10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"
        else -> "?$r"
    }

    /** Por qué murió el proceso las últimas veces (lo informa Android) + eventos propios. */
    private fun restartDiag(): String {
        val sb = StringBuilder("\n— Reinicios —\n")
        sb.append("Memoria: ${memInfo()}\n")
        try {
            if (Build.VERSION.SDK_INT < 30) {
                sb.append("Este Android no informa por qué se cierra el proceso\n")
                throw IllegalStateException("sin historial")
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 6)
            if (list.isEmpty()) sb.append("Android no registra cierres del proceso\n")
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
            for (e in list) {
                sb.append(fmt.format(java.util.Date(e.timestamp))).append(" ").append(exitName(e.reason))
                    .append(" · ").append(e.description ?: "").append(" · imp=").append(e.importance).append("\n")
            }
        } catch (t: Throwable) {
            if (t !is IllegalStateException) sb.append("No se pudo leer el historial: ${t.javaClass.simpleName}\n")
        }
        val ev = prefs.getString("evlog", "") ?: ""
        sb.append("Eventos del launcher:\n").append(if (ev.isBlank()) "(vacío)" else ev)
        return sb.toString()
    }

    /** Guarda el error si la app se cierra, y lo devuelve la próxima vez para mostrarlo. */
    private fun installCrashLogger(): String? {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { t, e ->
            try {
                prefs.edit().putString("last_crash", Log.getStackTraceString(e).take(2500)).commit()
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        })
        val last = prefs.getString("last_crash", null)
        if (last != null) prefs.edit().remove("last_crash").commit()
        return last
    }

    private fun showFatal(msg: String) {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101214"))
            addView(tv)
        })
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        startLocation()
        if (::web.isInitialized && !webReleased) { web.onResume(); web.resumeTimers() }
        startRadioWatch()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
        if (leftForApp) {
            leftForApp = false
            js("launchDone()") // animación de regreso a la interfaz
        }
    }

    override fun onPause() {
        // No consultar sesiones, conectividad ni GPS mientras otra app está al frente.
        ui.removeCallbacks(ticker)
        stopLocation()
        stopRadioWatch()
        // Congela JavaScript y animaciones del WebView mientras otra app está al frente.
        if (::web.isInitialized && !webReleased) { web.onPause(); web.pauseTimers() }
        super.onPause()
    }

    /** El equipo cambió entre modo día y noche: actualizar las luces al instante. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideBars()
        tickLights()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Botón Home: ya estamos en la pantalla de inicio.
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Un launcher no debe cerrarse con "atrás".
    }

    private var lastTrimLog = 0L

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Solo los avisos serios (memoria crítica / proceso en riesgo de ser eliminado).
        // Los niveles 5/10/15 los manda Android cuando quedan pocos procesos en caché (con Spotify en
        // segundo plano pasa seguido) aunque sobre RAM: se registran como mucho cada 2 min y no se actúa.
        val now = SystemClock.elapsedRealtime()
        if (level >= TRIM_MEMORY_UI_HIDDEN || now - lastTrimLog > 120_000L) {
            lastTrimLog = now
            logEvent("aviso de memoria nivel=$level (5-15 = pocos procesos en caché, no falta de RAM) ${memInfo()}")
        }
        if (stopped && level >= TRIM_MEMORY_MODERATE) releaseWebView()
        if (level >= TRIM_MEMORY_MODERATE && ::web.isInitialized && !webReleased) {
            try { web.clearCache(false) } catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        logEvent("onDestroy finalizando=$isFinishing cambioConfig=$isChangingConfigurations")
        try { contentResolver.unregisterContentObserver(settingsObserver) } catch (_: Throwable) { }
        try { unregisterReceiver(musicReceiver) } catch (_: Throwable) { }
        stopRadioWatch()
        ui.removeCallbacks(ticker)
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        if (::web.isInitialized) {
            try {
                web.destroy()
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
    }

    // ---------------------------------------------------------------- pantalla

    private fun hideBars() {
        try {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Throwable) {
        }
    }

    private fun js(code: String) {
        if (!pageReady || webReleased) return
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Tema oscuro (noche) = luces de cruce encendidas; tema claro (día) = apagadas. */
    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---------------------------------------------------------------- ciclo de 1 s

    /** Envía el estado de las luces solo cuando cambia (día→noche enciende, noche→día apaga). */
    private fun tickLights(force: Boolean = false) {
        val n = lightsWanted()
        if (!force && n == lastNight) return
        lastNight = n
        js("setLights($n)")
    }

    private fun tick() {
        if (!pageReady || isFinishing) return
        val now = SystemClock.elapsedRealtime()
        try { tickLights() } catch (_: Throwable) { }
        // El GPS sigue actualizando la velocidad por su listener; la música no necesita
        // consultar MediaSession ni reconstruir imágenes 10 veces por segundo.
        if (now - lastMediaCheckAt >= 2000L) {
            lastMediaCheckAt = now
            try { pushMedia() } catch (_: Throwable) { }
        }
        if (now - lastRadioPollAt >= 2000L) {
            lastRadioPollAt = now
            try { pollRadio() } catch (_: Throwable) { }
        }
        if (now - lastStatusCheckAt >= 10000L) {
            lastStatusCheckAt = now
            try { pushStatus() } catch (_: Throwable) { }
        }
        if (now - lastFixAt > 4000 && lastSpeedSent != 0) {
            lastSpeedSent = 0
            js("setSpeed(0)")
        }
    }

    // ---------------------------------------------------------------- luces

    private fun readSetting(ref: String): String? = try {
        val i = ref.indexOf(':')
        val t = ref.substring(0, i)
        val k = ref.substring(i + 1)
        when (t) {
            "system" -> Settings.System.getString(contentResolver, k)
            "secure" -> Settings.Secure.getString(contentResolver, k)
            else -> Settings.Global.getString(contentResolver, k)
        }
    } catch (_: Throwable) {
        null
    }

    /** true si el sol está bajo el horizonte (con un poco de crepúsculo). Usa la última posición GPS. */
    private fun sunBelowHorizon(): Boolean {
        val d = System.currentTimeMillis() / 86400000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
        val q = (280.459 + 0.98564736 * d) % 360.0
        val lon = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360.0)
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(lon), cos(lon))
        val dec = asin(sin(e) * sin(lon))
        val lst = Math.toRadians((((18.697374558 + 24.06570982441908 * d) % 24.0) * 15.0 + lastLon + 360.0) % 360.0)
        val lat = Math.toRadians(lastLat)
        val alt = asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(lst - ra))
        return Math.toDegrees(alt) < 2.0
    }

    private fun keyLights(): Boolean? {
        val ref = prefs.getString("light_key", null) ?: return null
        val v = readSetting(ref) ?: return null
        val on = prefs.getString("light_on_val", null)
        val off = prefs.getString("light_off_val", null)
        return if (on != null) v == on else if (off != null) v != off else null
    }

    private fun lightsWanted(): Boolean = when (prefs.getString("light_mode", "ui")) {
        "on" -> true
        "off" -> false
        "sun" -> sunBelowHorizon()
        "key" -> keyLights() ?: isNight()
        else -> isNight()
    }

    private fun lightModeName() = when (prefs.getString("light_mode", "ui")) {
        "on" -> "siempre encendidas"
        "off" -> "siempre apagadas"
        "sun" -> "por hora (sol)"
        "key" -> "por ajuste del equipo"
        else -> "tema día/noche"
    }

    // ---------------------------------------------------------------- radio

    private fun isRadioPkg(p: String) = p == prefs.getString("app_radio", null) || p.contains("radio", true)

    private fun radioController(): MediaController? = try {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java)).firstOrNull { isRadioPkg(it.packageName) }
    } catch (_: Throwable) {
        null
    }

    private fun sendMediaKey(code: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Throwable) { }
    }

    // ---- Radio NWD: frecuencia real y teclas ‹ › -------------------------------------------------

    private fun isFmBand(b: Int) = b in 0..2 || b in 6..8   // FM1-3 y FM local 1-3 (AM = 3..5)

    /** Fuente activa del estéreo (4 = radio). -1 si el equipo no tiene la pila NWD. */
    private fun nwdSource(): Int = try {
        Settings.System.getInt(contentResolver, SET_SOURCE, -1)
    } catch (_: Throwable) {
        -1
    }

    /** La radio avisa cada cambio de frecuencia por broadcast, pero solo mientras es la fuente activa. */
    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val intent = i ?: return
            if (intent.action != ACT_FREQ_NEW) return
            val f = intent.getIntExtra(X_FREQ_NEW, -1)
            val b = intent.getByteExtra(X_BAND_NEW, (-1).toByte()).toInt()
            if (f <= 0 || b < 0) return
            gotRadioBroadcast = true
            // Durante una búsqueda llegan muchas frecuencias seguidas: solo se guarda si cambió algo.
            if (f != radioFreq || b != radioBand) {
                prefs.edit().putInt("radio_last_freq", f).putInt("radio_last_band", b).apply()
            }
            pushRadio(f, b)
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun startRadioWatch() {
        if (!radioReceiverOn) {
            try {
                val f = IntentFilter(ACT_FREQ_NEW)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(radioReceiver, f, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(radioReceiver, f)
                }
                radioReceiverOn = true
            } catch (_: Throwable) { }
        }
        // Pide a la radio que repita la frecuencia actual (responde solo si es la fuente activa).
        try { sendBroadcast(Intent(ACT_REQ_FREQ)) } catch (_: Throwable) { }
    }

    private fun stopRadioWatch() {
        if (!radioReceiverOn) return
        try { unregisterReceiver(radioReceiver) } catch (_: Throwable) { }
        radioReceiverOn = false
    }

    /** Manda la frecuencia a la interfaz solo si algo cambió (frecuencia, banda o radio activa/inactiva). */
    private fun pushRadio(freq: Int, band: Int) {
        if (freq <= 0 || band < 0) return
        radioFreq = freq
        radioBand = band
        val active = nwdSource() == SOURCE_RADIO
        val key = "$freq|$band|$active"
        if (key == lastRadioPushKey) return
        lastRadioPushKey = key
        js("setRadioFreq($freq,$band,$active)")
    }

    /** Al abrir la interfaz: muestra lo último que se supo; si no hay nada, lo que guardó el equipo. */
    private fun restoreRadio() {
        lastRadioPushKey = ""
        val f = if (radioFreq > 0) radioFreq else prefs.getInt("radio_last_freq", -1)
        val b = if (radioBand >= 0) radioBand else prefs.getInt("radio_last_band", -1)
        if (f > 0 && b >= 0) pushRadio(f, b) else pollRadio()
    }

    /** Lectura pasiva (cada 2 s): ajustes que escribe la radio con MCU y estado activo/inactivo. */
    private fun pollRadio() {
        if (!gotRadioBroadcast) {
            val f = Settings.System.getInt(contentResolver, SET_FREQ, -1)
            val b = Settings.System.getInt(contentResolver, SET_BAND, -1)
            if (f > 0 && b >= 0) { pushRadio(f, b); return }
        }
        if (radioFreq > 0 && radioBand >= 0) pushRadio(radioFreq, radioBand) // refresca "activa / no activa"
    }

    /**
     * Botones ‹ › de la tarjeta Radio. Se manda la misma tecla que el kernel emite al apretar el botón
     * físico. Si la radio no es la fuente activa no hace nada (la radio ignoraría la tecla) y avisa.
     * Sin pila NWD (por ejemplo en un celular de prueba) cae a los controles multimedia genéricos.
     */
    private fun radioCommand(action: String) {
        val key: Byte = when (action) {
            "down" -> KEY_SEARCH_DOWN
            "up" -> KEY_SEARCH_UP
            "stepdown" -> KEY_SEEK_DOWN
            "stepup" -> KEY_SEEK_UP
            else -> return
        }
        val src = nwdSource()
        if (src == SOURCE_RADIO) {
            sendBroadcast(Intent(ACT_KEY_VALUE).putExtra(X_KEY_VALUE, key).putExtra(X_KEY_TYPE, KEY_EVENT_UP))
            return
        }
        if (src >= 0) {
            ensureRadio {
                sendBroadcast(Intent(ACT_KEY_VALUE).putExtra(X_KEY_VALUE, key).putExtra(X_KEY_TYPE, KEY_EVENT_UP))
            }
            return
        }
        val up = action == "up" || action == "stepup"
        val c = radioController()
        if (c != null) {
            if (up) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
        } else {
            sendMediaKey(if (up) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    /** Sintoniza una FM (MHz x100, p. ej. 8830) en la banda actual. Devuelve false si no se pudo. */
    private fun radioTune(freq: Int): Boolean {
        if (freq < 6500 || freq > 10800) return false
        runOnUiThread {
            val tune = {
                if (isFmBand(radioBand)) {
                    sendBroadcast(Intent(ACT_SET_FREQ).putExtra(X_FREQ_SET, freq))
                } else {
                    // estaba en AM o no se sabe: pasar a FM y luego sintonizar
                    sendBroadcast(Intent(ACT_KEY_VALUE).putExtra(X_KEY_VALUE, KEY_FM).putExtra(X_KEY_TYPE, KEY_EVENT_UP))
                    ui.postDelayed({ sendBroadcast(Intent(ACT_SET_FREQ).putExtra(X_FREQ_SET, freq)) }, 400)
                }
            }
            if (nwdSource() == SOURCE_RADIO) tune() else ensureRadio { tune() }
        }
        return true
    }

    /**
     * Deja la radio como fuente activa sin que el usuario pase por la app: le pide el cambio de fuente al
     * kernel NWD (como hace la app de radio) y, cuando el sistema abre la pantalla de la radio, vuelve al
     * launcher enseguida. La radio sigue sonando; después se ejecuta [then].
     */
    private fun ensureRadio(then: () -> Unit) {
        if (nwdSource() == SOURCE_RADIO) { then(); return }
        if (radioStarting) return
        radioStarting = true
        sendBroadcast(Intent(ACT_REQ_SOURCE).putExtra(X_SOURCE_ID, SOURCE_RADIO.toByte()))
        val t0 = SystemClock.elapsedRealtime()
        fun poll() {
            if (nwdSource() == SOURCE_RADIO) {
                ui.postDelayed({
                    bringLauncherFront()
                    ui.postDelayed({ radioStarting = false; then() }, 700)
                }, 900)
            } else if (SystemClock.elapsedRealtime() - t0 > 4500) {
                radioStarting = false
                toast("No pude activar la radio. Probá «Abrir radio del estéreo».")
            } else {
                ui.postDelayed({ poll() }, 150)
            }
        }
        poll()
    }

    private var radioStarting = false

    /** Trae el launcher al frente (hasta 3 intentos, por si la app de radio tarda en abrirse). */
    private fun bringLauncherFront(tries: Int = 3) {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Throwable) { }
        if (tries > 1) ui.postDelayed({ if (stopped) bringLauncherFront(tries - 1) }, 1200)
    }

    private fun radioDiag(): String {
        val s = Settings.System.getInt(contentResolver, SET_SOURCE, -1)
        val f = Settings.System.getInt(contentResolver, SET_FREQ, -1)
        val b = Settings.System.getInt(contentResolver, SET_BAND, -1)
        return "Radio NWD: fuente=$s (4=radio) · última frec=$radioFreq banda=$radioBand\n" +
            "Ajustes: frec=$f banda=$b · aviso recibido=$gotRadioBroadcast · receptor=$radioReceiverOn"
    }

    /** Al encender: si no suena nada, abre la radio un instante y vuelve al launcher (la radio queda sonando). */
    private fun scheduleAutoRadio() {
        if (autoRadioDone || !prefs.getBoolean("radio_auto", true)) return
        autoRadioDone = true
        // Si el launcher solo se reinició (no fue un arranque del equipo) no hay que abrir la radio otra vez:
        // ya suena (fuente = radio) o ya se hizo hace poco en este mismo arranque.
        if (nwdSource() == SOURCE_RADIO) return
        val boot = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Throwable) { -1 }
        val nowMs = System.currentTimeMillis()
        if (boot == prefs.getInt("radio_auto_boot", -2) && nowMs - prefs.getLong("radio_auto_at", 0L) < 30 * 60 * 1000L) return
        prefs.edit().putInt("radio_auto_boot", boot).putLong("radio_auto_at", nowMs).apply()
        ui.postDelayed({
            try {
                if (controller != null) return@postDelayed
                val pkg = resolvePkg("radio") ?: return@postDelayed
                val i = packageManager.getLaunchIntentForPackage(pkg) ?: return@postDelayed
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                ui.postDelayed({
                    try {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    } catch (_: Throwable) { }
                }, 2500)
            } catch (_: Throwable) { }
        }, 4000)
    }

    /** Lista los comandos (acciones) que la app de radio declara en su manifiesto. */
    private fun scanRadio(): String {
        val pkg = resolvePkg("radio") ?: return "No encontré la app de radio. Asignala primero al botón Radio (pulsación larga)."
        val sb = StringBuilder("App: $pkg\n\n")
        try {
            val ns = "http://schemas.android.com/apk/res/android"
            val xml = packageManager.getResourcesForApplication(pkg).assets.openXmlResourceParser("AndroidManifest.xml")
            var kind = ""
            var comp = ""
            var ev = xml.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "receiver", "service", "activity" -> {
                            kind = xml.name
                            comp = (xml.getAttributeValue(ns, "name") ?: "").substringAfterLast('.')
                        }
                        "action" -> {
                            val a = xml.getAttributeValue(ns, "name") ?: ""
                            if (kind != "activity" && a.isNotEmpty()) sb.append("$kind $comp: $a\n")
                        }
                    }
                }
                ev = xml.next()
            }
        } catch (t: Throwable) {
            sb.append("No se pudo leer: $t")
        }
        sb.append(nwdComponents())
        return sb.toString().take(9000)
    }

    /** Servicios y receptores de los paquetes com.nwd* / radio (* = exportado, o sea, usable desde otra app). */
    private fun nwdComponents(): String {
        val sb = StringBuilder("\n--- Servicios (S) y receptores (R) ---\n")
        val pm = packageManager
        val pkgs = try {
            pm.getInstalledPackages(0).map { it.packageName }.filter { it.startsWith("com.nwd") || it.contains("radio", true) }
        } catch (_: Throwable) {
            emptyList()
        }
        for (pk in pkgs.take(12)) {
            try {
                val pi = pm.getPackageInfo(pk, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
                sb.append("\n$pk\n")
                pi.services?.forEach { sb.append("  S ${it.name.substringAfterLast('.')}${if (it.exported) " *" else ""}\n") }
                pi.receivers?.forEach { sb.append("  R ${it.name.substringAfterLast('.')}${if (it.exported) " *" else ""}\n") }
            } catch (_: Throwable) { }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- herramientas (pulsación larga en "Luces")

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showText(title: String, msg: String) {
        dlg().setTitle(title).setMessage(msg).setPositiveButton("OK", null)
            .setNeutralButton("Copiar") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("ranger", msg))
                toast("Copiado")
            }.show()
    }

    private fun showTools() {
        val items = arrayOf(
            "Luces: ${lightModeName()}",
            "Detectar luces: ver cambios del equipo",
            "Radio: inicio automático ${if (prefs.getBoolean("radio_auto", true)) "SÍ" else "NO"}",
            "Radio: ver comandos de la app",
            "Velocímetro: ajuste +${prefs.getInt("speed_pct", 0)} %",
            "Diagnóstico",
            "Radio: exportar APK para analizar",
            "Ver errores recientes (logcat)",
            "Exportar APK del launcher original",
            "Reiniciar el estéreo",
            "Exportar APK de una app (elegir)"
        )
        dlg().setTitle("Herramientas").setItems(items) { _, i ->
            when (i) {
                0 -> chooseLightMode()
                1 -> showLightChanges()
                2 -> {
                    prefs.edit().putBoolean("radio_auto", !prefs.getBoolean("radio_auto", true)).apply()
                    toast("Listo")
                }
                3 -> showText("Comandos de la app de radio", scanRadio())
                4 -> chooseSpeedAdjust()
                5 -> showText("Diagnóstico", diagnostics())
                6 -> exportRadioApks()
                7 -> showRecentLog()
                8 -> exportLauncherApks()
                9 -> confirmReboot()
                10 -> chooseAppToExport()
            }
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun chooseLightMode() {
        val modes = arrayOf("ui", "sun", "on", "off")
        val names = arrayOf("Tema día/noche del equipo", "Por hora del día (sol, usa el GPS)", "Siempre encendidas", "Siempre apagadas")
        dlg().setTitle("Luces de cruce").setItems(names) { _, i ->
            prefs.edit().putString("light_mode", modes[i]).apply()
            tickLights(true)
        }.show()
    }

    private fun showLightChanges() {
        val list = synchronized(changes) { changes.toList() }
        if (list.isEmpty()) {
            showText("Cambios del equipo", "Todavía no se detectó ningún cambio.\n\nEncendé y apagá las luces de la camioneta y volvé a abrir esta pantalla. El ajuste que cambie con las luces va a aparecer acá.")
            return
        }
        dlg().setTitle("Elegí el ajuste que cambió con las luces").setItems(list.toTypedArray()) { _, i ->
            val ref = list[i].substringBefore(" = ")
            val cur = readSetting(ref) ?: ""
            dlg().setTitle("¿Las luces están ENCENDIDAS ahora?")
                .setMessage("Ajuste: $ref\nValor actual: $cur")
                .setPositiveButton("Sí, encendidas") { _, _ -> saveLightKey(ref, cur, true) }
                .setNegativeButton("No, apagadas") { _, _ -> saveLightKey(ref, cur, false) }
                .show()
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun saveLightKey(ref: String, cur: String, on: Boolean) {
        val e = prefs.edit().putString("light_key", ref).putString("light_mode", "key")
        if (on) e.putString("light_on_val", cur).remove("light_off_val") else e.putString("light_off_val", cur).remove("light_on_val")
        e.apply()
        tickLights(true)
        toast("Guardado")
    }

    private fun chooseSpeedAdjust() {
        val v = intArrayOf(0, 2, 3, 5)
        dlg().setTitle("Velocímetro: sumar a la velocidad GPS")
            .setItems(arrayOf("0 % (velocidad real)", "+2 %", "+3 %", "+5 %")) { _, i ->
                prefs.edit().putInt("speed_pct", v[i]).apply()
                lastSpeedSent = -1
            }.show()
    }

    /** Copia los APK de la radio y del núcleo NWD a Descargas, para poder analizarlos y cablear el control real. */
    /** Busca el launcher original de la radio (y apps de temas) y copia sus APK a Descargas. */
    private fun exportLauncherApks() {
        val pm = packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        val themed = try {
            pm.getInstalledPackages(0).map { it.packageName }.filter { it.contains("launcher", true) || it.contains("theme", true) }
        } catch (_: Throwable) {
            emptyList()
        }
        val list = (homes + themed + listOf("com.nwd.android.music.ui"))
            .filter { it != packageName && !it.startsWith("com.google") && !it.startsWith("com.android.") }
            .distinct().take(7)
        if (list.isEmpty()) toast("No encontré otro launcher") else exportRadioApks(list)
    }

    private fun confirmReboot() {
        dlg().setTitle("Reiniciar el estéreo")
            .setMessage("Se va a reiniciar toda la radio. ¿Continuar?")
            .setPositiveButton("Reiniciar") { _, _ -> doReboot() }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Prueba los métodos que permite Android a una app común; si ninguno sirve, lo dice. */
    private fun doReboot() {
        Thread {
            var ok = false
            try {
                (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).reboot(null)
                ok = true
            } catch (_: Throwable) { }
            if (!ok) {
                for (cmd in listOf(arrayOf("su", "-c", "reboot"), arrayOf("reboot"))) {
                    try {
                        val p = Runtime.getRuntime().exec(cmd)
                        if (p.waitFor() == 0) { ok = true; break }
                    } catch (_: Throwable) { }
                }
            }
            if (!ok) runOnUiThread {
                showText("No se pudo reiniciar",
                    "Este equipo no deja reiniciar desde una app común (hace falta un permiso de sistema o root).\n\n" +
                    "Para copiar el método de la app que sí lo hace: Herramientas > Exportar APK de una app (elegir), " +
                    "elegila y subí el archivo.")
            }
        }.start()
    }

    /** Lista todas las apps con ícono y exporta el APK de la que se elija. */
    private fun chooseAppToExport() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val labels = apps.map { "${it.loadLabel(pm)}  (${it.activityInfo.packageName})" }.toTypedArray()
        dlg().setTitle("¿Qué app querés exportar?")
            .setItems(labels) { _, i -> exportRadioApks(listOf(apps[i].activityInfo.packageName)) }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun exportRadioApks(extra: List<String>? = null) {
        val pkgs = extra ?: listOfNotNull(resolvePkg("radio"), "com.nwd.kernel").distinct()
        Thread {
            val res = ArrayList<String>()
            for (pk in pkgs) {
                try {
                    val src = File(packageManager.getApplicationInfo(pk, 0).sourceDir)
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$pk.apk")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri == null) {
                        res.add("$pk: no se pudo crear el archivo")
                    } else {
                        contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                        res.add("$pk.apk (${src.length() / 1048576} MB)")
                    }
                } catch (t: Throwable) {
                    res.add("$pk: error ${t.javaClass.simpleName}")
                }
            }
            runOnUiThread { showText("Exportación", "Guardado en la carpeta Descargas:\n" + res.joinToString("\n")) }
        }.start()
    }

    /** Últimas líneas de advertencia/error del registro del sistema (Android solo muestra las de esta app). */
    private fun showRecentLog() {
        Thread {
            val txt = try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "800", "*:W"))
                val lines = p.inputStream.bufferedReader().readLines().filter { it.length > 20 && !it.startsWith("---") }
                val key = Regex("fatal|signal|crash|exception|died|killed|chromium|webview|gpu|oom|abort", RegexOption.IGNORE_CASE)
                val hits = lines.filter { key.containsMatchIn(it) }.takeLast(30)
                (if (hits.isNotEmpty()) hits else lines.takeLast(30)).joinToString("\n") { it.take(170) }
                    .ifBlank { "(el sistema no dejó leer el registro)" }
            } catch (t: Throwable) {
                "No se pudo leer el registro: ${t.javaClass.simpleName}"
            }
            runOnUiThread { showText("Errores recientes", txt) }
        }.start()
    }

    private fun diagnostics(): String {
        val wv = try { WebView.getCurrentWebViewPackage()?.versionName } catch (_: Throwable) { null }
        val gps = if (SystemClock.elapsedRealtime() - lastFixAt < 4000) "con señal" else "sin señal"
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nWebView: ${wv ?: "?"}\n" +
            "Modo noche del sistema: ${isNight()}\nLuces (${lightModeName()}): ${lightsWanted()}\n" +
            "GPS: $gps\nRadio: ${resolvePkg("radio") ?: "sin asignar"}\n" +
            "${radioDiag()}\n" +
            "Acceso a notificaciones: ${notificationAccessGranted()}" +
            restartDiag()
    }

    private fun stopLocation() {
        if (!locationStarted) return
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        } catch (_: Exception) { }
        locationStarted = false
    }

    // ---------------------------------------------------------------- GPS (velocidad)

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocationPermission()) {
            if (!askedPermission) {
                askedPermission = true
                try {
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        100
                    )
                } catch (_: Throwable) {
                }
            }
            return
        }
        if (locationStarted) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 0f, locationListener, Looper.getMainLooper())
            locationStarted = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Para el clima: usa la última posición conocida aunque todavía no haya fix de GPS. */
    private fun sendLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (loc != null) {
                lastLat = loc.latitude; lastLon = loc.longitude
                js("setLocation(${loc.latitude},${loc.longitude})")
            }
        } catch (_: SecurityException) {
        }
    }

    // ---------------------------------------------------------------- música

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    // ---------------------------------------------------------------- música sin acceso a notificaciones

    private class BMedia(val source: String) {
        var title = ""
        var artist = ""
        var album = ""
        var dur = 0L
        var pos = 0L
        var playing = false
        var at = 0L
    }

    private val bmedia = HashMap<String, BMedia>()

    /** Spotify (con "Estado de transmisión del dispositivo" activado) y reproductores clásicos avisan lo que suena. */
    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            val a = i.action ?: return
            val spotify = a.startsWith("com.spotify.music.")
            val b = bmedia.getOrPut(if (spotify) "spotify" else "stereo") {
                BMedia(if (spotify) "Spotify" else "Música del estéreo")
            }
            fun num(k: String) = (i.extras?.get(k) as? Number)?.toLong()
            val old = b.title
            i.getStringExtra("track")?.let { b.title = it }
            i.getStringExtra("artist")?.let { b.artist = it }
            i.getStringExtra("album")?.let { b.album = it }
            if (b.title != old) b.pos = 0L
            num("length")?.let { b.dur = it }
            num("duration")?.let { b.dur = it }
            num("playbackPosition")?.let { b.pos = it }
            num("position")?.let { b.pos = it }
            if (i.hasExtra("playing")) b.playing = i.getBooleanExtra("playing", false)
            else if (!spotify && a.endsWith("metachanged")) b.playing = true
            if (a.endsWith("playbackcomplete")) b.playing = false
            b.at = SystemClock.elapsedRealtime()
        }
    }

    private fun registerMusicReceiver() {
        val f = IntentFilter()
        listOf(
            "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged", "com.spotify.music.queuechanged",
            "com.android.music.metachanged", "com.android.music.playstatechanged",
            "com.android.music.playbackcomplete", "com.android.music.queuechanged"
        ).forEach { f.addAction(it) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(musicReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(musicReceiver, f)
    }

    private fun freshBMedia(): BMedia? {
        val now = SystemClock.elapsedRealtime()
        return bmedia.values.filter { it.title.isNotEmpty() && (it.playing || now - it.at < 15 * 60_000L) }.maxByOrNull { it.at }
    }

    private fun pushBMedia(b: BMedia) {
        var pos = b.pos
        if (b.playing) pos += SystemClock.elapsedRealtime() - b.at
        if (b.dur > 0) pos = pos.coerceAtMost(b.dur)
        val key = "b|${b.source}|${b.title}|${b.artist}|${b.album}|${b.dur}"
        val o = JSONObject().put("title", b.title).put("artist", b.artist).put("source", b.source)
            .put("playing", b.playing).put("pos", pos).put("dur", b.dur).put("isRadio", false)
        if (key != lastMediaArtworkKey) {
            lastMediaArtworkKey = key
            cachedArtworkData = null
            o.put("artwork", JSONObject.NULL)
        }
        js("setMedia($o)")
    }

    /** Reproduce la música del estéreo: tecla play y, si no suena nada, abre su app un instante y vuelve. */
    private fun startStereoMusic() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        val pkg = resolvePkg("music")
        if (pkg == null) {
            pickApp("music", true)
            return
        }
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        ui.postDelayed({
            try {
                if (am.isMusicActive) return@postDelayed
                val i = packageManager.getLaunchIntentForPackage(pkg) ?: return@postDelayed
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                ui.postDelayed({
                    try {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    } catch (_: Throwable) { }
                    ui.postDelayed({ sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 1200)
                }, 2500)
            } catch (_: Throwable) { }
        }, 1500)
    }

    private fun pushMedia() {
        val access = notificationAccessGranted()
        val c: MediaController? = if (!access) null else try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
        controller = c
        if (c == null) {
            val b = freshBMedia()
            if (b != null) pushBMedia(b)
            else js(if (access) "setMedia({idle:true})" else "setMedia({idle:true,needsAccess:true})")
            return
        }
        val md = c.metadata
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = (st?.position ?: 0L).coerceAtLeast(0L)
        if (st != null && playing) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val source = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
        } catch (_: Exception) {
            c.packageName
        }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val isRadio = isRadioPkg(c.packageName)
        val mediaKey = "${c.packageName}|$title|$artist|$album|$dur"
        val stateKey = "$mediaKey|$source|$playing"
        val o = JSONObject()
            .put("title", title).put("isRadio", isRadio)
            .put("artist", artist)
            .put("source", source)
            .put("playing", playing)
            .put("pos", pos)
            .put("dur", dur)

        // Codificar carátulas es caro: hacerlo solo al cambiar de canción/álbum.
        val artworkChanged = mediaKey != lastMediaArtworkKey
        if (artworkChanged) {
            lastMediaArtworkKey = mediaKey
            cachedArtworkData = null
            try {
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                if (art != null) {
                    val scaled = if (art.width > 384 || art.height > 384) {
                        val ratio = minOf(384f / art.width, 384f / art.height)
                        Bitmap.createScaledBitmap(art, (art.width * ratio).toInt().coerceAtLeast(1), (art.height * ratio).toInt().coerceAtLeast(1), true)
                    } else art
                    val bytes = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 76, bytes)
                    cachedArtworkData = "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    if (scaled !== art) scaled.recycle()
                }
            } catch (_: Exception) { }
        }
        if (artworkChanged) o.put("artwork", cachedArtworkData)
        // El progreso sigue actualizándose, pero la imagen solo cruza el puente al cambiar.
        lastMediaStateKey = stateKey
        js("setMedia($o)")
    }

    // ---------------------------------------------------------------- conexiones

    private fun pushStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
        js("setStatus({wifi:$wifi,bt:$bt})")
    }

    // ---------------------------------------------------------------- abrir apps

    private fun tryStart(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        leftForApp = true
        // Transición suave entre la interfaz y la app (zoom + fundido)
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.app_enter, R.anim.launcher_exit)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun launch(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return tryStart(i)
    }

    private fun customFor(slot: String): String? =
        prefs.getString("app_$slot", null)
            ?: if (slot.startsWith("dock_")) prefs.getString("app_${base(slot)}", null) else null

    private fun resolvePkg(slot: String): String? {
        val b = base(slot)
        val cands = listOfNotNull(customFor(slot)) + (defaults[b] ?: emptyList())
        cands.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }?.let { return it }
        val hint = hints[b] ?: return null
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .firstOrNull { it.activityInfo.packageName != packageName && it.loadLabel(pm).toString().contains(hint, true) }
            ?.activityInfo?.packageName
    }

    /** Devuelve true si se abrió una app (si no, se muestra el selector). */
    private fun openApp(slot: String): Boolean {
        val pkg = resolvePkg(slot)
        if (pkg != null && launch(pkg)) return true
        val fallback: Intent? = when (base(slot)) {
            "phone" -> Intent(Intent.ACTION_DIAL)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            else -> null
        }
        if (fallback != null) {
            val ok = tryStart(fallback)
            if (!ok) toast("No se pudo abrir ${names[base(slot)] ?: slot}")
            return ok
        }
        pickApp(slot, true)
        return false
    }

    private fun iconB64(d: Drawable): String {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Envía a la interfaz el ícono y nombre real de la app de cada botón.
     * En la barra inferior siempre; en el resto solo si elegiste una app a mano.
     */
    private fun pushDock() {
        val all = JSONObject()
        for (k in dockKeys + otherKeys) {
            val o = JSONObject()
            try {
                val pkg = resolvePkg(k)
                val isDock = k.startsWith("dock_")
                val custom = pkg != null && pkg == customFor(k)
                if (pkg != null && (isDock || custom)) {
                    val ai = packageManager.getApplicationInfo(pkg, 0)
                    o.put("label", packageManager.getApplicationLabel(ai).toString())
                    o.put("icon", iconB64(packageManager.getApplicationIcon(ai)))
                    o.put("custom", custom)
                }
            } catch (_: Throwable) {
            }
            all.put(k, o)
        }
        js("setApps($all)")
    }

    private fun resetApp(slot: String) {
        val e = prefs.edit().remove("app_$slot")
        if (slot.startsWith("dock_")) e.remove("app_${base(slot)}")
        e.apply()
        pushDock()
        toast("Restaurado: ${names[base(slot)] ?: slot}")
    }

    /** Cambiar la app de un botón (pulsación larga). Con launchAfter abre la elegida. */
    private fun pickApp(slot: String, launchAfter: Boolean = true) {
        showAppGrid("Elegí la app para ${names[base(slot)] ?: slot}", { resetApp(slot) }) { pkg ->
            prefs.edit().putString("app_$slot", pkg).apply()
            pushDock()
            if (launchAfter) launch(pkg)
        }
    }

    /** Cajón de aplicaciones a pantalla completa (sin título) o selector con título. */
    private fun showDrawer() = showAppGrid(null, null) { pkg -> launch(pkg) }

    private fun showAppGrid(title: String?, onReset: (() -> Unit)?, onPick: (String) -> Unit) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val dlg = Dialog(this, R.style.AppDrawerDialogTheme)
        // Garantiza que el WebView quede en estado normal al cerrar el cajón,
        // tanto con Atrás como tocando fuera del diálogo.
        dlg.setOnDismissListener { js("drawerDone()") }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2101214"))
        }
        if (title != null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(48, 32, 48, 0)
            }
            bar.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 22f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (onReset != null) {
                bar.addView(Button(this).apply {
                    text = "Restaurar original"
                    setOnClickListener { dlg.dismiss(); onReset() }
                })
            }
            bar.addView(Button(this).apply {
                text = "Cancelar"
                setOnClickListener { dlg.dismiss() }
            })
            root.addView(bar)
        }
        val grid = GridView(this)
        grid.numColumns = 7
        grid.verticalSpacing = 28
        grid.setPadding(48, 48, 48, 48)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    compoundDrawablePadding = 10
                }
                val ri = apps[position]
                val icon = ri.loadIcon(pm)
                icon.setBounds(0, 0, 104, 104)
                tv.setCompoundDrawables(null, icon, null, null)
                tv.text = ri.loadLabel(pm)
                return tv
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            onPick(apps[pos].activityInfo.packageName)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg.setContentView(root)
        dlg.show()
    }

    // ---------------------------------------------------------------- Casa / Trabajo

    private fun slotName(slot: String) = if (slot == "home") "Casa" else "Trabajo"

    /** Devuelve true si se abrió la navegación. */
    private fun navigateTo(slot: String): Boolean {
        val addr = prefs.getString("addr_$slot", "").orEmpty()
        if (addr.isBlank()) {
            askAddress(slot)
            return false
        }
        val waze = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=" + Uri.encode(addr) + "&navigate=yes"))
            .setPackage("com.waze")
        if (tryStart(waze)) return true
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(addr)))
            .setPackage("com.google.android.apps.maps")
        if (tryStart(maps)) return true
        toast("Waze no está instalado")
        return false
    }

    private fun askAddress(slot: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(prefs.getString("addr_$slot", ""))
            hint = "Calle y número, ciudad"
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Dirección de ${slotName(slot)}")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("addr_$slot", input.text.toString().trim()).apply()
                navigateTo(slot)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- puente JS -> Kotlin

    inner class Bridge {
        @JavascriptInterface
        fun open(key: String) {
            runOnUiThread { if (!openApp(key)) js("launchDone()") }
        }

        @JavascriptInterface
        fun pick(key: String) {
            runOnUiThread { pickApp(key, false) }
        }

        @JavascriptInterface
        fun dial() {
            runOnUiThread { if (!tryStart(Intent(Intent.ACTION_DIAL))) toast("No hay app de teléfono") }
        }

        @JavascriptInterface
        fun settings() {
            runOnUiThread { tryStart(Intent(Settings.ACTION_SETTINGS)) }
        }

        @JavascriptInterface
        fun drawer() {
            runOnUiThread { showDrawer() }
        }

        @JavascriptInterface
        fun notifAccess() {
            runOnUiThread {
                if (!tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                    toast("Tu equipo no tiene esa pantalla. Activalo desde Ajustes > Apps > Acceso especial")
                }
            }
        }

        @JavascriptInterface
        fun navigate(slot: String) {
            runOnUiThread { if (!navigateTo(slot)) js("launchDone()") }
        }

        @JavascriptInterface
        fun setAddress(slot: String) {
            runOnUiThread { askAddress(slot) }
        }

        @JavascriptInterface
        fun media(action: String) {
            runOnUiThread {
                val c = controller
                if (c == null) {
                    when (action) {
                        "toggle" -> if (freshBMedia() == null) startStereoMusic() else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        "next" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                        "prev" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                    return@runOnUiThread
                }
                val t = c.transportControls
                when (action) {
                    "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                    "next" -> t.skipToNext()
                    "prev" -> t.skipToPrevious()
                }
            }
        }

        /** Radio: "down"/"up" buscan emisora anterior/siguiente; "stepdown"/"stepup" mueven un paso. */
        @JavascriptInterface
        fun radio(action: String) {
            runOnUiThread { radioCommand(action) }
        }

        /** Sintoniza una FM (MHz x100). Devuelve false si la radio no está activa o no está en FM. */
        @JavascriptInterface
        fun radioTune(freqX100: Int): Boolean = this@MainActivity.radioTune(freqX100)

        @JavascriptInterface
        fun tools() {
            runOnUiThread { showTools() }
        }
    }
}