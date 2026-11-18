package com.example.retrieval

data class RetrievalTriggerClassification(
    val decision: RetrievalDecisionCategory,
    val freshnessPolicy: RetrievalFreshnessPolicy,
    val normalizedQuery: String,
    val reason: String
)

/**
 * Deterministic gate that ensures ordinary small talk, acknowledgments, timeless questions,
 * device commands, and private-memory probes NEVER trigger a blocking public search request.
 */
object RetrievalTriggerClassifier {

    private val exactSmallTalkSet = setOf(
        "hi", "hello", "hey", "hiya", "yo", "sup",
        "good morning", "good afternoon", "good evening", "good night",
        "how are you", "how r u", "whats up", "what's up",
        "thanks", "thank you", "thx", "ok", "okay", "sure", "alright", "cool", "nice",
        "yes", "no", "yeah", "nope", "yep", "stop", "pause", "resume", "cancel",
        "haan", "han", "nahi", "nahin", "ji", "achha", "accha", "theek hai", "thik hai",
        "shukriya", "meharbani", "assalam o alaikum", "assalamualaikum", "salam",
        "kaise ho", "kya haal hai", "sab theek", "hello omnivoice", "hi omnivoice",
        "ہاں", "نہیں", "جی", "اچھا", "ٹھیک ہے", "شکریہ", "سلام", "کیسے ہو"
    )

    private val smallTalkPhraseRegex = Regex(
        "^(hi|hello|hey|good morning|good evening|how are you|thank you|thanks|tell me a joke|who are you|what is your name|kaise ho|kya haal hai|shukriya|salam)[\\s!?.]*$"
    )

    private val deviceCommandRegex = Regex(
        "\\b(open|launch|call|dial|text|sms|whatsapp|send message|send email|set alarm|set timer|remind me|reminder|turn on|turn off|enable|disable|mute|unmute|volume|brightness|flashlight|bluetooth|wifi|lock screen|unlock|play music|pause|stop listening)\\b"
    )

    private val privateMemoryOrSecretRegex = Regex(
        "\\b(my contacts|my messages|my sms|my emails|my inbox|my calendar|my schedule|my notes|my photos|my files|my location|private memory|system prompt|system instruction|developer prompt|api key|app check|resumption handle|access token|bearer token)\\b"
    )

    private val breakingLiveSignalsRegex = Regex(
        "\\b(news|headlines|breaking|latest|today|tonight|right now|currently|live|weather|forecast|temperature|rain|score|match|won|winner|stock|share price|bitcoin|crypto|exchange rate|dollar rate|gold price|election|earthquake|traffic|flight status|aaj|abhi|taza|khabar|khabrein|mausam|barish|qeemat|rate|jeeta)\\b"
    )

    private val changingFactSignalsRegex = Regex(
        "\\b(this week|this month|this year|recent|recently|update|updates|schedule|release date|standings|results|who is the current|president|prime minister|ceo|chair|2025|2026|2027|kal|yesterday|tomorrow|kya hua)\\b"
    )

    private val urduLiveSignals = listOf(
        "آج", "ابھی", "تازہ", "خبر", "خبریں", "موسم", "بارش", "میچ", "اسکور", "قیمت", "ریٹ", "کرکٹ", "جیتا"
    )

    fun classify(rawQueryOrUtterance: String): RetrievalTriggerClassification {
        val collapsed = rawQueryOrUtterance
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalized = collapsed.lowercase()
            .replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "")
            .trim()

        if (normalized.isBlank() || normalized.length < 2) {
            return RetrievalTriggerClassification(
                decision = RetrievalDecisionCategory.SKIP_EMPTY_OR_UNCLEAR,
                freshnessPolicy = RetrievalFreshnessPolicy.CHANGING_FACTS,
                normalizedQuery = "",
                reason = "Empty or unclear utterance requires no search."
            )
        }

        if (exactSmallTalkSet.contains(normalized) || smallTalkPhraseRegex.matches(normalized)) {
            return RetrievalTriggerClassification(
                decision = RetrievalDecisionCategory.SKIP_SMALL_TALK,
                freshnessPolicy = RetrievalFreshnessPolicy.CHANGING_FACTS,
                normalizedQuery = normalized,
                reason = "Ordinary greeting/small talk/acknowledgment requires no web retrieval."
            )
        }

        if (privateMemoryOrSecretRegex.containsMatchIn(normalized)) {
            return RetrievalTriggerClassification(
                decision = RetrievalDecisionCategory.SKIP_PRIVATE_MEMORY_OR_SECRETS,
                freshnessPolicy = RetrievalFreshnessPolicy.CHANGING_FACTS,
                normalizedQuery = normalized,
                reason = "Private user memory, personal data, or internal credentials are strictly excluded from public search."
            )
        }

        if (deviceCommandRegex.containsMatchIn(normalized) &&
            !breakingLiveSignalsRegex.containsMatchIn(normalized)
        ) {
            return RetrievalTriggerClassification(
                decision = RetrievalDecisionCategory.SKIP_DEVICE_COMMAND,
                freshnessPolicy = RetrievalFreshnessPolicy.CHANGING_FACTS,
                normalizedQuery = normalized,
                reason = "Device control or action utterance does not trigger public web retrieval."
            )
        }

        val hasUrduLive = urduLiveSignals.any { normalized.contains(it) }
        if (hasUrduLive || breakingLiveSignalsRegex.containsMatchIn(normalized)) {
            return RetrievalTriggerClassification(
                decision = RetrievalDecisionCategory.REQUIRES_CURRENT_PUBLIC_INFO,
                freshnessPolicy = RetrievalFreshnessPolicy.BREAKING_LIVE,
                normalizedQuery = normalized,
                reason = "Query asks for time-sensitive breaking/live public information."
            )
        }

        if (changingFactSignalsRegex.containsMatchIn(normalized)) {
            return RetrievalTriggerClassification(
                decision = RetrievalDecisionCategory.REQUIRES_CURRENT_PUBLIC_INFO,
                freshnessPolicy = RetrievalFreshnessPolicy.CHANGING_FACTS,
                normalizedQuery = normalized,
                reason = "Query asks for changing current public facts."
            )
        }

        return RetrievalTriggerClassification(
            decision = RetrievalDecisionCategory.SKIP_TIMELESS_KNOWLEDGE,
            freshnessPolicy = RetrievalFreshnessPolicy.CHANGING_FACTS,
            normalizedQuery = normalized,
            reason = "General/timeless question handled directly by native conversation without blocking search."
        )
    }
}
