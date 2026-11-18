package com.example.retrieval

import com.example.live.DiagnosticRedactor
import java.net.URI

data class SanitizedTextResult(
    val sanitizedText: String,
    val strippedInjectionCount: Int,
    val flaggedPatterns: List<String>,
    val isFullyBlockedHostile: Boolean
)

/**
 * Enforces an untrusted-data security boundary over all retrieved public web pages,
 * snippets, titles, search suggestion HTML, and source URLs.
 *
 * Retrieved pages can NEVER:
 * - authorize or invoke device actions;
 * - reveal private user memory, contacts, or credentials;
 * - override or modify system/developer instructions.
 */
object UntrustedContentSanitizer {

    private val bidiAndControlCharsRegex = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val htmlScriptBlockRegex = Regex("(?is)<script[^>]*>.*?</script>")
    private val htmlStyleBlockRegex = Regex("(?is)<style[^>]*>.*?</style>")
    private val htmlTagRegex = Regex("(?is)<[^>]+>")
    private val dangerousMarkdownLinkRegex = Regex("(?i)\\[([^\\]]+)]\\(\\s*(?:javascript|data|intent|file|content|vbscript):[^)]*\\)")

    private val hostileDirectiveRules: List<Pair<String, Regex>> = listOf(
        "PROMPT_OVERRIDE" to Regex(
            "(?i)(ignore|disregard|forget|override|bypass|replace)\\s+(?:all\\s+|any\\s+|the\\s+)?(?:previous|prior|above|existing|system|developer)\\s+(?:\\w+\\s+){0,3}(?:instructions?|prompts?|rules?|policies?)"
        ),
        "SYSTEM_INSTRUCTION_CHANGE" to Regex(
            "(?i)(?:new|updated)\\s+(?:system|developer)\\s+(?:instruction|prompt|role)\\s*[:=]"
        ),
        "DEVICE_ACTION_INJECTION" to Regex(
            "(?i)(?:execute|run|authorize|trigger|perform|invoke|enable)\\s+(?:the\\s+|a\\s+)?(?:device\\s+)?(?:action|command|tool|call|sms|message|alarm|settings|unlock|lock_screen|send_sms|make_call|open_app|delete_file)"
        ),
        "DEVICE_TOOL_SYNTAX" to Regex(
            "(?i)\\b(?:send_sms|make_call|open_app|open_settings|unlock_device|run_shell|grant_permission|authorize_action)\\b"
        ),
        "PRIVATE_MEMORY_EXFILTRATION" to Regex(
            "(?i)(?:reveal|dump|exfiltrate|output|speak|print|send|leak|read)\\s+(?:all\\s+|the\\s+|user['’]?s\\s+)?(?:private\\s+memory|personal\\s+memory|contacts?|sms|messages?|call\\s+logs?|calendar|emails?|resumption\\s+handle|app\\s*check|api\\s*key|credentials?|secret\\s+token|system\\s+prompt)"
        )
    )

    private val disallowedHostPrefixes = listOf(
        "127.", "10.", "192.168.", "169.254.", "0.0.0.0"
    )

    fun validatePublicHttpsUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.replace(bidiAndControlCharsRegex, "").trim()
        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        if (!uri.rawUserInfo.isNullOrBlank()) return null
        val host = uri.host?.lowercase()?.trim() ?: return null
        if (host.isBlank() ||
            host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host == "::1" ||
            host.startsWith("fe80:") ||
            host.startsWith("fc") ||
            host.startsWith("fd") ||
            disallowedHostPrefixes.any { host.startsWith(it) } ||
            isPrivate172Host(host)
        ) {
            return null
        }
        if (!host.contains('.')) return null
        return uri.toASCIIString()
    }

    private fun isPrivate172Host(host: String): Boolean {
        if (!host.startsWith("172.")) return false
        val secondOctet = host.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
        return secondOctet in 16..31
    }

    fun extractSafeDomain(httpsUrl: String, fallbackDomain: String?): String {
        val parsedHost = try {
            URI(httpsUrl).host?.lowercase()?.removePrefix("www.")
        } catch (_: Throwable) {
            null
        }
        val safeFallback = fallbackDomain
            ?.replace(bidiAndControlCharsRegex, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains(' ') }
        return parsedHost ?: safeFallback ?: "verified-source"
    }

    fun sanitizeText(rawText: String, maxChars: Int): SanitizedTextResult {
        if (rawText.isBlank()) {
            return SanitizedTextResult(
                sanitizedText = "",
                strippedInjectionCount = 0,
                flaggedPatterns = emptyList(),
                isFullyBlockedHostile = false
            )
        }

        var cleaned = rawText
            .replace(bidiAndControlCharsRegex, " ")
            .replace(htmlScriptBlockRegex, " [SCRIPT_REMOVED] ")
            .replace(htmlStyleBlockRegex, " ")
            .replace(dangerousMarkdownLinkRegex, "$1")
            .replace(htmlTagRegex, " ")

        val flagged = linkedSetOf<String>()
        var strippedCount = 0

        val segments = cleaned.split(Regex("(?<=[.!?\\n;])\\s+|\\n+"))
        val safeSegments = mutableListOf<String>()
        for (segment in segments) {
            val trimmedSeg = segment.trim()
            if (trimmedSeg.isEmpty()) continue
            var segmentHostile = false
            for ((code, regex) in hostileDirectiveRules) {
                if (regex.containsMatchIn(trimmedSeg)) {
                    flagged.add(code)
                    strippedCount += 1
                    segmentHostile = true
                }
            }
            if (!segmentHostile) {
                safeSegments.add(trimmedSeg)
            }
        }

        cleaned = safeSegments.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val redacted = if (cleaned.isBlank()) {
            ""
        } else {
            DiagnosticRedactor.redact(cleaned)
                .takeIf { it != "NONE" }
                .orEmpty()
                .take(maxChars)
                .trim()
        }

        val fullyHostile = strippedCount > 0 && redacted.isBlank()
        return SanitizedTextResult(
            sanitizedText = redacted,
            strippedInjectionCount = strippedCount,
            flaggedPatterns = flagged.toList(),
            isFullyBlockedHostile = fullyHostile
        )
    }

    fun sanitizeSources(
        rawSources: List<RawDriverSource>,
        maxSources: Int
    ): Triple<List<PublicSourceLink>, Int, HostileContentSanitizationReport> {
        val accepted = mutableListOf<PublicSourceLink>()
        val seenUrls = linkedSetOf<String>()
        var blockedUrls = 0
        var strippedInjections = 0
        val flagged = linkedSetOf<String>()

        for (raw in rawSources) {
            val safeUrl = validatePublicHttpsUrl(raw.uri)
            if (safeUrl == null) {
                blockedUrls += 1
                flagged.add("UNSAFE_URL_SCHEME_OR_HOST")
                continue
            }
            if (!seenUrls.add(safeUrl)) continue

            val titleCheck = sanitizeText(raw.title, maxChars = 90)
            strippedInjections += titleCheck.strippedInjectionCount
            flagged.addAll(titleCheck.flaggedPatterns)

            val snippetCheck = raw.snippet?.let { sanitizeText(it, maxChars = 160) }
            if (snippetCheck != null) {
                strippedInjections += snippetCheck.strippedInjectionCount
                flagged.addAll(snippetCheck.flaggedPatterns)
            }

            if (titleCheck.isFullyBlockedHostile && snippetCheck?.isFullyBlockedHostile == true) {
                blockedUrls += 1
                continue
            }

            val domain = extractSafeDomain(safeUrl, raw.domain)
            val safeTitle = titleCheck.sanitizedText.ifBlank { domain }
            accepted.add(
                PublicSourceLink(
                    title = safeTitle,
                    url = safeUrl,
                    domain = domain,
                    snippet = snippetCheck?.sanitizedText?.takeIf { it.isNotBlank() }
                )
            )
            if (accepted.size >= maxSources) break
        }

        val report = HostileContentSanitizationReport(
            blockedThreatCount = blockedUrls + strippedInjections,
            blockedUrlsCount = blockedUrls,
            strippedPromptInjectionCount = strippedInjections,
            flaggedPatterns = flagged.toList()
        )
        return Triple(accepted, blockedUrls, report)
    }

    /**
     * Strips any executable scripts or inline JS event handlers from Google Search suggestion HTML
     * before optional sandboxed WebView rendering.
     */
    fun sanitizeSearchEntryPointHtml(rawHtml: String?): String? {
        if (rawHtml.isNullOrBlank()) return null
        val sanitized = rawHtml
            .replace(htmlScriptBlockRegex, "")
            .replace(Regex("(?is)<(iframe|object|embed|meta)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?is)<(iframe|object|embed|meta)[^>]*>"), "")
            .replace(Regex("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
            .replace(Regex("(?i)javascript\\s*:"), "https:")
            .trim()
        return sanitized.takeIf { it.isNotBlank() }
    }
}
