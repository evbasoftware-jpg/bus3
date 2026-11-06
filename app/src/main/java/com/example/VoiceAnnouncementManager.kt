package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object VoiceAnnouncementManager {

  private var appContext: Context? = null
  private var textToSpeech: TextToSpeech? = null
  private var isTtsInitialized = false
  private var audioPlayer: MediaPlayer? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private val speechQueue = ConcurrentLinkedQueue<Pair<String, String>>()
  private var isAnnouncing = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val ioScope = CoroutineScope(Dispatchers.IO)

  fun init(context: Context) {
    if (appContext != null && isTtsInitialized) return
    appContext = context.applicationContext

    try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
      wakeLock = pm?.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "BusTracker::VoiceAnnouncementWakeLock"
      )
    } catch (_: Exception) {}

    initTextToSpeech(context.applicationContext)
  }

  private fun initTextToSpeech(context: Context) {
    val googleEngine = "com.google.android.tts"
    textToSpeech = TextToSpeech(context, { status ->
      if (status == TextToSpeech.SUCCESS) {
        isTtsInitialized = true
        setupTtsVoice("bn")
      } else {
        // Fallback to default engine if Google engine unavailable
        textToSpeech = TextToSpeech(context) { fallbackStatus ->
          if (fallbackStatus == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            setupTtsVoice("bn")
          }
        }
      }
    }, googleEngine)
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
              n.contains("zira")
            ) return true
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
              n.contains("-m-") || n.contains("_m") || n.endsWith("-m") || n.endsWith("_m")
            ) return true
            return v.features.any {
              val fl = it.lowercase()
              fl.contains("gender=male") || fl == "male"
            }
          }

          val femaleVoices = matchingLocaleVoices.filter { isExplicitlyFemale(it) && !isExplicitlyMale(it) }
          val selectedVoice = if (femaleVoices.isNotEmpty()) {
            femaleVoices.sortedWith(
              compareByDescending<Voice> { it.isNetworkConnectionRequired || it.name.contains("network") }
                .thenByDescending { it.quality }
            ).firstOrNull()
          } else {
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

      if (isFemaleSelected) {
        tts.setPitch(1.22f)
        tts.setSpeechRate(0.92f)
      } else {
        tts.setPitch(1.25f)
        tts.setSpeechRate(0.92f)
      }
    } catch (_: Exception) {}
  }

  fun formatTextForTts(text: String): String {
    var out = text
    out = out.replace(Regex("(?i)\\bBDO\\s+OFFICE\\b"), "বিডিও অফিস")
    out = out.replace(Regex("(?i)\\bBDO\\b"), "বিডিও")
    out = out.replace(Regex("(?i)\\bOFFICE\\b"), "অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\s+OFFICE\\b"), "এসডিও অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\b"), "এসডিও")
    out = out.replace(Regex("(?i)\\bAHMADPUR\\b"), "আহমদপুর")
    out = out.replace(Regex("(?i)\\bAHMEDPUR\\b"), "আহমদপুর")

    val regex = Regex("\\b[A-Z]{2,}\\b")
    return regex.replace(out) { matchResult ->
      val word = matchResult.value
      word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
  }

  private var lastSpokenText: String = ""
  private var lastSpokenTimestamp: Long = 0L

  fun speak(text: String, langCode: String = "bn") {
    if (text.isBlank()) return
    val preparedText = formatTextForTts(text)
    val curLang = if (langCode.isBlank()) "bn" else langCode

    val now = System.currentTimeMillis()
    // একই টেক্সট ১০ সেকেন্ডের মধ্যে পুনরাবৃত্তি করবেন না (নন-স্টপ লুপ প্রতিরোধ)
    if (preparedText == lastSpokenText && (now - lastSpokenTimestamp < 10000L)) {
      return
    }

    lastSpokenText = preparedText
    lastSpokenTimestamp = now

    // কিউতে আগের জমে থাকা ঘোষণা পরিষ্কার করুন যাতে ব্যাকলগ নন-স্টপ না বাজে
    speechQueue.clear()
    speechQueue.add(Pair(preparedText, curLang))

    // Acquire WakeLock so CPU does not sleep during announcement
    try {
      wakeLock?.acquire(10000L) // 10 seconds max safety
    } catch (_: Exception) {}

    mainHandler.post {
      processNextSpeech()
    }
  }

  private fun processNextSpeech() {
    if (isAnnouncing || speechQueue.isEmpty()) return
    val item = speechQueue.poll() ?: return
    isAnnouncing = true
    val preparedText = item.first
    val curLang = item.second

    val onDoneCallback: () -> Unit = {
      mainHandler.post {
        mainHandler.postDelayed({
          isAnnouncing = false
          processNextSpeech()
        }, 700L)
      }
    }

    // দ্রুত ও মিষ্টি সুরেলা অনলাইন নারী কণ্ঠ অগ্রাধিকার দিন (অ্যাপ মিনিমাইজ বা স্ক্রিন অফ থাকলেও কাজ করবে)
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
    val ctx = appContext ?: run {
      onComplete(false)
      return
    }

    val targetLang = when (langCode.lowercase()) {
      "en" -> "en-IN"
      "hi" -> "hi"
      else -> "bn"
    }

    ioScope.launch {
      try {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encodedText&tl=$targetLang&client=tw-ob"
        val url = URL(urlStr)

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.setRequestProperty(
          "User-Agent",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        )

        if (connection.responseCode != 200) {
          withContext(Dispatchers.Main) { onComplete(false) }
          return@launch
        }

        val cacheFile = File(ctx.cacheDir, "tts_voice_${System.currentTimeMillis()}.mp3")
        connection.inputStream.use { input ->
          cacheFile.outputStream().use { output ->
            input.copyTo(output)
          }
        }

        withContext(Dispatchers.Main) {
          if (cacheFile.exists() && cacheFile.length() > 500) {
            playAudioFile(cacheFile, onComplete)
          } else {
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

  private fun playAudioFile(file: File, onDone: (Boolean) -> Unit) {
    val ctx = appContext ?: run {
      onDone(false)
      return
    }

    try {
      audioPlayer?.stop()
      audioPlayer?.release()
      audioPlayer = null

      val player = MediaPlayer()
      player.setAudioAttributes(
        AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .build()
      )
      player.setDataSource(ctx, Uri.fromFile(file))
      player.setOnCompletionListener { mp ->
        try { mp.release() } catch (_: Exception) {}
        if (audioPlayer === mp) audioPlayer = null
        try { file.delete() } catch (_: Exception) {}
        onDone(true)
      }
      player.setOnErrorListener { mp, _, _ ->
        try { mp.release() } catch (_: Exception) {}
        if (audioPlayer === mp) audioPlayer = null
        try { file.delete() } catch (_: Exception) {}
        onDone(false)
        true
      }
      player.prepare()
      player.start()
      audioPlayer = player
    } catch (_: Exception) {
      onDone(false)
    }
  }

  private fun speakViaNativeTts(text: String, langCode: String, onDone: () -> Unit) {
    val tts = textToSpeech
    if (tts == null || !isTtsInitialized) {
      onDone()
      return
    }

    try {
      wakeLock?.acquire(12000L)
    } catch (_: Exception) {}

    val safeDone: () -> Unit = {
      try {
        if (wakeLock?.isHeld == true) wakeLock?.release()
      } catch (_: Exception) {}
      onDone()
    }

    setupTtsVoice(langCode)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      val params = android.os.Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "native_${System.currentTimeMillis()}")
      }
      tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { safeDone() }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { safeDone() }
        override fun onError(utteranceId: String?, errorCode: Int) { safeDone() }
      })
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "native_${System.currentTimeMillis()}")
    } else {
      @Suppress("DEPRECATION")
      val params = HashMap<String, String>().apply {
        put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "native_${System.currentTimeMillis()}")
      }
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, params)
      val estimatedMs = (text.length * 110L).coerceIn(1200L, 7000L)
      mainHandler.postDelayed({ safeDone() }, estimatedMs)
    }
  }

  fun clearQueue() {
    speechQueue.clear()
    isAnnouncing = false
    lastSpokenText = ""
    lastSpokenTimestamp = 0L
    try {
      audioPlayer?.stop()
      audioPlayer?.release()
      audioPlayer = null
    } catch (_: Exception) {}
    try {
      textToSpeech?.stop()
    } catch (_: Exception) {}
  }

  fun shutdown() {
    clearQueue()
    try {
      textToSpeech?.shutdown()
      textToSpeech = null
      isTtsInitialized = false
    } catch (_: Exception) {}
  }

  private fun isNetworkAvailable(): Boolean {
    val ctx = appContext ?: return false
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
}
