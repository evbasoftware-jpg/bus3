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
import android.media.AudioManager
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
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

  private var textToSpeech: TextToSpeech? = null
  private var isTtsInitialized = false
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
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
          webView?.let { wv ->
            AndroidView(
              factory = { wv },
              modifier = Modifier.fillMaxSize()
            )
          }
        }
      }
    }
  }

  private fun initTextToSpeech() {
    textToSpeech = TextToSpeech(applicationContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        isTtsInitialized = true
        setupTtsVoice("bn")
      }
    }
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
  private fun initWebView() {
    val wv = WebView(this)
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

      var isConfirmedMale = false
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

          // স্কোরিং অ্যালগরিদম দিয়ে খুবই পরিষ্কার ও গম্ভীর পুরুষের কণ্ঠ নির্বাচন
          val scoredVoices = matchingLocaleVoices.map { voice ->
            val name = voice.name.lowercase()
            val baseName = name.removeSuffix("-local").removeSuffix("-network")
            val variant = baseName.substringAfter("-x-", "")
            var score = 0
            var confirmed = false

            // ১. সুস্পষ্ট পুরুষ কণ্ঠের কি-ওয়ার্ড
            if ((name.contains("male") && !name.contains("female")) ||
                name.contains("man") || name.contains("boy") ||
                name.contains("-m-") || name.contains("_m") ||
                name.endsWith("_m") || name.endsWith("-m")) {
              score += 200
              confirmed = true
            }
            if (voice.features.any { it.lowercase().contains("male") && !it.lowercase().contains("female") }) {
              score += 200
              confirmed = true
            }

            // ২. গুগল টিটিএস (Google TTS) ভ্যারিয়েন্ট প্যাটার্ন
            if (variant.isNotEmpty()) {
              val lastChar = variant.last()
              if (lastChar == 'b' || lastChar == 'e') {
                score += 150
                confirmed = true
              } else if (lastChar == 'c') {
                score += 50
              } else if (lastChar == 'f' || lastChar == 'd' || lastChar == 'a') {
                score -= 200
              }
            }

            // ৩. মহিলা কণ্ঠের কি-ওয়ার্ড থাকলে স্কোর কমানো
            if (name.contains("female") || name.contains("woman") || name.contains("girl") ||
                name.contains("-fem") || name.contains("_fem") || name.contains("fem_")) {
              score -= 300
            }

            // ৪. কোয়ালিটি প্রেফারেন্স
            if (voice.quality >= Voice.QUALITY_HIGH) {
              score += 20
            }

            Triple(voice, score, confirmed)
          }.sortedByDescending { it.second }

          val bestCandidate = scoredVoices.firstOrNull()
          if (bestCandidate != null && bestCandidate.second > -100) {
            tts.voice = bestCandidate.first
            isConfirmedMale = bestCandidate.third
          }
        }
      }

      // খুবই পরিষ্কার ও গম্ভীর পুরুষের কণ্ঠের জন্য আদর্শ পিচ ও স্পিচ রেট:
      tts.setPitch(0.96f)
      tts.setSpeechRate(0.93f)
    } catch (_: Exception) {}
  }

  fun speakVoiceAnnouncement(text: String, langCode: String) {
    if (text.isBlank()) return
    runOnUiThread {
      try {
        val tts = textToSpeech
        if (tts != null) {
          setupTtsVoice(langCode)
          val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
          }
          tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "TTS_ANNOUNCE_${System.currentTimeMillis()}")
        }
      } catch (_: Exception) {}
    }
  }

  fun startTrackingService(
    busNumber: String,
    busName: String,
    currentStop: String,
    nextStop: String,
    destInfo: String,
    langCode: String
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
    langCode: String
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
