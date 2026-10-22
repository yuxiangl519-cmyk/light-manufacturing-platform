"""Text-to-SQL MVP maps model intent to approved template IDs, never SQL strings."""
from .tools import validate_call


class TemplateTextSQL:
    def validate_plan(self, plan: list[dict], period: dict) -> list[dict]:
        return [{'name': call['name'], 'arguments': validate_call(call['name'], call['arguments'], period)}
                for call in plan]
