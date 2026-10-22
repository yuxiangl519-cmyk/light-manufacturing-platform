"""Per-request working memory; Java owns durable conversation storage.

No global conversation cache, vector store, or imaginary checkpoint persistence.
Only the explicitly allowed clarification fields cross request boundaries.
"""
from dataclasses import dataclass, field


@dataclass
class WorkingMemory:
    message: str
    resume: dict = field(default_factory=dict)

    def short_term_context(self):
        return {key: self.resume[key] for key in ('question', 'originalMessage', 'startDate', 'endDate', 'metric')
                if key in self.resume and isinstance(self.resume[key], str)}

    def effective_message(self):
        original = self.short_term_context().get('originalMessage', '')
        return f'{original}\n补充：{self.message}' if original else self.message
