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

  val BENGALI_STOP_DICTIONARY = mapOf(
    "LABPUR" to "লাভপুর",
    "SURI" to "সিউড়ি",
    "BOLPUR" to "বোলপুর",
    "KIRNAHAR" to "কীর্ণাহার",
    "KIRNAHAR BUS STAND" to "কীর্ণাহার বাস স্ট্যান্ড",
    "BOLPUR BUS STAND" to "বোলপুর বাস স্ট্যান্ড",
    "SURI BUS STAND" to "সিউড়ি বাস স্ট্যান্ড",
    "LABPUR BUS STAND" to "লাভপুর বাস স্ট্যান্ড",
    "SAINTHIA" to "সাঁইথিয়া",
    "AHMEDPUR" to "আহমদপুর",
    "AHMADPUR" to "আহমদপুর",
    "AHMADPUR STATION" to "আহমদপুর স্টেশন",
    "AHMEDPUR STATION" to "আহমদপুর স্টেশন",
    "DUBRAJPUR" to "দুবরাজপুর",
    "ILLAMBAZAR" to "ইলামবাজার",
    "ILAMBAZAR" to "ইলামবাজার",
    "RAMPURHAT" to "রামপুরহাট",
    "NALHATI" to "নলহাটি",
    "MURARAI" to "মুরারই",
    "MALLARPUR" to "মল্লারপুর",
    "MAYURESWAR" to "ময়ূরেশ্বর",
    "PRANTIK" to "প্রান্তিক",
    "KANKALITALA" to "কঙ্কালীতলা",
    "NANOOR" to "নানুর",
    "GUSHKARA" to "গুসকরা",
    "BHEDIA" to "ভেড়িয়া",
    "BARDHAMAN" to "বর্ধমান",
    "BURDWAN" to "বর্ধমান",
    "DURGAPUR" to "দুর্গাপুর",
    "ASANSOL" to "আসানসোল",
    "KOLKATA" to "কলকাতা",
    "PURANDARPUR" to "পুরন্দরপুর",
    "KOTASUR" to "কোটাসুর",
    "TARAPITH" to "তারাপীঠ",
    "SUNDIPUR" to "সুন্দিপুর",
    "CHOWHATTA" to "চৌহাট্টা",
    "HIRAPUR" to "হীরাপুর",
    "KUSUMGORIA" to "কুসুমগড়িয়া",
    "GORURHAT SASTHINAGAR" to "গরুরহাট ষষ্ঠীনগর",
    "GORURHAT" to "গরুরহাট",
    "SASTHINAGAR" to "ষষ্ঠীনগর",
    "BAMNIGRAM" to "বামনিগ্রাম",
    "UJJWALPUR" to "উজ্জ্বলপুর",
    "KADWA" to "কাদোয়া",
    "BORO PUKUR" to "বড়পুকুর",
    "BOROPUKUR" to "বড়পুকুর",
    "INDUS" to "ইন্দাস",
    "GHOSHPARA" to "ঘোষপাড়া",
    "BIPRATIKURI" to "বিপ্রতিকুড়ি",
    "CHOWRASTA" to "চৌরাস্তা",
    "JAMBU" to "জাম্বু",
    "JAMBUNI" to "জামবুনী",
    "BAHIRI" to "বাহিরী",
    "PICHHULDANGA" to "পিছুলডাঙ্গা",
    "DWARKA" to "দ্বারকা",
    "SINGHEE" to "সিংঘী",
    "PUNJABI MORE" to "পাঞ্জাবি মোড়",
    "TIN MATHA MORE" to "তিন মাথা মোড়",
    "STATION MORE" to "স্টেশন মোড়",
    "HOSPITAL MORE" to "হাসপাতাল মোড়",
    "COLLEGE MORE" to "কলেজ মোড়",
    "COURT MORE" to "কোর্ট মোড়",
    "BUS STAND" to "বাস স্ট্যান্ড",
    "BDO" to "বিডিও",
    "B.D.O." to "বিডিও",
    "B.D.O" to "বিডিও",
    "OFFICE" to "অফিস",
    "BDO OFFICE" to "বিডিও অফিস",
    "BDO OFFICE AHMADPUR" to "বিডিও অফিস আহমদপুর",
    "BDO OFFICE AHMEDPUR" to "বিডিও অফিস আহমদপুর",
    "SDO" to "এসডিও",
    "SDO OFFICE" to "এসডিও অফিস",
    "PS" to "থানা",
    "POLICE STATION" to "পুলিশ স্টেশন",
    "KEUDA" to "কেউদা",
    "KUCHUIGHATA" to "কুচুইঘাটা",
    "REGISTRY PARA" to "রেজিস্ট্রি পাড়া",
    "REGISTRY PARA LABPUR" to "রেজিস্ট্রি পাড়া লাভপুর",
    "TETULTALA" to "তেঁতুলতলা",
    "KADAMTALA" to "কদমতলা",
    "BLOCK" to "ব্লক",
    "PANCHAYAT" to "পঞ্চায়েত",
    "STATION" to "স্টেশন",
    "RLY STATION" to "রেলওয়ে স্টেশন",
    "RAILWAY STATION" to "রেলওয়ে স্টেশন"
  )

  fun toBengaliSpokenStopName(raw: String): String {
    val clean = raw.replace(Regex("\\(.*?\\)"), "").replace(Regex("[🚏🚌📍🏁]"), "").trim()
    if (clean.isBlank() || clean == "--") return ""
    if (clean.any { it in '\u0980'..'\u09FF' }) return clean
    val upper = clean.uppercase()
    BENGALI_STOP_DICTIONARY[upper]?.let { return it }

    val parts = upper.split(Regex("\\s+"))
    if (parts.size > 1) {
      return parts.joinToString(" ") { p ->
        BENGALI_STOP_DICTIONARY[p] ?: transliterateWord(p)
      }
    }
    return transliterateWord(upper)
  }

  private fun transliterateWord(w: String): String {
    var word = w.trim().uppercase()
    if (word.isBlank()) return ""
    if (word.any { it in '\u0980'..'\u09FF' }) return word
    BENGALI_STOP_DICTIONARY[word]?.let { return it }

    val suffixes = listOf(
      Regex("PURAM$") to "পুরম", Regex("PUR$") to "পুর", Regex("NAGAR$") to "নগর",
      Regex("PARA$") to "পাড়া", Regex("HAT$") to "হাট", Regex("GRAM$") to "গ্রাম",
      Regex("DANGA$") to "ডাঙ্গা", Regex("DIHI$") to "ডিহি", Regex("PUKUR$") to "পুকুর",
      Regex("GHAT$") to "ঘাট", Regex("MORE$") to "মোড়", Regex("KURI$") to "কুড়ি",
      Regex("BARI$") to "বাড়ি", Regex("TALA$") to "তলা", Regex("BAZAR$") to "বাজার",
      Regex("GANJ$") to "গঞ্জ", Regex("BAD$") to "বাদ", Regex("KALI$") to "কালী",
      Regex("GORIA$") to "গড়িয়া", Regex("BANDH$") to "বাঁধ", Regex("STAND$") to "স্ট্যান্ড"
    )

    var foundSuffix = ""
    for ((pat, repl) in suffixes) {
      if (pat.containsMatchIn(word)) {
        word = word.replace(pat, "")
        foundSuffix = repl
        break
      }
    }

    val clusters = listOf(
      "SHH" to "ষ", "CHH" to "ছ", "KH" to "খ", "GH" to "ঘ", "CH" to "চ", "JH" to "ঝ",
      "TH" to "থ", "DH" to "ধ", "PH" to "ফ", "BH" to "ভ", "SH" to "শ", "NG" to "ঙ",
      "NJ" to "ঞ্জ", "ND" to "ন্দ", "NT" to "ন্ত", "MB" to "ম্ব", "MP" to "ম্প",
      "ST" to "স্ট", "STR" to "স্ট্র", "TR" to "ত্র", "PR" to "প্র", "KR" to "ক্র",
      "BR" to "ব্র", "DR" to "দ্র", "GR" to "গ্র", "TT" to "ট্ট", "DD" to "ড্ড"
    )

    val singleConsonants = mapOf(
      'K' to "ক", 'G' to "গ", 'C' to "ক", 'J' to "জ", 'Z' to "জ",
      'T' to "ট", 'D' to "ড", 'N' to "ন", 'P' to "প", 'F' to "ফ",
      'B' to "ব", 'V' to "ভ", 'M' to "ম", 'Y' to "য", 'R' to "র",
      'L' to "ল", 'W' to "ওয়", 'S' to "স", 'H' to "হ"
    )

    val vowelIndep = mapOf(
      "AA" to "আ", "A" to "অ", "I" to "ই", "EE" to "ঈ", "U" to "উ",
      "OO" to "ঊ", "E" to "এ", "AI" to "ঐ", "O" to "ও", "AU" to "ঔ", "OU" to "ঔ", "OW" to "ঔ"
    )

    val vowelDep = mapOf(
      "AA" to "া", "A" to "া", "I" to "ি", "EE" to "ী", "U" to "ু",
      "OO" to "ূ", "E" to "ে", "AI" to "ৈ", "O" to "ো", "AU" to "ৌ", "OU" to "ৌ", "OW" to "ৌ"
    )

    var res = ""
    var idx = 0
    var lastWasConsonant = false

    while (idx < word.length) {
      var vMatch: String? = null
      for (vk in listOf("AA", "EE", "OO", "AI", "AU", "OU", "OW")) {
        if (word.startsWith(vk, idx)) {
          vMatch = vk
          break
        }
      }
      if (vMatch == null && listOf('A', 'I', 'U', 'E', 'O').contains(word[idx])) {
        vMatch = word[idx].toString()
      }

      if (vMatch != null) {
        res += if (lastWasConsonant) (vowelDep[vMatch] ?: "া") else (vowelIndep[vMatch] ?: "অ")
        idx += vMatch.length
        lastWasConsonant = false
        continue
      }

      var cMatch: Pair<String, String>? = null
      for (c in clusters) {
        if (word.startsWith(c.first, idx)) {
          cMatch = c
          break
        }
      }

      if (cMatch != null) {
        res += cMatch.second
        idx += cMatch.first.length
        lastWasConsonant = true
        continue
      }

      val ch = word[idx]
      val mapped = singleConsonants[ch]
      if (mapped != null) {
        res += mapped
        lastWasConsonant = true
      } else {
        res += ch
        lastWasConsonant = false
      }
      idx++
    }

    return (res + foundSuffix).trim()
  }

  fun setupTtsVoice(langCode: String) {
    val tts = textToSpeech ?: return
    val targetLocale = getLocaleForLanguage(langCode)
    try {
      tts.language = targetLocale

      var isFemaleSelected = false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val availableVoices = tts.voices
        if (!availableVoices.isNullOrEmpty()) {
          // ভারতীয় স্ট্যান্ডার্ড ভয়েস (country = IN বা নাম bn-in) অগ্রাধিকার দিন
          val matchingLocaleVoices = availableVoices.filter {
            it.locale.language.equals(targetLocale.language, ignoreCase = true) &&
            (it.locale.country.equals("IN", ignoreCase = true) || it.name.lowercase().contains("-in") || it.name.lowercase().contains("_in"))
          }.ifEmpty {
            availableVoices.filter {
              it.locale.language.equals(targetLocale.language, ignoreCase = true)
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
              n.contains("zira") || n.contains("ananya")
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
              .firstOrNull() ?: matchingLocaleVoices.firstOrNull()
          }

          if (selectedVoice != null) {
            tts.voice = selectedVoice
            isFemaleSelected = isExplicitlyFemale(selectedVoice)
          }
        }
      }

      // ভারতীয় মিষ্টি, মার্জিত ও স্পষ্ট সুরেলা কণ্ঠের জন্য আদর্শ পিচ ও গতি
      tts.setPitch(1.08f)
      tts.setSpeechRate(0.92f)
    } catch (_: Exception) {}
  }

  fun formatTextForTts(text: String): String {
    var out = text
    // কোলন (:) বাদ দেওয়া যাতে টিটিএস অপ্রয়োজনীয় বিরতি বা অদ্ভুত উচ্চারণ না করে
    out = out.replace(":", " ")

    // সাধারণ পরিচিত শব্দ রূপান্তর
    out = out.replace(Regex("(?i)\\bBDO\\s+OFFICE\\b"), "বিডিও অফিস")
    out = out.replace(Regex("(?i)\\bBDO\\b"), "বিডিও")
    out = out.replace(Regex("(?i)\\bOFFICE\\b"), "অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\s+OFFICE\\b"), "এসডিও অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\b"), "এসডিও")
    out = out.replace(Regex("(?i)\\bAHMADPUR\\b"), "আহমদপুর")
    out = out.replace(Regex("(?i)\\bAHMEDPUR\\b"), "আহমদপুর")
    out = out.replace(Regex("(?i)\\bSTATION\\b"), "স্টেশন")
    out = out.replace(Regex("(?i)\\bTETULTALA\\b"), "তেঁতুলতলা")
    out = out.replace(Regex("(?i)\\bKADAMTALA\\b"), "কদমতলা")
    out = out.replace(Regex("(?i)\\bLABPUR\\b"), "লাভপুর")
    out = out.replace(Regex("(?i)\\bCHOWHATTA\\b"), "চৌহাট্টা")
    out = out.replace(Regex("(?i)\\bSAINTHIA\\b"), "সাঁইথিয়া")
    out = out.replace(Regex("(?i)\\bBOLPUR\\b"), "বোলপুর")
    out = out.replace(Regex("(?i)\\bSURI\\b"), "সিউড়ি")

    // টেক্সটে যদি এখনো কোনো ইংরেজি ক্যাপিটাল শব্দ বা নাম থাকে, তা স্বয়ংক্রিয়ভাবে বাংলায় রূপান্তর করা
    val engRegex = Regex("\\b[A-Za-z]{2,}\\b")
    out = engRegex.replace(out) { matchResult ->
      val word = matchResult.value
      toBengaliSpokenStopName(word)
    }

    return out.replace(Regex("\\s+"), " ").trim()
  }

  private var lastSpokenText: String = ""
  private var lastSpokenTimestamp: Long = 0L

  fun speak(text: String, langCode: String = "bn") {
    if (text.isBlank()) return
    val preparedText = formatTextForTts(text)
    val curLang = if (langCode.isBlank()) "bn" else langCode

    val now = System.currentTimeMillis()
    // একই টেক্সট ১০ সেকেন্ডের মধ্যে পুনরাবৃত্তি করবেন না
    if (preparedText == lastSpokenText && (now - lastSpokenTimestamp < 10000L)) {
      return
    }

    lastSpokenText = preparedText
    lastSpokenTimestamp = now

    speechQueue.clear()
    speechQueue.add(Pair(preparedText, curLang))

    try {
      wakeLock?.acquire(10000L)
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

    // ডিভাইসের নিজস্ব গুগল টেক্সট-টু-স্পিচ (com.google.android.tts) সর্বদা খাঁটি ভারতীয় কণ্ঠ প্রদান করে
    if (isTtsInitialized && textToSpeech != null) {
      speakViaNativeTts(preparedText, curLang, onDoneCallback)
      return
    }

    // ফলব্যাক: যদি ডিভাইসে নেটিভ টিটিএস ইনিশিয়ালাইজ না হয়ে থাকে
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
