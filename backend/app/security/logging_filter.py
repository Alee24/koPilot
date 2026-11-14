import logging
import re

_PATTERNS = [
    (re.compile(r"(Bearer\s+)[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE), r"\1[REDACTED]"),
    (re.compile(r"\b(sk-[A-Za-z0-9_\-]{10,})"), "[REDACTED_OPENAI_KEY]"),
    (re.compile(r"\b(ek_[A-Za-z0-9_\-]{10,})"), "[REDACTED_EPHEMERAL_KEY]"),
    (re.compile(r"\b(AIza[0-9A-Za-z\-_]{20,})"), "[REDACTED_GOOGLE_KEY]"),
    (
        re.compile(r'(["\']?(?:password|secret|token|api_key|authorization)["\']?\s*[:=]\s*["\']?)([^"\'\s,}]+)', re.IGNORECASE),
        r"\1[REDACTED]",
    ),
]


def redact_sensitive_text(text: str) -> str:
    """Redact API keys, tokens, and credentials from log strings."""
    result = text
    for pattern, replacement in _PATTERNS:
        result = pattern.sub(replacement, result)
    return result


class SensitiveDataFilter(logging.Filter):
    """Logging filter that strips secrets before log emission."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = redact_sensitive_text(record.msg)
        if record.args:
            if isinstance(record.args, tuple):
                record.args = tuple(
                    redact_sensitive_text(a) if isinstance(a, str) else a
                    for a in record.args
                )
            elif isinstance(record.args, dict):
                record.args = {
                    k: redact_sensitive_text(v) if isinstance(v, str) else v
                    for k, v in record.args.items()
                }
        return True
