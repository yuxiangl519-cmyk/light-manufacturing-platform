from dataclasses import dataclass, field


@dataclass
class EpisodicMemory:
    """Bounded request-local execution facts, containing no gateway credentials."""
    episodes: list[dict] = field(default_factory=list)

    def record(self, node: str, outcome: str):
        self.episodes.append({'node': node, 'outcome': outcome})
        self.episodes[:] = self.episodes[-24:]

    def recall(self) -> tuple[dict, ...]:
        return tuple(dict(episode) for episode in self.episodes)
