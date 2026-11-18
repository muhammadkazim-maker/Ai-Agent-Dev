package com.example.retrieval

import java.util.LinkedHashMap

/**
 * Bounded LRU cache for verified public-information retrieval results with explicit
 * timestamps and freshness TTL enforcement.
 *
 * Stale / expired cache entries are NEVER silently returned as current news.
 */
class PublicRetrievalCache(
    private val maxEntries: Int = 24
) {
    private data class CacheEntry(
        val attachment: PublicRetrievalAttachment,
        val fetchedMonotonicMs: Long,
        val policy: RetrievalFreshnessPolicy
    )

    sealed interface LookupResult {
        data object Miss : LookupResult
        data class FreshHit(
            val attachment: PublicRetrievalAttachment,
            val ageMs: Long
        ) : LookupResult

        data class ExpiredRejected(
            val ageMs: Long,
            val maxTtlMs: Long
        ) : LookupResult
    }

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > maxEntries
    }

    var expiredRejectedCount: Int = 0
        private set

    fun putFresh(
        normalizedQuery: String,
        policy: RetrievalFreshnessPolicy,
        fetchedMonotonicMs: Long,
        attachment: PublicRetrievalAttachment
    ) {
        if (normalizedQuery.isBlank() ||
            attachment.sources.isEmpty() ||
            attachment.status != RetrievalVerificationStatus.GROUNDED_CURRENT_WEB
        ) {
            return
        }
        synchronized(lock) {
            entries[cacheKey(normalizedQuery, policy)] = CacheEntry(
                attachment = attachment,
                fetchedMonotonicMs = fetchedMonotonicMs,
                policy = policy
            )
        }
    }

    fun lookup(
        normalizedQuery: String,
        policy: RetrievalFreshnessPolicy,
        nowMonotonicMs: Long
    ): LookupResult {
        if (normalizedQuery.isBlank()) return LookupResult.Miss
        val key = cacheKey(normalizedQuery, policy)
        synchronized(lock) {
            val entry = entries[key] ?: return LookupResult.Miss
            val ageMs = (nowMonotonicMs - entry.fetchedMonotonicMs).coerceAtLeast(0L)
            if (ageMs <= entry.policy.ttlMs) {
                val cachedAttachment = entry.attachment.copy(
                    status = RetrievalVerificationStatus.GROUNDED_FRESH_CACHE,
                    fromFreshCache = true,
                    expiredCacheRejected = false
                )
                return LookupResult.FreshHit(
                    attachment = cachedAttachment,
                    ageMs = ageMs
                )
            }
            entries.remove(key)
            expiredRejectedCount += 1
            return LookupResult.ExpiredRejected(
                ageMs = ageMs,
                maxTtlMs = entry.policy.ttlMs
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            expiredRejectedCount = 0
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun cacheKey(normalizedQuery: String, policy: RetrievalFreshnessPolicy): String =
        "${policy.name}::$normalizedQuery"
}
