from pathlib import Path


class SemanticMemory:
    """Local reviewed business definitions; no model-written or user-shared memory."""
    def __init__(self):
        path = Path(__file__).resolve().parents[1] / 'knowledge' / 'sales.md'
        self._facts = {'sales': {'topic': 'sales', 'source': 'knowledge/sales.md',
                                 'definitions': path.read_text(encoding='utf-8')}}

    def recall(self, topic='sales') -> dict:
        return dict(self._facts[topic])
