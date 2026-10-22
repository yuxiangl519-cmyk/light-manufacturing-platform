"""Minimal prompt data minimization, not a general enterprise DLP system."""
import re

def redact_question(text: str) -> str:
    text=re.sub(r'\bsk-[A-Za-z0-9_-]{12,}', '[REDACTED_KEY]', text)
    text=re.sub(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '[REDACTED_EMAIL]', text)
    return re.sub(r'(?<!\d)1[3-9]\d{9}(?!\d)', '[REDACTED_PHONE]', text)
