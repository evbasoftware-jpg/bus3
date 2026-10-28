package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import java.net.URLEncoder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

  private var textToSpeech: TextToSpeech? = null
  private var isTtsInitialized = false
  private var audioPlayer: MediaPlayer? = null
  private var locationManager: LocationManager? = null
  private var webView: WebView? = null
  private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
  private var pendingGeolocationOrigin: String? = null

  private val locationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (fineGranted || coarseGranted) {
      pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, true, true)
      pendingGeolocationCallback = null
      pendingGeolocationOrigin = null
      startLocationUpdates()
      webView?.post {
        webView?.evaluateJavascript("if (typeof onLocationPermissionGranted === 'function') { onLocationPermissionGranted(); }", null)
      }
    } else {
      pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, false, false)
      pendingGeolocationCallback = null
      pendingGeolocationOrigin = null
    }
  }

  private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    if (isGranted && BusTrackingService.isRunning) {
      try {
        val intent = Intent(this, BusTrackingService::class.java).apply {
          action = BusTrackingService.ACTION_UPDATE_NOTIFICATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          startForegroundService(intent)
        } else {
          startService(intent)
        }
      } catch (_: Exception) {}
    }
  }

  fun checkAndRequestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ActivityCompat.checkSelfPermission(
          this,
          Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  private val locationListener = object : LocationListener {
    override fun onLocationChanged(location: Location) {
      val speedKmH = if (location.hasSpeed()) (location.speed * 3.6f) else 0f
      webView?.post {
        webView?.evaluateJavascript(
          "if (typeof updateNativeGPS === 'function') { updateNativeGPS(${location.latitude}, ${location.longitude}, ${location.accuracy}, ${speedKmH}); }",
          null
        )
      }
    }
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    initTextToSpeech()
    initLocation()
    initWebView()

    BusTrackingService.onLocationReceived = { location, speedKmH ->
      webView?.post {
        webView?.evaluateJavascript(
          "if (typeof updateNativeGPS === 'function') { updateNativeGPS(${location.latitude}, ${location.longitude}, ${location.accuracy}, ${speedKmH}); }",
          null
        )
      }
    }

    setContent {
      MyApplicationTheme {
        BackHandler(enabled = true) {
          if (webView?.canGoBack() == true) {
            webView?.goBack()
          } else {
            finish()
          }
        }
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .imePadding()
        ) {
          AndroidView(
            factory = { _ ->
              val wv = webView ?: initWebView()
              (wv.parent as? ViewGroup)?.removeView(wv)
              wv
            },
            update = { wv ->
              wv.onResume()
            },
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }
  }

  private fun initTextToSpeech() {
    val googleEnginePackage = "com.google.android.tts"
    textToSpeech = TextToSpeech(applicationContext, { status ->
      if (status == TextToSpeech.SUCCESS) {
        isTtsInitialized = true
        setupTtsVoice("bn")
      } else {
        // Fallback to default engine if Google TTS fails
        textToSpeech = TextToSpeech(applicationContext) { fallbackStatus ->
          if (fallbackStatus == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            setupTtsVoice("bn")
          }
        }
      }
    }, googleEnginePackage)
  }

  private fun initLocation() {
    checkAndRequestNotificationPermission()
    locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val permissionsToRequest = mutableListOf(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (hasLocationPermission()) {
      startLocationUpdates()
    } else {
      locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }
  }

  private fun hasLocationPermission(): Boolean {
    return ActivityCompat.checkSelfPermission(
      this,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
      this,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }

  @SuppressLint("MissingPermission")
  private fun startLocationUpdates() {
    if (!hasLocationPermission()) return
    try {
      val lm = locationManager ?: return
      if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
      }
      if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2f, locationListener)
      }
      val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
      lastKnown?.let { loc ->
        locationListener.onLocationChanged(loc)
      }
    } catch (_: Exception) {}
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun initWebView(): WebView {
    val existing = webView
    if (existing != null) {
      try {
        (existing.parent as? ViewGroup)?.removeView(existing)
        existing.destroy()
      } catch (_: Exception) {}
      webView = null
    }

    val wv = WebView(this)
    wv.setBackgroundColor(android.graphics.Color.WHITE)
    wv.layoutParams = ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT
    )

    with(wv.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      setGeolocationEnabled(true)
      allowFileAccess = true
      allowContentAccess = true
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      cacheMode = WebSettings.LOAD_DEFAULT
      useWideViewPort = true
      loadWithOverviewMode = true
      mediaPlaybackRequiresUserGesture = false
    }

    wv.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

    wv.webChromeClient = object : WebChromeClient() {
      override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
      ) {
        if (hasLocationPermission()) {
          callback?.invoke(origin, true, true)
        } else {
          pendingGeolocationCallback = callback
          pendingGeolocationOrigin = origin
          locationPermissionLauncher.launch(
            arrayOf(
              Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.ACCESS_COARSE_LOCATION
            )
          )
        }
      }

      override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
      ): Boolean {
        AlertDialog.Builder(this@MainActivity)
          .setMessage(message)
          .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
          .setOnCancelListener { result?.cancel() }
          .create()
          .show()
        return true
      }

      override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
      ): Boolean {
        AlertDialog.Builder(this@MainActivity)
          .setMessage(message)
          .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
          .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
          .setOnCancelListener { result?.cancel() }
          .create()
          .show()
        return true
      }

      override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
      ): Boolean {
        val input = EditText(this@MainActivity).apply {
          setText(defaultValue)
        }
        AlertDialog.Builder(this@MainActivity)
          .setMessage(message)
          .setView(input)
          .setPositiveButton(android.R.string.ok) { _, _ ->
            result?.confirm(input.text.toString())
          }
          .setNegativeButton(android.R.string.cancel) { _, _ ->
            result?.cancel()
          }
          .setOnCancelListener { result?.cancel() }
          .create()
          .show()
        return true
      }
    }

    wv.webViewClient = object : WebViewClient() {
      override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        try {
          (view?.parent as? ViewGroup)?.removeView(view)
          view?.destroy()
        } catch (_: Exception) {}
        webView = null
        runOnUiThread {
          initWebView()
        }
        return true
      }

      override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleCustomUri(url)
      }

      @Deprecated("Deprecated in Java")
      override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        return handleCustomUri(url)
      }

      private fun handleCustomUri(url: String): Boolean {
        if (url.startsWith("whatsapp:") || url.startsWith("intent:") || url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("https://api.whatsapp.com/")) {
          try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
          } catch (_: Exception) {}
          return true
        }
        return false
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (hasLocationPermission()) {
          view?.evaluateJavascript("if (typeof onLocationPermissionGranted === 'function') { onLocationPermissionGranted(); }", null)
        }
      }
    }

    wv.loadUrl("file:///android_asset/index.html")
    this.webView = wv
    return wv
  }

  override fun onResume() {
    super.onResume()
    try {
      webView?.onResume()
      webView?.resumeTimers()
      startLocationUpdates()
    } catch (_: Exception) {}
  }

  override fun onPause() {
    super.onPause()
    try {
      webView?.onPause()
    } catch (_: Exception) {}
  }

  private fun getLocaleForLanguage(langCode: String): Locale {
    return when (langCode.lowercase()) {
      "bn" -> Locale("bn", "IN")
      "hi" -> Locale("hi", "IN")
      "en" -> Locale("en", "IN")
      "ta" -> Locale("ta", "IN")
      "te" -> Locale("te", "IN")
      "mr" -> Locale("mr", "IN")
      "gu" -> Locale("gu", "IN")
      "kn" -> Locale("kn", "IN")
      "ml" -> Locale("ml", "IN")
      "pa" -> Locale("pa", "IN")
      "or" -> Locale("or", "IN")
      else -> Locale("bn", "IN")
    }
  }

  private fun isNetworkAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun setupTtsVoice(langCode: String) {
    val tts = textToSpeech ?: return
    val targetLocale = getLocaleForLanguage(langCode)
    try {
      val langResult = tts.setLanguage(targetLocale)
      if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        if (tts.setLanguage(Locale("bn", "IN")) < TextToSpeech.LANG_AVAILABLE) {
          tts.setLanguage(Locale.ENGLISH)
        }
      }

      var isFemaleSelected = false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val availableVoices = tts.voices
        if (!availableVoices.isNullOrEmpty()) {
          val matchingLocaleVoices = availableVoices.filter {
            it.locale.language.equals(targetLocale.language, ignoreCase = true)
          }.ifEmpty {
            availableVoices.filter {
              it.locale.language.equals("bn", ignoreCase = true)
            }
          }.ifEmpty {
            availableVoices.toList()
          }

          fun isExplicitlyFemale(v: Voice): Boolean {
            val n = v.name.lowercase()
            if (n.contains("female") || n.contains("woman") || n.contains("girl") ||
                n.contains("bdf") || n.contains("bnf") || n.contains("bif") || n.contains("baf") ||
                n.contains("hif") || n.contains("hid") || n.contains("hic") ||
                n.contains("-fem") || n.contains("_fem") || n.contains("fem_") ||
                n.contains("swara") || n.contains("aditi") || n.contains("kavya") ||
                n.contains("lekha") || n.contains("sangeeta") || n.contains("priya") ||
                n.contains("zira")) return true
            return v.features.any {
              val fl = it.lowercase()
              fl.contains("female") || fl.contains("gender=female")
            }
          }

          fun isExplicitlyMale(v: Voice): Boolean {
            val n = v.name.lowercase()
            if ((n.contains("male") && !n.contains("female")) ||
                n.contains("man") || n.contains("boy") ||
                n.contains("-bin-") || n.contains("-ban-") || n.contains("bin-") || n.contains("ban-") ||
                n.endsWith("-bin") || n.endsWith("-ban") || n.endsWith("_bin") || n.endsWith("_ban") ||
                n.contains("hin-") || n.contains("-hin-") ||
                n.contains("-m-") || n.contains("_m") || n.endsWith("-m") || n.endsWith("_m")) return true
            return v.features.any {
              val fl = it.lowercase()
              fl.contains("gender=male") || fl == "male"
            }
          }

          // ১. প্রথম পছন্দ: সুস্পষ্ট নারী কণ্ঠ যা পুরুষ নয়
          val femaleVoices = matchingLocaleVoices.filter { isExplicitlyFemale(it) && !isExplicitlyMale(it) }
          val selectedVoice = if (femaleVoices.isNotEmpty()) {
            femaleVoices.sortedWith(
              compareByDescending<Voice> { it.isNetworkConnectionRequired || it.name.contains("network") }
                .thenByDescending { it.quality }
            ).firstOrNull()
          } else {
            // ২. দ্বিতীয় পছন্দ: যে ভয়েসে কোনো পুরুষ নির্দেশক নেই
            matchingLocaleVoices.filter { !isExplicitlyMale(it) }
              .sortedByDescending { it.quality }
              .firstOrNull()
          }

          if (selectedVoice != null) {
            tts.voice = selectedVoice
            isFemaleSelected = isExplicitlyFemale(selectedVoice)
          }
        }
      }

      // মিষ্টি নারী কণ্ঠ পাওয়া গেলে মধুর পিচ ও রেট, অন্যথায় সাধারণ স্বাভাবিক পিচ যাতে রোবটিক পুরুষ না শোনায়
      if (isFemaleSelected) {
        tts.setPitch(1.18f)
        tts.setSpeechRate(0.92f)
      } else {
        tts.setPitch(1.02f)
        tts.setSpeechRate(0.95f)
      }
    } catch (_: Exception) {}
  }

  private fun formatTextForTts(text: String): String {
    var out = text
    // BDO OFFICE এবং অন্যান্য পরিচিত সংক্ষিপ্ত রূপ ও স্টপের সঠিক বাংলা উচ্চারণ
    out = out.replace(Regex("(?i)\\bBDO\\s+OFFICE\\b"), "বিডিও অফিস")
    out = out.replace(Regex("(?i)\\bBDO\\b"), "বিডিও")
    out = out.replace(Regex("(?i)\\bOFFICE\\b"), "অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\s+OFFICE\\b"), "এসডিও অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\b"), "এসডিও")
    out = out.replace(Regex("(?i)\\bAHMADPUR\\b"), "আহমদপুর")
    out = out.replace(Regex("(?i)\\bAHMEDPUR\\b"), "আহমদপুর")

    // Convert remaining ALL-CAPS Latin words to Title Case (e.g., SUNDIPUR -> Sundipur)
    val regex = Regex("\\b[A-Z]{2,}\\b")
    return regex.replace(out) { matchResult ->
      val word = matchResult.value
      word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
  }

  private val nativeSpeechQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
  private var isNativeAnnouncing = false

  fun speakVoiceAnnouncement(text: String, langCode: String) {
    if (text.isBlank()) return
    val preparedText = formatTextForTts(text)
    val curLang = if (langCode.isBlank()) "bn" else langCode

    runOnUiThread {
      nativeSpeechQueue.add(Pair(preparedText, curLang))
      processNextNativeSpeech()
    }
  }

  private fun processNextNativeSpeech() {
    if (isNativeAnnouncing || nativeSpeechQueue.isEmpty()) return
    val item = nativeSpeechQueue.poll() ?: return
    isNativeAnnouncing = true
    val preparedText = item.first
    val curLang = item.second

    val onDoneCallback: () -> Unit = {
      runOnUiThread {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
          isNativeAnnouncing = false
          processNextNativeSpeech()
        }, 700L)
      }
    }

    if (isNetworkAvailable() && preparedText.length < 250) {
      playGoogleOnlineFemaleVoice(preparedText, curLang) { success ->
        if (!success) {
          speakViaNativeTts(preparedText, curLang, onDoneCallback)
        } else {
          onDoneCallback()
        }
      }
      return
    }

    speakViaNativeTts(preparedText, curLang, onDoneCallback)
  }

  private fun playGoogleOnlineFemaleVoice(text: String, langCode: String, onComplete: (Boolean) -> Unit) {
    try {
      audioPlayer?.stop()
      audioPlayer?.release()
      audioPlayer = null
    } catch (_: Exception) {}

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$langCode&q=$encodedText"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty(
          "User-Agent",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        conn.setRequestProperty("Referer", "https://translate.google.com/")
        conn.connectTimeout = 4000
        conn.readTimeout = 4000

        if (conn.responseCode == 200) {
          val tempFile = File.createTempFile("tts_female_", ".mp3", cacheDir)
          conn.inputStream.use { input ->
            tempFile.outputStream().use { output ->
              input.copyTo(output)
            }
          }

          withContext(Dispatchers.Main) {
            try {
              val player = MediaPlayer()
              audioPlayer = player
              player.setAudioAttributes(
                AudioAttributes.Builder()
                  .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                  .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                  .build()
              )
              player.setDataSource(tempFile.absolutePath)
              player.setOnCompletionListener { mp ->
                try { mp.release() } catch (_: Exception) {}
                if (audioPlayer == mp) audioPlayer = null
                tempFile.delete()
                onComplete(true)
              }
              player.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (_: Exception) {}
                if (audioPlayer == mp) audioPlayer = null
                tempFile.delete()
                onComplete(false)
                true
              }
              player.prepare()
              player.start()
            } catch (_: Exception) {
              tempFile.delete()
              onComplete(false)
            }
          }
        } else {
          withContext(Dispatchers.Main) {
            onComplete(false)
          }
        }
      } catch (_: Exception) {
        withContext(Dispatchers.Main) {
          onComplete(false)
        }
      }
    }
  }

  private fun speakViaNativeTts(preparedText: String, langCode: String, onDone: () -> Unit) {
    try {
      val tts = textToSpeech ?: run {
        onDone()
        return
      }
      setupTtsVoice(langCode)
      val params = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
      }
      val utteranceId = "TTS_ANNOUNCE_${System.currentTimeMillis()}"
      tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
          onDone()
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
          onDone()
        }
      })
      tts.speak(preparedText, TextToSpeech.QUEUE_ADD, params, utteranceId)
    } catch (_: Exception) {
      onDone()
    }
  }

  fun startTrackingService(
    busNumber: String,
    busName: String,
    currentStop: String,
    nextStop: String,
    destInfo: String,
    langCode: String,
    nextName: String = "",
    nextLat: Double = 0.0,
    nextLon: Double = 0.0,
    destName: String = "",
    destLat: Double = 0.0,
    destLon: Double = 0.0
  ) {
    checkAndRequestNotificationPermission()
    try {
      val intent = Intent(this, BusTrackingService::class.java).apply {
        action = BusTrackingService.ACTION_START_TRACKING
        putExtra(BusTrackingService.EXTRA_BUS_NUMBER, busNumber)
        putExtra(BusTrackingService.EXTRA_BUS_NAME, busName)
        putExtra(BusTrackingService.EXTRA_CURRENT_STOP, currentStop)
        putExtra(BusTrackingService.EXTRA_NEXT_STOP, nextStop)
        putExtra(BusTrackingService.EXTRA_DEST_DIST_ETA, destInfo)
        putExtra(BusTrackingService.EXTRA_LANG_CODE, langCode)
        if (nextName.isNotBlank()) putExtra(BusTrackingService.EXTRA_NEXT_NAME, nextName)
        if (nextLat != 0.0) putExtra(BusTrackingService.EXTRA_NEXT_LAT, nextLat)
        if (nextLon != 0.0) putExtra(BusTrackingService.EXTRA_NEXT_LON, nextLon)
        if (destName.isNotBlank()) putExtra(BusTrackingService.EXTRA_DEST_NAME, destName)
        if (destLat != 0.0) putExtra(BusTrackingService.EXTRA_DEST_LAT, destLat)
        if (destLon != 0.0) putExtra(BusTrackingService.EXTRA_DEST_LON, destLon)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
      } else {
        startService(intent)
      }
    } catch (_: Exception) {}
  }

  fun updateTrackingNotification(
    busNumber: String,
    busName: String,
    currentStop: String,
    nextStop: String,
    destInfo: String,
    langCode: String,
    nextName: String = "",
    nextLat: Double = 0.0,
    nextLon: Double = 0.0,
    destName: String = "",
    destLat: Double = 0.0,
    destLon: Double = 0.0
  ) {
    checkAndRequestNotificationPermission()
    try {
      val intent = Intent(this, BusTrackingService::class.java).apply {
        action = BusTrackingService.ACTION_UPDATE_NOTIFICATION
        putExtra(BusTrackingService.EXTRA_BUS_NUMBER, busNumber)
        putExtra(BusTrackingService.EXTRA_BUS_NAME, busName)
        putExtra(BusTrackingService.EXTRA_CURRENT_STOP, currentStop)
        putExtra(BusTrackingService.EXTRA_NEXT_STOP, nextStop)
        putExtra(BusTrackingService.EXTRA_DEST_DIST_ETA, destInfo)
        putExtra(BusTrackingService.EXTRA_LANG_CODE, langCode)
        if (nextName.isNotBlank()) putExtra(BusTrackingService.EXTRA_NEXT_NAME, nextName)
        if (nextLat != 0.0) putExtra(BusTrackingService.EXTRA_NEXT_LAT, nextLat)
        if (nextLon != 0.0) putExtra(BusTrackingService.EXTRA_NEXT_LON, nextLon)
        if (destName.isNotBlank()) putExtra(BusTrackingService.EXTRA_DEST_NAME, destName)
        if (destLat != 0.0) putExtra(BusTrackingService.EXTRA_DEST_LAT, destLat)
        if (destLon != 0.0) putExtra(BusTrackingService.EXTRA_DEST_LON, destLon)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
      } else {
        startService(intent)
      }
    } catch (_: Exception) {}
  }

  fun stopTrackingService() {
    try {
      val intent = Intent(this, BusTrackingService::class.java).apply {
        action = BusTrackingService.ACTION_STOP_TRACKING
      }
      startService(intent)
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      audioPlayer?.stop()
      audioPlayer?.release()
      audioPlayer = null
    } catch (_: Exception) {}
    try {
      textToSpeech?.stop()
      textToSpeech?.shutdown()
    } catch (_: Exception) {}
    try {
      locationManager?.removeUpdates(locationListener)
    } catch (_: Exception) {}
    try {
      webView?.destroy()
    } catch (_: Exception) {}
  }
}

class WebAppInterface(private val activity: MainActivity) {
  @JavascriptInterface
  fun speakAnnouncement(text: String, langCode: String) {
    activity.speakVoiceAnnouncement(text, langCode)
  }

  @JavascriptInterface
  fun setNotificationLanguage(lang: String) {
    // সংগৃহীত ভাষা অ্যাপ সেটিংসে আপডেট
  }

  @JavascriptInterface
  fun startBackgroundTrackingWithDetails(
    busNumber: String,
    busName: String,
    currentStop: String,
    nextStop: String,
    destInfo: String,
    langCode: String
  ) {
    activity.startTrackingService(busNumber, busName, currentStop, nextStop, destInfo, langCode)
  }

  @JavascriptInterface
  fun onBusSelected(busNumber: String, busName: String) {
    activity.startTrackingService(busNumber, busName, "--", "--", "--", "bn")
  }

  @JavascriptInterface
  fun updateNotificationDetails(
    busNumber: String,
    busName: String,
    currentStop: String,
    nextStop: String,
    destInfo: String,
    langCode: String
  ) {
    activity.updateTrackingNotification(busNumber, busName, currentStop, nextStop, destInfo, langCode)
  }

  @JavascriptInterface
  fun updateNotificationWithCoords(
    busNumber: String,
    busName: String,
    currentStop: String,
    nextStop: String,
    destInfo: String,
    langCode: String,
    nextName: String,
    nextLat: Double,
    nextLon: Double,
    destName: String,
    destLat: Double,
    destLon: Double
  ) {
    activity.updateTrackingNotification(
      busNumber, busName, currentStop, nextStop, destInfo, langCode,
      nextName, nextLat, nextLon, destName, destLat, destLon
    )
  }

  @JavascriptInterface
  fun stopBackgroundTracking() {
    activity.stopTrackingService()
  }

  @JavascriptInterface
  fun isTrackingRunning(): Boolean {
    return BusTrackingService.isRunning
  }

  @JavascriptInterface
  fun setStaffMode(enabled: Boolean) {
    BusTrackingService.isStaffMode = enabled
  }

  @JavascriptInterface
  fun isStaffMode(): Boolean {
    return BusTrackingService.isStaffMode
  }

  @JavascriptInterface
  fun shareWhatsApp(text: String) {
    activity.runOnUiThread {
      try {
        val intent = Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          setPackage("com.whatsapp")
          putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(intent)
      } catch (_: Exception) {
        shareText(text)
      }
    }
  }

  @JavascriptInterface
  fun shareText(text: String) {
    activity.runOnUiThread {
      try {
        val intent = Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "বাস লাইভ ট্র্যাকিং শেয়ার করুন")
        activity.startActivity(chooser)
      } catch (_: Exception) {}
    }
  }
}
