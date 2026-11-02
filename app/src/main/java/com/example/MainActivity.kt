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

  private var locationManager: LocationManager? = null
  internal var webView: WebView? = null
  private var webViewContainer: android.widget.FrameLayout? = null
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

    VoiceAnnouncementManager.init(this)
    initLocation()
    getOrCreateWebView(this)

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
            factory = { ctx ->
              android.widget.FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT
                )
                val wv = getOrCreateWebView(ctx)
                (wv.parent as? ViewGroup)?.removeView(wv)
                addView(wv)
                webViewContainer = this
              }
            },
            update = { container ->
              val wv = getOrCreateWebView(container.context)
              if (wv.parent != container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.removeAllViews()
                container.addView(wv)
              }
              wv.onResume()
              wv.resumeTimers()
              wv.post {
                wv.requestLayout()
                wv.invalidate()
              }
            },
            modifier = Modifier.fillMaxSize()
          )
        }
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
  fun getOrCreateWebView(context: Context): WebView {
    val existing = webView
    if (existing != null) return existing

    val wv = WebView(context.applicationContext)
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
          val newWv = getOrCreateWebView(this@MainActivity)
          webViewContainer?.let { container ->
            container.removeAllViews()
            container.addView(newWv)
          }
          newWv.loadUrl("file:///android_asset/index.html")
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
      val wv = getOrCreateWebView(this)
      if (wv.url.isNullOrBlank() || wv.url == "about:blank") {
        wv.loadUrl("file:///android_asset/index.html")
      } else {
        wv.onResume()
        wv.resumeTimers()
        wv.post {
          wv.requestLayout()
          wv.invalidate()
          wv.evaluateJavascript("if (typeof onAppResumeState === 'function') { onAppResumeState(); }", null)
        }
      }
      startLocationUpdates()
    } catch (_: Exception) {}
  }

  override fun onPause() {
    super.onPause()
    // ট্র্যাকিং চলাকালীন বা স্ক্রিন অফ থাকাকালীন কখনোই ঘোষণা কিউ মুছবেন না এবং ব্যাকগ্রাউন্ড প্রসেসিং চালু রাখুন
    if (!BusTrackingService.isRunning) {
      try {
        webView?.onPause()
      } catch (_: Exception) {}
    }
  }

  fun speakVoiceAnnouncement(text: String, langCode: String = "bn") {
    VoiceAnnouncementManager.speak(text, langCode)
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

  fun clearSpeechQueueAndStop() {
    VoiceAnnouncementManager.clearQueue()
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      locationManager?.removeUpdates(locationListener)
    } catch (_: Exception) {}

    if (isFinishing && !BusTrackingService.isRunning) {
      VoiceAnnouncementManager.shutdown()
      try {
        webView?.destroy()
        webView = null
      } catch (_: Exception) {}
    }
  }
}

class WebAppInterface(private val activity: MainActivity) {
  @JavascriptInterface
  fun clearSpeechQueue() {
    activity.clearSpeechQueueAndStop()
  }

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

  @JavascriptInterface
  fun assetExists(fileName: String): Boolean {
    return try {
      val stream = activity.assets.open(fileName)
      val len = stream.available()
      stream.close()
      len > 100
    } catch (_: Exception) {
      false
    }
  }

  @JavascriptInterface
  fun clearWebViewCache() {
    activity.runOnUiThread {
      try {
        activity.webView?.clearCache(true)
      } catch (_: Exception) {}
    }
  }
}
