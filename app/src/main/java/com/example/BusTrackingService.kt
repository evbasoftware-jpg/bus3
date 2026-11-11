package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BusTrackingService : Service() {

  companion object {
    const val ACTION_START_TRACKING = "com.example.action.START_TRACKING"
    const val ACTION_STOP_TRACKING = "com.example.action.STOP_TRACKING"
    const val ACTION_UPDATE_NOTIFICATION = "com.example.action.UPDATE_NOTIFICATION"

    const val EXTRA_BUS_NUMBER = "extra_bus_number"
    const val EXTRA_BUS_NAME = "extra_bus_name"
    const val EXTRA_CURRENT_STOP = "extra_current_stop"
    const val EXTRA_NEXT_STOP = "extra_next_stop"
    const val EXTRA_DEST_DIST_ETA = "extra_dest_dist_eta"
    const val EXTRA_LANG_CODE = "extra_lang_code"

    const val EXTRA_DEST_NAME = "extra_dest_name"
    const val EXTRA_DEST_LAT = "extra_dest_lat"
    const val EXTRA_DEST_LON = "extra_dest_lon"
    const val EXTRA_NEXT_NAME = "extra_next_name"
    const val EXTRA_NEXT_LAT = "extra_next_lat"
    const val EXTRA_NEXT_LON = "extra_next_lon"

    const val CHANNEL_ID = "bus_live_tracking_channel"
    const val NOTIFICATION_ID = 1001

    private const val API_URL =
      "https://script.google.com/macros/s/AKfycbxBgPQeiK8LQryKhtlBOfw_2V-pStqQa3KVR-gSuUg4kPkuXwbb1sEE5Pc7EeYw1LU9bA/exec"

    var isRunning = false
      private set
    var activeBusNumber: String = ""
      private set
    var activeBusName: String = ""
      private set

    // Callback to update active UI if app is in foreground
    var onLocationReceived: ((Location, Float) -> Unit)? = null

    // বাসের চালক বা কন্ডাক্টরের সিকিউরিটি মোড (শুধুমাত্র অনুমোদিত স্টাফ সক্রিয় করলেই সার্ভারে বাস লোকেশন আপলোড হবে)
    var isStaffMode: Boolean = false

    // ট্র্যাকিং বন্ধ হলে অ্যাপের ইন্টারফেস ও ওয়েবভিউকে অবহিত করার কলব্যাক
    var onTrackingStopped: (() -> Unit)? = null

    data class RouteStop(
      val name: String,
      val lat: Double,
      val lon: Double,
      val index: Int
    )

    var activeRouteStops: List<RouteStop> = emptyList()

    fun setFullRouteStops(stopsJson: String) {
      try {
        val jsonArray = org.json.JSONArray(stopsJson)
        val list = mutableListOf<RouteStop>()
        for (i in 0 until jsonArray.length()) {
          val obj = jsonArray.getJSONObject(i)
          val name = obj.optString("name", obj.optString("stopName", "")).trim()
          val lat = obj.optDouble("lat", obj.optDouble("latitude", 0.0))
          val lon = obj.optDouble("lon", obj.optDouble("longitude", 0.0))
          if (name.isNotBlank() && lat != 0.0 && lon != 0.0) {
            list.add(RouteStop(name, lat, lon, i))
          }
        }
        activeRouteStops = list
      } catch (_: Exception) {}
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var locationManager: LocationManager? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  private var lastServerSyncTimestamp = 0L
  private var lastSpeedKmh: Float = 0f
  private var currentStopText: String = "--"
  private var nextStopText: String = "--"
  private var destDistEtaText: String = "--"
  private var currentLangCode: String = "bn"

  private var destName: String = ""
  private var destLat: Double = 0.0
  private var destLon: Double = 0.0
  private var nextName: String = ""
  private var nextLat: Double = 0.0
  private var nextLon: Double = 0.0

  private var lastAnnouncedServiceCurrent: String = ""
  private var lastAnnouncedServiceNext: String = ""
  private var lastAnnouncedServiceDest: String = ""
  private var lastAnnouncementTime: Long = 0L
  private val serviceHandler = Handler(Looper.getMainLooper())
  private var nextAnnouncementRunnable: Runnable? = null

  private fun getCleanStopBaseName(s: String): String {
    if (s.isBlank() || s == "--") return ""
    return s.substringBefore("(")
      .substringBefore("|")
      .replace(Regex("[🚏🚌📍🏁]"), "")
      .trim()
  }

  private val locationListener = object : LocationListener {
    override fun onLocationChanged(location: Location) {
      val speedKmh = if (location.hasSpeed()) (location.speed * 3.6f) else 0f
      handleNewLocation(location, speedKmh)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
  }

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    VoiceAnnouncementManager.init(this)
    locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
    wakeLock = powerManager?.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "BusTracker::BackgroundLocationWakeLock"
    )?.apply {
      setReferenceCounted(false)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) return START_NOT_STICKY

    val action = intent.action
    if (action == ACTION_STOP_TRACKING) {
      stopTracking()
      return START_NOT_STICKY
    }

    if (action == ACTION_UPDATE_NOTIFICATION) {
      if (!isRunning) {
        // ট্র্যাকিং যখন বন্ধ, তখন কোনো আপডেট নোটিফিকেশন সার্ভিস পুনরায় চালু করতে পারবে না
        return START_NOT_STICKY
      }
    }

    if (action == ACTION_UPDATE_NOTIFICATION || action == ACTION_START_TRACKING) {
      val busNumber = intent.getStringExtra(EXTRA_BUS_NUMBER) ?: ""
      val busName = intent.getStringExtra(EXTRA_BUS_NAME) ?: ""
      val curStop = intent.getStringExtra(EXTRA_CURRENT_STOP) ?: ""
      val nextStop = intent.getStringExtra(EXTRA_NEXT_STOP) ?: ""
      val destInfo = intent.getStringExtra(EXTRA_DEST_DIST_ETA) ?: ""
      val lang = intent.getStringExtra(EXTRA_LANG_CODE) ?: ""

      val dName = intent.getStringExtra(EXTRA_DEST_NAME) ?: ""
      val dLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
      val dLon = intent.getDoubleExtra(EXTRA_DEST_LON, 0.0)
      val nName = intent.getStringExtra(EXTRA_NEXT_NAME) ?: ""
      val nLat = intent.getDoubleExtra(EXTRA_NEXT_LAT, 0.0)
      val nLon = intent.getDoubleExtra(EXTRA_NEXT_LON, 0.0)

      if (busNumber.isNotBlank()) activeBusNumber = busNumber
      if (busName.isNotBlank()) activeBusName = busName
      if (curStop.isNotBlank()) currentStopText = curStop
      if (nextStop.isNotBlank()) {
        val cleanCur = getCleanStopBaseName(curStop.ifBlank { currentStopText })
        val cleanNext = getCleanStopBaseName(nextStop)
        if (cleanNext.isNotBlank() && !cleanNext.equals(cleanCur, ignoreCase = true)) {
          nextStopText = nextStop
        }
      }
      if (destInfo.isNotBlank()) destDistEtaText = destInfo
      if (lang.isNotBlank()) currentLangCode = lang

      if (dName.isNotBlank()) destName = dName
      if (dLat != 0.0) destLat = dLat
      if (dLon != 0.0) destLon = dLon
      if (nName.isNotBlank()) {
        val cleanCur = getCleanStopBaseName(curStop.ifBlank { currentStopText })
        val cleanNextName = getCleanStopBaseName(nName)
        if (cleanNextName.isNotBlank() && !cleanNextName.equals(cleanCur, ignoreCase = true)) {
          nextName = nName
        }
      }
      if (nLat != 0.0) nextLat = nLat
      if (nLon != 0.0) nextLon = nLon

      if (!isRunning) {
        isRunning = true
        startForegroundServiceInternal()
        startLocationUpdates()
        startBackgroundPollingLoop()
      } else {
        refreshNotification()
      }
      return START_STICKY
    }

    return START_STICKY
  }

  private fun startForegroundServiceInternal() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    try {
      wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
    } catch (_: Exception) {}
  }

  @SuppressLint("MissingPermission")
  private fun startLocationUpdates() {
    try {
      locationManager?.let { lm ->
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
          lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            2000L,
            0f,
            locationListener,
            Looper.getMainLooper()
          )
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
          lm.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            3000L,
            0f,
            locationListener,
            Looper.getMainLooper()
          )
        }
      }
    } catch (_: SecurityException) {}
  }

  private fun updateDynamicDistancesFromGps(location: Location, speedKmh: Float) {
    val effSpeed = if (speedKmh > 5f) speedKmh else (if (lastSpeedKmh > 5f) lastSpeedKmh else 28f)

    if (activeRouteStops.isNotEmpty()) {
      var minDistance = Float.MAX_VALUE
      var closestIndex = 0
      for (i in activeRouteStops.indices) {
        val st = activeRouteStops[i]
        val stLoc = Location("").apply {
          latitude = st.lat
          longitude = st.lon
        }
        val dist = location.distanceTo(stLoc)
        if (dist < minDistance) {
          minDistance = dist
          closestIndex = i
        }
      }

      val closestStop = activeRouteStops[closestIndex]
      val cleanClosest = getCleanStopBaseName(closestStop.name)
      val cleanCur = getCleanStopBaseName(currentStopText)

      if (minDistance < 60f) {
        currentStopText = closestStop.name
      } else if (currentStopText == "--" || cleanCur.isBlank()) {
        currentStopText = closestStop.name
      }

      // পরবর্তী স্টপেজ নির্ধারণ (বর্তমান স্টপ ও পরবর্তী স্টপ যেন কখনোই এক না হয়)
      val curIdx = activeRouteStops.indexOfFirst {
        getCleanStopBaseName(it.name).equals(getCleanStopBaseName(currentStopText), ignoreCase = true)
      }
      val nextIdx = if (curIdx >= 0) {
        curIdx + 1
      } else {
        if (minDistance < 60f) closestIndex + 1 else {
          if (cleanClosest.equals(cleanCur, ignoreCase = true)) closestIndex + 1 else closestIndex
        }
      }

      if (nextIdx in activeRouteStops.indices) {
        val nextStop = activeRouteStops[nextIdx]
        nextName = nextStop.name
        nextLat = nextStop.lat
        nextLon = nextStop.lon

        val nextLoc = Location("").apply {
          latitude = nextStop.lat
          longitude = nextStop.lon
        }
        val distToNextM = location.distanceTo(nextLoc)
        val nextKm = distToNextM / 1000f
        val hours = nextKm / effSpeed
        val mins = Math.round(hours * 60)
        val nextEtaStr = if (distToNextM < 60f) {
          when (currentLangCode.lowercase()) {
            "en" -> "Arrived"
            "hi" -> "पहुँच गया"
            else -> "পৌঁছে গেছে"
          }
        } else if (mins < 1) {
          "< 1 MIN"
        } else {
          "$mins MINS"
        }
        val distStr = if (nextKm < 1f) {
          "${distToNextM.toInt()} M"
        } else {
          String.format(Locale.US, "%.1f KM (%d M)", nextKm, distToNextM.toInt())
        }
        nextStopText = "${nextStop.name} ($distStr • ETA: $nextEtaStr)"
      } else {
        val finalLabel = if (destName.isNotBlank()) destName.uppercase() else {
          when (currentLangCode.lowercase()) {
            "en" -> "Final Stop"
            "hi" -> "अंतिम स्टॉप"
            else -> "গন্তব্যের শেষ স্টপেজ"
          }
        }
        nextStopText = finalLabel
        nextName = finalLabel
      }

      // গন্তব্য দূরত্ব ও ইটিএ হিসাব
      val destStop = activeRouteStops.last()
      val targetDestName = if (destName.isNotBlank()) destName else destStop.name
      val targetDestLat = if (destLat != 0.0) destLat else destStop.lat
      val targetDestLon = if (destLon != 0.0) destLon else destStop.lon

      val finalLoc = Location("").apply {
        latitude = targetDestLat
        longitude = targetDestLon
      }
      val directMeters = location.distanceTo(finalLoc)
      val roadDistKm = (directMeters * 1.12f) / 1000f
      val totalMeters = (roadDistKm * 1000).toInt()
      val hours = roadDistKm / effSpeed
      val mins = Math.round(hours * 60)
      val etaStr = if (directMeters < 60f) {
        when (currentLangCode.lowercase()) {
          "en" -> "Arrived"
          "hi" -> "पहुँच गया"
          else -> "পৌঁছে গেছে"
        }
      } else if (mins < 1) {
        "< 1 MIN"
      } else if (mins < 60) {
        "$mins MINS"
      } else {
        "${mins / 60} HR ${mins % 60} MINS"
      }
      val distStr = if (roadDistKm < 1f) {
        "${totalMeters} M"
      } else {
        String.format(Locale.US, "%.1f KM (%d M)", roadDistKm, totalMeters)
      }
      val targetLabel = targetDestName.uppercase()
      destDistEtaText = "$targetLabel ($distStr • ETA: $etaStr)"

      checkBackgroundScreenOffAnnouncements(directMeters)
      return
    }

    var directDestM = 0f
    if (destLat != 0.0 && destLon != 0.0) {
      val destLoc = Location("").apply {
        latitude = destLat
        longitude = destLon
      }
      directDestM = location.distanceTo(destLoc)
      val roadDistKm = (directDestM * 1.12f) / 1000f
      val totalMeters = (roadDistKm * 1000).toInt()
      val hours = roadDistKm / effSpeed
      val mins = Math.round(hours * 60)

      val etaStr = if (directDestM < 50f) {
        when (currentLangCode.lowercase()) {
          "en" -> "Arrived"
          "hi" -> "पहुँच गया"
          else -> "পৌঁছে গেছে"
        }
      } else if (mins < 1) {
        "< 1 MIN"
      } else if (mins < 60) {
        "$mins MINS"
      } else {
        "${mins / 60} HR ${mins % 60} MINS"
      }

      val distStr = if (roadDistKm < 1f) {
        "${totalMeters} M"
      } else {
        String.format(Locale.US, "%.1f KM (%d M)", roadDistKm, totalMeters)
      }

      val targetLabel = if (destName.isNotBlank()) destName.uppercase() else "DEST"
      destDistEtaText = "$targetLabel ($distStr • ETA: $etaStr)"
    }

    if (nextLat != 0.0 && nextLon != 0.0) {
      val nextLoc = Location("").apply {
        latitude = nextLat
        longitude = nextLon
      }
      val directMeters = location.distanceTo(nextLoc)
      val nextKm = directMeters / 1000f
      val hours = nextKm / effSpeed
      val mins = Math.round(hours * 60)

      val nextEtaStr = if (directMeters < 50f) {
        when (currentLangCode.lowercase()) {
          "en" -> "Arrived"
          "hi" -> "पहुँच गया"
          else -> "পৌঁছে গেছে"
        }
      } else if (mins < 1) {
        "< 1 MIN"
      } else {
        "$mins MINS"
      }

      val distStr = if (nextKm < 1f) {
        "${directMeters.toInt()} M"
      } else {
        String.format(Locale.US, "%.1f KM (%d M)", nextKm, directMeters.toInt())
      }

      val targetNext = if (nextName.isNotBlank()) nextName else "NEXT"
      nextStopText = "$targetNext ($distStr • ETA: $nextEtaStr)"
    }

    checkBackgroundScreenOffAnnouncements(directDestM)
  }

  private fun checkBackgroundScreenOffAnnouncements(destMeters: Float) {
    // স্ক্রিন অন থাকলে অ্যাপের স্ক্রিন (UI) নিজেই স্টপের সঠিক নাম একবার ঘোষণা করবে।
    // সার্ভিস শুধুমাত্র স্ক্রিন অফ বা অ্যাপ মিনিমাইজ থাকলে (!MainActivity.isActivityResumed) ঘোষণা করবে।
    if (MainActivity.isActivityResumed) return

    val cleanCur = getCleanStopBaseName(currentStopText)
    val cleanNext = getCleanStopBaseName(nextName.ifBlank { nextStopText })
    val cleanDest = getCleanStopBaseName(destName)

    val spokenCur = VoiceAnnouncementManager.toBengaliSpokenStopName(cleanCur)
    val spokenNext = VoiceAnnouncementManager.toBengaliSpokenStopName(cleanNext)
    val spokenDest = VoiceAnnouncementManager.toBengaliSpokenStopName(cleanDest)

    val now = System.currentTimeMillis()

    // ১. গন্তব্যে পৌঁছানো ঘোষণা (অ্যাপ ওপেন থাকার সময়ের মতো হুবহু একই বাক্য)
    val isDestinationReached = (destMeters in 1f..80f) || (cleanDest.isNotBlank() && cleanCur.equals(cleanDest, ignoreCase = true))
    if (isDestinationReached && cleanDest.isNotBlank() && cleanDest != lastAnnouncedServiceDest) {
      lastAnnouncedServiceDest = cleanDest
      lastAnnouncementTime = now
      val destSpeech = when (currentLangCode.lowercase()) {
        "hi" -> "हम गंतव्य $spokenDest पहुँच चुके हैं। यात्रा के लिए धन्यवाद!"
        "en" -> "We have arrived at destination $cleanDest. Thank you for traveling with us!"
        else -> "আমরা গন্তব্য $spokenDest-এ পৌঁছে গেছি। আমাদের সাথে ভ্রমণের জন্য ধন্যবাদ!"
      }
      VoiceAnnouncementManager.speak(destSpeech, currentLangCode)
      return
    }

    // ২. বর্তমান স্টপেজ ঘোষণা (অ্যাপ ওপেন থাকার সময়ের মতো হুবহু একই বাক্য)
    if (cleanCur.isNotBlank() && cleanCur != "--" && cleanCur != lastAnnouncedServiceCurrent) {
      lastAnnouncedServiceCurrent = cleanCur
      lastAnnouncementTime = now
      val curSpeech = when (currentLangCode.lowercase()) {
        "hi" -> "वर्तमान स्टॉप $spokenCur।"
        "en" -> "Current stop $cleanCur."
        else -> "বর্তমান স্টপেজ $spokenCur।"
      }
      VoiceAnnouncementManager.speak(curSpeech, currentLangCode)

      // পরবর্তী স্টপের ঘোষণা ৩.২ সেকেন্ড পর আলাদাভাবে বাজবে (ঠিক যেমন অ্যাপ ওপেন থাকলে বাজে)
      if (cleanNext.isNotBlank() && cleanNext != "--" && !cleanNext.equals(cleanCur, ignoreCase = true)) {
        lastAnnouncedServiceNext = cleanNext
        nextAnnouncementRunnable?.let { serviceHandler.removeCallbacks(it) }
        val r = Runnable {
          if (!MainActivity.isActivityResumed) {
            val nextSpeech = when (currentLangCode.lowercase()) {
              "hi" -> "अगला स्टॉप $spokenNext है।"
              "en" -> "Next stop is $cleanNext."
              else -> "পরবর্তী স্টপেজ $spokenNext।"
            }
            VoiceAnnouncementManager.speak(nextSpeech, currentLangCode)
          }
        }
        nextAnnouncementRunnable = r
        serviceHandler.postDelayed(r, 3200L)
      }
      return
    }

    // ৩. শুধুমাত্র পরবর্তী স্টপেজ পরিবর্তিত হলে ঘোষণা (যদি বর্তমান স্টপ অপরিবর্তিত থাকে)
    if (cleanNext.isNotBlank() && cleanNext != "--" && !cleanNext.equals(cleanCur, ignoreCase = true) && cleanNext != lastAnnouncedServiceNext) {
      if (now - lastAnnouncementTime < 4000L) return
      lastAnnouncedServiceNext = cleanNext
      lastAnnouncementTime = now
      val nextSpeech = when (currentLangCode.lowercase()) {
        "hi" -> "अगला स्टॉप $spokenNext है।"
        "en" -> "Next stop is $cleanNext."
        else -> "পরবর্তী স্টপেজ $spokenNext।"
      }
      VoiceAnnouncementManager.speak(nextSpeech, currentLangCode)
    }
  }

  private fun handleNewLocation(location: Location, speedKmh: Float) {
    lastSpeedKmh = speedKmh

    // Notify in-app UI if open
    onLocationReceived?.invoke(location, speedKmh)

    // Dynamic destination and next stop distance recalculation from real GPS
    updateDynamicDistancesFromGps(location, speedKmh)

    // Rate-limit server upload to once every 4-5 seconds (কেবলমাত্র চালক/কন্ডাক্টর মোড চালু থাকলে)
    val now = System.currentTimeMillis()
    if (isStaffMode && now - lastServerSyncTimestamp >= 4000L && activeBusNumber.isNotBlank()) {
      lastServerSyncTimestamp = now
      uploadLocationToServer(location, speedKmh)
    }

    refreshNotification()
  }

  private fun refreshNotification() {
    if (!isRunning) return
    val notification = buildNotification()
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    notificationManager?.notify(NOTIFICATION_ID, notification)
  }

  private fun uploadLocationToServer(location: Location, speedKmh: Float) {
    serviceScope.launch {
      try {
        val payload = JSONObject().apply {
          put("action", "updateGPS")
          put("busNumber", activeBusNumber)
          put("lat", location.latitude)
          put("lon", location.longitude)
          put("speed", speedKmh)
          put("accuracy", location.accuracy)
        }

        val requestBody = payload.toString().toRequestBody("text/plain;charset=utf-8".toMediaType())
        val request = Request.Builder()
          .url(API_URL)
          .post(requestBody)
          .build()

        okHttpClient.newCall(request).execute().close()
      } catch (_: Exception) {
        // Handled gracefully, will retry on next GPS tick
      }
    }
  }

  private var backgroundPollingJob: kotlinx.coroutines.Job? = null

  private fun startBackgroundPollingLoop() {
    backgroundPollingJob?.cancel()
    backgroundPollingJob = serviceScope.launch {
      while (isRunning) {
        kotlinx.coroutines.delay(5000L)
        if (!isRunning) break
        if (activeBusNumber.isNotBlank()) {
          fetchBusLiveDataAndUpdate(activeBusNumber)
        }
      }
    }
  }

  private suspend fun fetchBusLiveDataAndUpdate(busNo: String) {
    try {
      val request = Request.Builder()
        .url("$API_URL?action=getLive&busNo=$busNo")
        .get()
        .build()
      okHttpClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
          val body = response.body?.string() ?: return@use
          val json = JSONObject(body)
          if (json.optBoolean("ok", false) && json.has("live")) {
            val liveObj = json.getJSONObject("live")
            val speed = liveObj.optDouble("speed", 0.0).toFloat()
            if (speed >= 0f) {
              lastSpeedKmh = speed
            }
            val cur = liveObj.optString("currentStop", "")
            if (cur.isNotBlank() && cur != "--") {
              currentStopText = cur
            }
            val next = liveObj.optString("nextStop", "")
            if (next.isNotBlank() && next != "--") {
              nextStopText = next
            }
            val busLat = liveObj.optDouble("lat", 0.0)
            val busLon = liveObj.optDouble("lon", 0.0)
            if (busLat != 0.0 && busLon != 0.0) {
              val busLoc = Location("").apply {
                latitude = busLat
                longitude = busLon
              }
              updateDynamicDistancesFromGps(busLoc, lastSpeedKmh)
            }
          }
          refreshNotification()
        }
      }
    } catch (_: Exception) {}
  }

  private fun getTranslation(lang: String, key: String): String {
    return when (lang.lowercase()) {
      "hi" -> when (key) {
        "currentStop" -> "वर्तमान स्टॉप"
        "nextStop" -> "अगला स्टॉप"
        "dest" -> "गंतव्य"
        "speed" -> "गति"
        "update" -> "अपडेट"
        "stopTracking" -> "ट्रैकिंग बंद करें"
        "busNo" -> "बस नंबर"
        "waiting" -> "जानकारी लोड हो रही है..."
        else -> ""
      }
      "en" -> when (key) {
        "currentStop" -> "Current Stop"
        "nextStop" -> "Next Stop"
        "dest" -> "Destination"
        "speed" -> "Speed"
        "update" -> "Updated"
        "stopTracking" -> "Stop Tracking"
        "busNo" -> "Bus No"
        "waiting" -> "Loading details..."
        else -> ""
      }
      "ta" -> when (key) {
        "currentStop" -> "தற்போதைய நிறுத்தம்"
        "nextStop" -> "அடுத்த நிறுத்தம்"
        "dest" -> "இலக்கு"
        "speed" -> "வேகம்"
        "update" -> "புதுப்பிப்பு"
        "stopTracking" -> "டிராக்கிங் நிறுத்து"
        "busNo" -> "பேருந்து எண்"
        "waiting" -> "விவரங்கள் ஏற்றப்படுகின்றன..."
        else -> ""
      }
      "te" -> when (key) {
        "currentStop" -> "ప్రస్తుత స్టాప్"
        "nextStop" -> "తదుపరి స్టాప్"
        "dest" -> "గమ్యస్థానం"
        "speed" -> "వేగం"
        "update" -> "అప్‌డేట్"
        "stopTracking" -> "ట్రాకింగ్ ఆపండి"
        "busNo" -> "బస్సు సంఖ్య"
        "waiting" -> "వివరాలు లోడ్ అవుతున్నాయి..."
        else -> ""
      }
      "mr" -> when (key) {
        "currentStop" -> "सध्याचा थांबा"
        "nextStop" -> "पुढील थांबा"
        "dest" -> "गंतव्य"
        "speed" -> "वेग"
        "update" -> "अपडेट"
        "stopTracking" -> "ट्रॅकिंग थांबवा"
        "busNo" -> "बस क्रमांक"
        "waiting" -> "माहिती लोड होत आहे..."
        else -> ""
      }
      "gu" -> when (key) {
        "currentStop" -> "વર્તમાન સ્ટોપ"
        "nextStop" -> "આગળનું સ્ટોપ"
        "dest" -> "ગંતવ્ય"
        "speed" -> "ઝડપ"
        "update" -> "અપડેટ"
        "stopTracking" -> "ટ્રેકિંગ બંધ કરો"
        "busNo" -> "બસ નંબર"
        "waiting" -> "વિગતો લોડ થઈ રહી છે..."
        else -> ""
      }
      "kn" -> when (key) {
        "currentStop" -> "ಪ್ರಸ್ತುತ ನಿಲ್ದಾಣ"
        "nextStop" -> "ಮುಂದಿನ ನಿಲ್ದಾಣ"
        "dest" -> "ಗಮ್ಯಸ್ಥಾನ"
        "speed" -> "ವೇಗ"
        "update" -> "ಅಪ್‌ಡೇಟ್"
        "stopTracking" -> "ಟ್ರ್ಯಾಕಿಂಗ್ ನಿಲ್ಲಿಸಿ"
        "busNo" -> "ಬಸ್ ಸಂಖ್ಯೆ"
        "waiting" -> "ವಿವರಗಳು ಲೋಡ್ ಆಗುತ್ತಿವೆ..."
        else -> ""
      }
      "ml" -> when (key) {
        "currentStop" -> "ഇപ്പോഴത്തെ സ്റ്റോപ്പ്"
        "nextStop" -> "അടുത്ത സ്റ്റോപ്പ്"
        "dest" -> "ലക്ഷ്യസ്ഥാനം"
        "speed" -> "വേഗത"
        "update" -> "അപ്ഡേറ്റ്"
        "stopTracking" -> "ട്രാക്കിംഗ് നിർത്തുക"
        "busNo" -> "ബസ് നമ്പർ"
        "waiting" -> "വിവരങ്ങൾ ലോഡ് ചെയ്യുന്നു..."
        else -> ""
      }
      "pa" -> when (key) {
        "currentStop" -> "ਮੌਜੂਦਾ ਸਟਾਪ"
        "nextStop" -> "ਅਗਲਾ ਸਟਾਪ"
        "dest" -> "ਮੰਜ਼ਿਲ"
        "speed" -> "ਰਫ਼ਤਾਰ"
        "update" -> "ਅੱਪਡੇਟ"
        "stopTracking" -> "ਟਰੈਕਿੰਗ ਬੰਦ ਕਰੋ"
        "busNo" -> "ਬੱਸ ਨੰਬਰ"
        "waiting" -> "ਵੇਰਵੇ ਲੋਡ ਹੋ ਰਹੇ ਹਨ..."
        else -> ""
      }
      "or" -> when (key) {
        "currentStop" -> "ବର୍ତ୍ତମାନ ଷ୍ଟପ୍"
        "nextStop" -> "ପରବର୍ତ୍ତୀ ଷ୍ଟପ୍"
        "dest" -> "ଗନ୍ତବ୍ୟସ୍ଥଳ"
        "speed" -> "ଗତି"
        "update" -> "ଅପଡେଟ୍"
        "stopTracking" -> "ଟ୍ରାକିଂ ବନ୍ଦ କରନ୍ତୁ"
        "busNo" -> "ବସ୍ ନମ୍ବର"
        "waiting" -> "ବିବରଣୀ ଲୋଡ୍ ହେଉଛି..."
        else -> ""
      }
      else -> when (key) {
        "currentStop" -> "বর্তমান স্টপেজ"
        "nextStop" -> "পরবর্তী স্টপেজ"
        "dest" -> "গন্তব্য"
        "speed" -> "গতি"
        "update" -> "আপডেট"
        "stopTracking" -> "ট্র্যাকিং বন্ধ"
        "busNo" -> "বাস নম্বর"
        "waiting" -> "তথ্য লোড হচ্ছে..."
        else -> ""
      }
    }
  }

  private fun buildNotification(): Notification {
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, BusTrackingService::class.java).apply {
        action = ACTION_STOP_TRACKING
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // বাসের নাম ও নম্বরসহ শিরোনাম ("লাইভ ট্র্যাকিং চলছে" লেখা ছাড়া)
    val title = if (activeBusName.isNotBlank() && activeBusNumber.isNotBlank()) {
      "🚌 $activeBusName ($activeBusNumber)"
    } else if (activeBusName.isNotBlank()) {
      "🚌 $activeBusName"
    } else if (activeBusNumber.isNotBlank()) {
      "🚌 ${getTranslation(currentLangCode, "busNo")}: $activeBusNumber"
    } else {
      "🚌 Bus Live Location"
    }

    val speedText = if (lastSpeedKmh > 0f) "${lastSpeedKmh.toInt()} KM/H" else "0 KM/H"
    val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
    val formattedTime = timeFormat.format(Date())

    val curLabel = getTranslation(currentLangCode, "currentStop")
    val nextLabel = getTranslation(currentLangCode, "nextStop")
    val destLabel = getTranslation(currentLangCode, "dest")
    val speedLabel = getTranslation(currentLangCode, "speed")
    val updateLabel = getTranslation(currentLangCode, "update")
    val stopActionText = getTranslation(currentLangCode, "stopTracking")

    val curDisplay = if (currentStopText.isNotBlank() && currentStopText != "--") currentStopText else getTranslation(currentLangCode, "waiting")
    var nextDisplay = if (nextStopText.isNotBlank() && nextStopText != "--") nextStopText else getTranslation(currentLangCode, "waiting")
    val destDisplay = if (destDistEtaText.isNotBlank() && destDistEtaText != "--") destDistEtaText else getTranslation(currentLangCode, "waiting")

    val cleanCurDisp = getCleanStopBaseName(curDisplay)
    val cleanNextDisp = getCleanStopBaseName(nextDisplay)

    if (cleanCurDisp.isNotBlank() && cleanCurDisp.equals(cleanNextDisp, ignoreCase = true)) {
      if (activeRouteStops.isNotEmpty()) {
        val curIdx = activeRouteStops.indexOfFirst {
          getCleanStopBaseName(it.name).equals(cleanCurDisp, ignoreCase = true)
        }
        if (curIdx >= 0 && curIdx + 1 < activeRouteStops.size) {
          val nextSt = activeRouteStops[curIdx + 1]
          nextDisplay = nextSt.name
        } else if (destName.isNotBlank() && !getCleanStopBaseName(destName).equals(cleanCurDisp, ignoreCase = true)) {
          nextDisplay = destName
        } else {
          nextDisplay = when (currentLangCode.lowercase()) {
            "en" -> "Final Destination"
            "hi" -> "अंतिम गंतव्य"
            else -> "গন্তব্যের শেষ স্টপেজ"
          }
        }
      } else if (destName.isNotBlank() && !getCleanStopBaseName(destName).equals(cleanCurDisp, ignoreCase = true)) {
        nextDisplay = destName
      }
    }

    // সংক্ষেপিত ভিউ (Custom RemoteViews Collapsed)
    val remoteViewsCollapsed = RemoteViews(packageName, R.layout.notification_custom_collapsed).apply {
      setTextViewText(R.id.tv_collapsed_title, title)
      setTextViewText(R.id.tv_collapsed_info, "⚡ $speedText")

      setTextViewText(R.id.tv_collapsed_cur_label, "📍 $curLabel")
      setTextViewText(R.id.tv_collapsed_cur_value, curDisplay)

      setTextViewText(R.id.tv_collapsed_next_label, "⏩ $nextLabel")
      setTextViewText(R.id.tv_collapsed_next_value, nextDisplay)

      setTextViewText(R.id.tv_collapsed_dest_label, "🎯 $destLabel")
      setTextViewText(R.id.tv_collapsed_dest_value, destDisplay)
    }

    // বিস্তারিত ৪ লাইনের এক্সপান্ডেড ভিউ (Custom RemoteViews Expanded with Themed Cards)
    val remoteViewsExpanded = RemoteViews(packageName, R.layout.notification_custom_expanded).apply {
      setTextViewText(R.id.tv_expanded_title, title)
      setTextViewText(R.id.btn_expanded_stop, "⛔ $stopActionText")
      setOnClickPendingIntent(R.id.btn_expanded_stop, stopIntent)

      setTextViewText(R.id.tv_expanded_cur_label, "📍 $curLabel")
      setTextViewText(R.id.tv_expanded_cur_value, curDisplay)

      setTextViewText(R.id.tv_expanded_next_label, "⏩ $nextLabel")
      setTextViewText(R.id.tv_expanded_next_value, nextDisplay)

      setTextViewText(R.id.tv_expanded_dest_label, "🎯 $destLabel")
      setTextViewText(R.id.tv_expanded_dest_value, destDisplay)

      setTextViewText(R.id.tv_expanded_speed, "⚡ $speedLabel: $speedText")
      setTextViewText(R.id.tv_expanded_time, "🕒 $updateLabel: $formattedTime")
    }

    val collapsedSummary = if (currentStopText != "--" && nextStopText != "--") {
      "🟠 $currentStopText | 🔵 $nextStopText"
    } else {
      "⚡ $speedLabel: $speedText | 🕒 $updateLabel: $formattedTime"
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_bus_notification)
      .setContentTitle(title)
      .setContentText(collapsedSummary)
      .setCustomContentView(remoteViewsCollapsed)
      .setCustomBigContentView(remoteViewsExpanded)
      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        stopActionText,
        stopIntent
      )
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "বাস লাইভ লোকেশন ট্র্যাকিং",
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = "স্ক্রিন বন্ধ থাকলেও চলন্ত বাসের যাত্রী ও যানের লাইভ লোকেশন আপডেট পাঠাতে ব্যবহৃত হয়"
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      }
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      notificationManager?.createNotificationChannel(channel)
    }
  }

  private fun stopTracking() {
    isRunning = false
    activeBusNumber = ""
    activeBusName = ""
    currentStopText = "--"
    nextStopText = "--"
    destDistEtaText = "--"
    destName = ""
    destLat = 0.0
    destLon = 0.0
    nextName = ""
    nextLat = 0.0
    nextLon = 0.0

    lastAnnouncedServiceCurrent = ""
    lastAnnouncedServiceNext = ""
    lastAnnouncedServiceDest = ""
    lastAnnouncementTime = 0L
    activeRouteStops = emptyList()
    VoiceAnnouncementManager.clearQueue()
    nextAnnouncementRunnable?.let { serviceHandler.removeCallbacks(it) }
    nextAnnouncementRunnable = null

    try {
      backgroundPollingJob?.cancel()
      backgroundPollingJob = null
    } catch (_: Exception) {}

    try {
      locationManager?.removeUpdates(locationListener)
    } catch (_: Exception) {}

    try {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    } catch (_: Exception) {}

    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()

    try {
      onTrackingStopped?.invoke()
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    super.onDestroy()
    stopTracking()
    serviceScope.cancel()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
