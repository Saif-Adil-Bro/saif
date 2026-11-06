package com.saif.mosque.util

import com.saif.mosque.data.model.Donor

data class VoiceExtractedItem(
    val rawName: String,
    val amount: Double,
    val matchedDonorId: String? = null,
    val matchedDonorName: String? = null
)

object VoiceLedgerParser {

    private val BENGALI_DIGITS = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    private val WORD_REPLACEMENTS = listOf(
        Regex("""\bদেড়\s*হাজার\b""", RegexOption.IGNORE_CASE) to "1500",
        Regex("""\bএক\s*হাজার\b""", RegexOption.IGNORE_CASE) to "1000",
        Regex("""\bদুই\s*হাজার\b""", RegexOption.IGNORE_CASE) to "2000",
        Regex("""\bতিন\s*হাজার\b""", RegexOption.IGNORE_CASE) to "3000",
        Regex("""\bচার\s*হাজার\b""", RegexOption.IGNORE_CASE) to "4000",
        Regex("""\bপাঁচ\s*হাজার\b""", RegexOption.IGNORE_CASE) to "5000",
        Regex("""\bহাজার\b""", RegexOption.IGNORE_CASE) to "1000",
        Regex("""\bআড়াই\s*শো\b""", RegexOption.IGNORE_CASE) to "250",
        Regex("""\bদেড়\s*শো\b""", RegexOption.IGNORE_CASE) to "150",
        Regex("""\bএক\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "100",
        Regex("""\bদুই\s*(?:শত|শো)|দুশো\b""", RegexOption.IGNORE_CASE) to "200",
        Regex("""\bতিন\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "300",
        Regex("""\bচার\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "400",
        Regex("""\bপাঁচ\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "500",
        Regex("""\bছয়\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "600",
        Regex("""\bসাত\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "700",
        Regex("""\bআট\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "800",
        Regex("""\bনয়\s*(?:শত|শো)\b""", RegexOption.IGNORE_CASE) to "900",
        Regex("""\bপঞ্চাশ\b""", RegexOption.IGNORE_CASE) to "50"
    )

    private val EXTRACTION_REGEX = Regex(
        """([^\d\n,;]+?)\s*[:=\-]?\s*(\d+(?:\.\d+)?)\s*(?:টাকা|টাকার|টাকায়|tk|taka)?(?=[\n,;]| এবং| ও|\s+[^\d\s]|\s*$)""",
        RegexOption.IGNORE_CASE
    )

    fun normalizeBengaliDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(BENGALI_DIGITS[c] ?: c)
        }
        return sb.toString()
    }

    fun preprocess(text: String): String {
        var normalized = normalizeBengaliDigits(text)
        for ((regex, replacement) in WORD_REPLACEMENTS) {
            normalized = normalized.replace(regex, replacement)
        }
        return normalized
    }

    private val COMMON_TITLES = setOf(
        "মোঃ", "মো:", "মো", "মুহাম্মদ", "মোহাম্মদ", "মাওলানা", "মওলানা", "হাফেজ", "মুফতি",
        "আলহাজ্ব", "আলহাজ", "হাজী", "হাজি", "শেখ", "সৈয়দ", "সৈয়দ", "কাজী", "মুন্সী",
        "মিয়া", "মিয়া", "ভাই", "চৌধুরী", "খান", "আহমেদ", "হোসেন", "আলী", "রহমান",
        "ইসলাম", "মোল্লা", "সরদার", "md", "md.", "mohammad", "muhammad", "mr", "mr."
    )

    fun parse(speechText: String, availableDonors: List<Donor> = emptyList()): List<VoiceExtractedItem> {
        if (speechText.isBlank()) return emptyList()
        val preprocessed = preprocess(speechText)
        val matches = EXTRACTION_REGEX.findAll(preprocessed)
        val list = mutableListOf<VoiceExtractedItem>()
        val usedDonorIds = mutableSetOf<String>()

        for (m in matches) {
            var rawName = m.groupValues[1].trim()
            rawName = rawName.replaceFirst(Regex("""^(?:এবং|ও|,|and)\s*"""), "").trim()
            rawName = rawName.replace(Regex("""^[:\-—,;\s]+|[:\-—,;\s]+$"""), "").trim()
            val amount = m.groupValues[2].toDoubleOrNull() ?: continue
            if (rawName.isBlank() || amount <= 0.0) continue

            val matchedDonor = findBestMatchingDonor(rawName, availableDonors, usedDonorIds)
            if (matchedDonor != null && matchedDonor.id.isNotBlank()) {
                usedDonorIds.add(matchedDonor.id)
            }
            list.add(
                VoiceExtractedItem(
                    rawName = rawName,
                    amount = amount,
                    matchedDonorId = matchedDonor?.id,
                    matchedDonorName = matchedDonor?.name
                )
            )
        }
        return list
    }

    fun findBestMatchingDonor(name: String, donors: List<Donor>, excludeIds: Set<String> = emptySet()): Donor? {
        if (donors.isEmpty() || name.isBlank()) return null
        val candidates = if (excludeIds.isEmpty()) donors else donors.filter { it.id.isBlank() || it.id !in excludeIds }
        if (candidates.isEmpty()) return null

        val cleanQuery = name.trim().lowercase()

        // 1. Exact match (case-insensitive)
        candidates.find { it.name.trim().equals(cleanQuery, ignoreCase = true) }?.let { return it }

        // 2. Donor name contains exact query (if query has meaningful length)
        if (cleanQuery.length >= 3) {
            candidates.find { it.name.trim().lowercase().contains(cleanQuery) }?.let { return it }
        }

        // 3. Query contains donor name (if donor name has meaningful length)
        candidates.find {
            val dName = it.name.trim().lowercase()
            dName.length >= 3 && cleanQuery.contains(dName)
        }?.let { return it }

        // 4. Distinctive Token overlap (ignoring common titles like মোঃ, মাওলানা, রহমান etc.)
        val queryTokens = cleanQuery.split("\\s+".toRegex())
            .map { it.trim().trim(':', '.', ',') }
            .filter { it.length > 2 && it !in COMMON_TITLES }

        if (queryTokens.isNotEmpty()) {
            for (donor in candidates) {
                val donorTokens = donor.name.trim().lowercase().split("\\s+".toRegex())
                    .map { it.trim().trim(':', '.', ',') }
                    .filter { it.length > 2 && it !in COMMON_TITLES }

                if (queryTokens.any { q -> donorTokens.contains(q) }) {
                    return donor
                }
            }
        }

        return null
    }
}
