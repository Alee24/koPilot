package ke.co.kopilot.core.security

object SensitiveLogRedactor {
    private val patterns = listOf(
        Regex("""(Bearer\s+)[A-Za-z0-9\-._~+/]+=*""", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
        Regex("""\b(sk-[A-Za-z0-9_\-]{10,})""") to "[REDACTED_OPENAI_KEY]",
        Regex("""\b(ek_[A-Za-z0-9_\-]{10,})""") to "[REDACTED_EPHEMERAL_KEY]",
        Regex("""\b(AIza[0-9A-Za-z\-_]{20,})""") to "[REDACTED_GOOGLE_KEY]",
    )

    fun redact(input: String): String {
        var out = input
        for ((regex, replacement) in patterns) {
            out = regex.replace(out, replacement)
        }
        return out
    }
}
