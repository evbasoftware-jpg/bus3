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
    "AHMEDPUR" to "আমেদপুর",
    "AHMADPUR" to "আমেদপুর",
    "AHMADPUR STATION" to "আমেদপুর স্টেশন",
    "AHMEDPUR STATION" to "আমেদপুর স্টেশন",
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
    "BDO OFFICE AHMADPUR" to "বিডিও অফিস আমেদপুর",
    "BDO OFFICE AHMEDPUR" to "বিডিও অফিস আমেদপুর",
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

  fun fixBengaliPronunciation(str: String): String {
    if (str.isBlank()) return ""
    return str
      .replace("আহমেদপুর", "আমেদপুর")
      .replace("আহমদপুর", "আমেদপুর")
      .replace("আহমাদপুর", "আমেদপুর")
      .replace("অহমদপুর", "আমেদপুর")
      .replace("আহামদপুর", "আমেদপুর")
      .replace("অহারুপুর", "আমেদপুর")
      .replace("অহরোপর", "আমেদপুর")
      .replace("আহমারপুর", "আমেদপুর")
      .replace("অহমাডপুর", "আমেদপুর")
      .replace("ওহাডপুর", "আমেদপুর")
      .replace("ওহাদপুর", "আমেদপুর")
      .replace("অহাদপুর", "আমেদপুর")
      .replace("অহদপুর", "আমেদপুর")
      .replace(Regex("(?i)\\bAHMADPUR\\b"), "আমেদপুর")
      .replace(Regex("(?i)\\bAHMEDPUR\\b"), "আমেদপুর")
      .replace(Regex("(?i)\\bAHADPUR\\b"), "আমেদপুর")
      .replace(Regex("\\bআহমেদ\\b"), "আমেদ")
      .replace(Regex("\\bআহমদ\\b"), "আমেদ")
      .replace(Regex("\\bআহমাদ\\b"), "আমেদ")
  }

  fun toBengaliSpokenStopName(raw: String): String {
    val clean = raw.replace(Regex("\\(.*?\\)"), "").replace(Regex("[🚏🚌📍🏁]"), "").trim()
    if (clean.isBlank() || clean == "--") return ""
    if (clean.any { it in '\u0980'..'\u09FF' }) return fixBengaliPronunciation(clean)
    val upper = clean.uppercase()
    BENGALI_STOP_DICTIONARY[upper]?.let { return fixBengaliPronunciation(it) }

    val parts = upper.split(Regex("\\s+"))
    val res = if (parts.size > 1) {
      parts.joinToString(" ") { p ->
        BENGALI_STOP_DICTIONARY[p] ?: transliterateWord(p)
      }
    } else {
      transliterateWord(upper)
    }
    return fixBengaliPronunciation(res)
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

    if (word == "AHMAD" || word == "AHMED") return "আমেদ" + foundSuffix
    if (word.startsWith("AHMAD") || word.startsWith("AHMED")) {
      return word.replace("AHMAD", "আমেদ").replace("AHMED", "আমেদ") + foundSuffix
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

  data class VoicePickResult(
    val langSupported: Boolean = false,
    val hasMatchingVoice: Boolean = false,
    val isFemaleVoice: Boolean = false,
    val isMaleVoice: Boolean = false
  )

  fun setupTtsVoice(langCode: String): VoicePickResult {
    val tts = textToSpeech ?: return VoicePickResult()
    val targetLocale = getLocaleForLanguage(langCode)
    var langSupported = false
    var hasMatchingVoice = false
    var isFemaleSelected = false
    var isMaleSelected = false

    try {
      val langStatus = tts.setLanguage(targetLocale)
      langSupported = langStatus != TextToSpeech.LANG_MISSING_DATA &&
        langStatus != TextToSpeech.LANG_NOT_SUPPORTED

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val availableVoices = tts.voices
        if (!availableVoices.isNullOrEmpty()) {
          val langVoices = availableVoices.filter {
            it.locale.language.equals(targetLocale.language, ignoreCase = true)
          }
          hasMatchingVoice = langVoices.isNotEmpty()

          val matchingLocaleVoices = langVoices.filter {
            it.locale.country.equals("IN", ignoreCase = true) ||
              it.locale.country.equals(targetLocale.country, ignoreCase = true) ||
              it.name.lowercase().contains("-in") ||
              it.name.lowercase().contains("_in")
          }.ifEmpty { langVoices }

          fun isExplicitlyMale(v: Voice): Boolean {
            val n = v.name.lowercase()
            if (Regex("(^|[-_])x-[a-z]{2}[bdm]([-_]|$)").containsMatchIn(n) ||
              Regex("wavenet-[bd]|neural2-[bd]|standard-[bd]").containsMatchIn(n) ||
              (n.contains("male") && !n.contains("female")) ||
              Regex("\\b(man|boy|ravi|david|mark|daniel|alex|prabhat|hemant|madhur|rishi|otoya|tarik|maged)\\b").containsMatchIn(n) ||
              n.contains("-m-") || n.contains("_m_") || n.endsWith("-m") || n.endsWith("_m")
            ) return true
            return v.features.any {
              val fl = it.lowercase()
              fl.contains("gender=male") || fl == "male"
            }
          }

          fun isExplicitlyFemale(v: Voice): Boolean {
            if (isExplicitlyMale(v)) return false
            val n = v.name.lowercase()
            if (n.contains("female") || n.contains("woman") || n.contains("girl") ||
              Regex("(^|[-_])x-[a-z]{2}[acef]([-_]|$)").containsMatchIn(n) ||
              Regex("wavenet-[acef]|neural2-[acef]|standard-[acef]").containsMatchIn(n) ||
              n.contains("-fem") || n.contains("_fem") || n.contains("fem_") ||
              n.contains("-f-") || n.contains("_f_") || n.endsWith("-f") || n.endsWith("_f") ||
              n.contains("voice-2") || n.contains("voice-4") || n.contains("voice_2") || n.contains("voice_4") ||
              Regex("\\b(swara|aditi|kavya|lekha|sangeeta|priya|zira|ananya|shruti|maya|kalpana|heera|neerja|veena|samantha|victoria|karen|tessa|moira|fiona|jenny|aria|sonia|libby|kyoko|yuna|damayanti|kanya|linh|yelda|alice|anna|amelie|paulina|monica|laura|milena|katya|laila|carmit)\\b").containsMatchIn(n)
            ) return true
            return v.features.any {
              val fl = it.lowercase()
              fl.contains("female") || fl.contains("gender=female")
            }
          }

          if (matchingLocaleVoices.isNotEmpty()) {
            val femaleVoices = matchingLocaleVoices.filter { isExplicitlyFemale(it) }
            val selectedVoice = if (femaleVoices.isNotEmpty()) {
              femaleVoices.sortedWith(
                compareByDescending<Voice> { !it.isNetworkConnectionRequired && !it.name.contains("network") }
                  .thenByDescending { it.quality }
              ).firstOrNull() ?: femaleVoices.firstOrNull()
            } else {
              matchingLocaleVoices.filter { !isExplicitlyMale(it) }
                .sortedWith(
                  compareByDescending<Voice> { !it.isNetworkConnectionRequired && !it.name.contains("network") }
                    .thenByDescending { it.quality }
                )
                .firstOrNull() ?: matchingLocaleVoices.firstOrNull()
            }

            if (selectedVoice != null) {
              tts.voice = selectedVoice
              isFemaleSelected = isExplicitlyFemale(selectedVoice)
              isMaleSelected = isExplicitlyMale(selectedVoice)
            }
          }
        }
      }

      // স্পষ্ট ও মিষ্টি স্বাভাবিক নারী কণ্ঠ (মহিলা ভয়েস নিশ্চিত না হলে পিচ সামান্য উঁচু রাখা)
      val targetPitch = when {
        isFemaleSelected -> 1.03f
        isMaleSelected -> 1.22f
        else -> 1.10f
      }
      tts.setPitch(targetPitch)
      tts.setSpeechRate(0.92f)
    } catch (_: Exception) {}

    return VoicePickResult(
      langSupported = langSupported,
      hasMatchingVoice = hasMatchingVoice,
      isFemaleVoice = isFemaleSelected,
      isMaleVoice = isMaleSelected
    )
  }

  val HINDI_STOP_DICTIONARY = mapOf(
    "LABPUR" to "लाभपुर",
    "SURI" to "सिउड़ी",
    "BOLPUR" to "बोलपुर",
    "KIRNAHAR" to "कीर्णहार",
    "SAINTHIA" to "सैंथिया",
    "AHMEDPUR" to "अहमदपुर",
    "AHMADPUR" to "अहमदपुर",
    "AHMADPUR STATION" to "अहमदपुर स्टेशन",
    "AHMEDPUR STATION" to "अहमदपुर स्टेशन",
    "DUBRAJPUR" to "दुबराजपुर",
    "ILLAMBAZAR" to "इलामबाजार",
    "RAMPURHAT" to "रामपुरहाट",
    "NALHATI" to "नलहाटी",
    "MURARAI" to "मुरारई",
    "MALLARPUR" to "मल्लारपुर",
    "MAYURESWAR" to "मयूरेश्वर",
    "PRANTIK" to "प्रांतिक",
    "KANKALITALA" to "कंकालीतला",
    "NANOOR" to "नानूर",
    "GUSHKARA" to "गुसकरा",
    "BARDHAMAN" to "बर्धमान",
    "BURDWAN" to "बर्धमान",
    "DURGAPUR" to "दुर्गापुर",
    "ASANSOL" to "आसनसोल",
    "KOLKATA" to "कोलकाता",
    "CHOWHATTA" to "चौहाट्टा",
    "TETULTALA" to "तेतुलतला",
    "KADAMTALA" to "कदमताला",
    "BUS STAND" to "बस स्टैंड",
    "STATION" to "स्टेशन",
    "BDO OFFICE" to "बीडीओ ऑफिस",
    "BDO OFFICE AHMADPUR" to "बीडीओ ऑफिस अहमदपुर"
  )

  fun toHindiSpokenStopName(raw: String): String {
    val clean = raw.replace(Regex("\\(.*?\\)"), "").replace(Regex("[🚏🚌📍🏁]"), "").trim()
    if (clean.isBlank() || clean == "--") return ""
    val upper = clean.uppercase()
    HINDI_STOP_DICTIONARY[upper]?.let { return it }
    val parts = upper.split(Regex("\\s+"))
    if (parts.size > 1) {
      val mapped = parts.map { p -> HINDI_STOP_DICTIONARY[p] ?: toTitleCaseWord(p) }
      return mapped.joinToString(" ")
    }
    return toTitleCaseWord(clean)
  }

  private fun toTitleCaseWord(input: String): String {
    if (input.isBlank()) return ""
    return input.trim().split(Regex("\\s+")).joinToString(" ") { w ->
      if (w.length <= 1) w.uppercase()
      else w.lowercase().replaceFirstChar { it.uppercase() }
    }
  }

  fun formatTextForTts(text: String, langCode: String = "bn"): String {
    val baseLang = langCode.lowercase().substringBefore("-")
    if (baseLang != "bn") {
      // অন্যান্য ভাষার ক্ষেত্রে বাংলা রূপান্তর করবেন না; কেবল : ও ALL-CAPS স্বাভাবিক করুন
      var clean = text.replace(":", " ")
        .replace(Regex("(?i)\\bB\\.D\\.O\\.?\\b"), "B D O")
        .replace(Regex("(?i)\\bS\\.D\\.O\\.?\\b"), "S D O")
      if (baseLang == "en") {
        clean = Regex("\\b[A-Z]{4,}\\b").replace(clean) { m -> toTitleCaseWord(m.value) }
      } else if (baseLang == "hi") {
        clean = Regex("\\b[A-Za-z]{2,}(?:\\s+[A-Za-z]{2,})*\\b").replace(clean) { m ->
          toHindiSpokenStopName(m.value)
        }
      }
      return clean.replace(Regex("\\s+"), " ").trim()
    }

    var out = text
    // কোলন (:) বাদ দেওয়া যাতে টিটিএস অপ্রয়োজনীয় বিরতি বা অদ্ভুত উচ্চারণ না করে
    out = out.replace(":", " ")

    // সাধারণ পরিচিত শব্দ রূপান্তর
    out = out.replace(Regex("(?i)\\bBDO\\s+OFFICE\\s+AHMADPUR\\b"), "বিডিও অফিস আমেদপুর")
    out = out.replace(Regex("(?i)\\bBDO\\s+OFFICE\\s+AHMEDPUR\\b"), "বিডিও অফিস আমেদপুর")
    out = out.replace(Regex("(?i)\\bBDO\\s+OFFICE\\b"), "বিডিও অফিস")
    out = out.replace(Regex("(?i)\\bBDO\\b"), "বিডিও")
    out = out.replace(Regex("(?i)\\bOFFICE\\b"), "অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\s+OFFICE\\b"), "এসডিও অফিস")
    out = out.replace(Regex("(?i)\\bSDO\\b"), "এসডিও")
    out = out.replace(Regex("(?i)\\bAHMADPUR\\s+STATION\\b"), "আমেদপুর স্টেশন")
    out = out.replace(Regex("(?i)\\bAHMEDPUR\\s+STATION\\b"), "আমেদপুর স্টেশন")
    out = out.replace(Regex("(?i)\\bAHMADPUR\\b"), "আমেদপুর")
    out = out.replace(Regex("(?i)\\bAHMEDPUR\\b"), "আমেদপুর")
    out = out.replace(Regex("(?i)\\bSTATION\\b"), "স্টেশন")
    out = out.replace(Regex("(?i)\\bTETULTALA\\b"), "তেঁতুলতলা")
    out = out.replace(Regex("(?i)\\bKADAMTALA\\b"), "কদমতলা")
    out = out.replace(Regex("(?i)\\bLABPUR\\b"), "লাভপুর")
    out = out.replace(Regex("(?i)\\bCHOWHATTA\\b"), "চৌহাট্টা")
    out = out.replace(Regex("(?i)\\bSAINTHIA\\b"), "সাঁইথিয়া")
    out = out.replace(Regex("(?i)\\bBOLPUR\\b"), "বোলপুর")
    out = out.replace(Regex("(?i)\\bSURI\\b"), "সিউড়ি")

    // আহমদপুর যাতে "অহারুপুর" বা "আহমারপুর" উচ্চারিত না হয়ে স্পষ্ট "আমেদপুর" উচ্চারিত হয়
    out = fixBengaliPronunciation(out)

    // টেক্সটে যদি এখনো কোনো ইংরেজি ক্যাপিটাল শব্দ বা নাম থাকে, তা স্বয়ংক্রিয়ভাবে বাংলায় রূপান্তর করা
    val engRegex = Regex("\\b[A-Za-z]{2,}\\b")
    out = engRegex.replace(out) { matchResult ->
      val word = matchResult.value
      toBengaliSpokenStopName(word)
    }

    return fixBengaliPronunciation(out.replace(Regex("\\s+"), " ").trim())
  }

  private var lastSpokenText: String = ""
  private var lastSpokenTimestamp: Long = 0L

  fun speak(text: String, langCode: String = "bn") {
    if (text.isBlank()) return
    val curLang = if (langCode.isBlank()) "bn" else langCode
    val preparedText = formatTextForTts(text, curLang)

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

    val nativePick = if (isTtsInitialized && textToSpeech != null) {
      setupTtsVoice(curLang)
    } else {
      VoicePickResult()
    }

    // সকল ভাষায় নিশ্চিত নারী কণ্ঠের জন্য:
    // যদি অনলাইন সংযোগ থাকে এবং নেটিভ TTS-এ ওই ভাষার নিশ্চিত নারী কণ্ঠ না থাকে (অথবা ভাষাটি অফলাইনে না থাকে),
    // তবে সরাসরি গুগলের অনলাইন নারী কণ্ঠ (Google Female TTS) ব্যবহার করুন।
    val shouldPreferOnlineFemale = isNetworkAvailable() && (
      !isTtsInitialized ||
        textToSpeech == null ||
        !nativePick.langSupported ||
        !nativePick.hasMatchingVoice ||
        !nativePick.isFemaleVoice
      )

    if (shouldPreferOnlineFemale) {
      playGoogleOnlineFemaleVoice(preparedText, curLang) { success ->
        if (!success) {
          if (isTtsInitialized && textToSpeech != null) {
            speakViaNativeTts(preparedText, curLang, onDoneCallback)
          } else {
            onDoneCallback()
          }
        } else {
          onDoneCallback()
        }
      }
      return
    }

    if (isTtsInitialized && textToSpeech != null) {
      speakViaNativeTts(preparedText, curLang, onDoneCallback)
      return
    }

    onDoneCallback()
  }

  private fun splitTextForTtsChunks(text: String, maxLen: Int = 180): List<String> {
    val clean = text.trim()
    if (clean.length <= maxLen) return listOf(clean)
    val result = mutableListOf<String>()
    val sentences = clean.split(Regex("(?<=[।.!?])\\s+"))
    var cur = StringBuilder()
    for (s in sentences) {
      val trimmed = s.trim()
      if (trimmed.isEmpty()) continue
      if (cur.length + trimmed.length + 1 <= maxLen) {
        if (cur.isNotEmpty()) cur.append(' ')
        cur.append(trimmed)
      } else {
        if (cur.isNotEmpty()) {
          result.add(cur.toString())
          cur = StringBuilder()
        }
        if (trimmed.length <= maxLen) {
          cur.append(trimmed)
        } else {
          val words = trimmed.split(Regex("\\s+"))
          for (w in words) {
            if (cur.length + w.length + 1 <= maxLen) {
              if (cur.isNotEmpty()) cur.append(' ')
              cur.append(w)
            } else {
              if (cur.isNotEmpty()) {
                result.add(cur.toString())
                cur = StringBuilder()
              }
              cur.append(w.take(maxLen))
            }
          }
        }
      }
    }
    if (cur.isNotEmpty()) result.add(cur.toString())
    return if (result.isEmpty()) listOf(clean.take(maxLen)) else result
  }

  private fun resolveGoogleTtsLangCode(langCode: String): String {
    return when (langCode.trim().lowercase()) {
      "en" -> "en-IN"
      "bn" -> "bn"
      "hi" -> "hi"
      "zh-cn" -> "zh-CN"
      "zh-tw" -> "zh-TW"
      "he" -> "iw"
      "fil" -> "tl"
      "jv" -> "jw"
      else -> langCode.trim()
    }
  }

  private fun downloadGoogleTtsAudio(ctx: Context, chunkText: String, targetLang: String): File? {
    var connection: HttpURLConnection? = null
    return try {
      val encodedText = URLEncoder.encode(chunkText, "UTF-8")
      val encodedLang = URLEncoder.encode(targetLang, "UTF-8")
      val urlStr = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encodedText&tl=$encodedLang&client=tw-ob"
      connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 4500
        readTimeout = 4500
        setRequestProperty(
          "User-Agent",
          "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        setRequestProperty("Referer", "https://translate.google.com/")
      }
      if (connection.responseCode != 200) return null
      val cacheFile = File(ctx.cacheDir, "tts_voice_${System.currentTimeMillis()}_${chunkText.hashCode()}.mp3")
      connection.inputStream.use { input ->
        cacheFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      if (cacheFile.exists() && cacheFile.length() > 300) cacheFile else {
        try { cacheFile.delete() } catch (_: Exception) {}
        null
      }
    } catch (_: Exception) {
      null
    } finally {
      try { connection?.disconnect() } catch (_: Exception) {}
    }
  }

  private fun playGoogleOnlineFemaleVoice(text: String, langCode: String, onComplete: (Boolean) -> Unit) {
    val ctx = appContext ?: run {
      onComplete(false)
      return
    }

    val targetLang = resolveGoogleTtsLangCode(langCode)
    val chunks = splitTextForTtsChunks(text, 180)

    ioScope.launch {
      try {
        val files = chunks.mapNotNull { ch -> downloadGoogleTtsAudio(ctx, ch, targetLang) }
        if (files.isEmpty()) {
          withContext(Dispatchers.Main) { onComplete(false) }
          return@launch
        }

        withContext(Dispatchers.Main) {
          fun playIndex(idx: Int) {
            if (idx >= files.size) {
              onComplete(true)
              return
            }
            playAudioFile(files[idx]) { ok ->
              if (ok) {
                playIndex(idx + 1)
              } else {
                for (remIdx in idx + 1 until files.size) {
                  try { files[remIdx].delete() } catch (_: Exception) {}
                }
                onComplete(idx > 0)
              }
            }
          }
          playIndex(0)
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
    val norm = langCode.trim().lowercase()
    return when (norm) {
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
      "as" -> Locale("as", "IN")
      "ur" -> Locale("ur", "PK")
      "ne" -> Locale("ne", "NP")
      "si" -> Locale("si", "LK")
      else -> Locale.forLanguageTag(langCode)
    }
  }
}
