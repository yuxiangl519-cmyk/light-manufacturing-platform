from typing import Protocol


class LongTermMemory(Protocol):
    """Java-owned persistence port. Implementations must validate opaque gateway capability.

    No Python global store is installed: successful period context and clarification
    context are persisted by Java alongside the authorized conversation.
    """
    async def recall(self, conversation_id: str, gateway_token: str) -> dict: ...
    async def remember(self, conversation_id: str, gateway_token: str, context: dict) -> None: ...
