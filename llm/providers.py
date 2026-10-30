"""CLIs that can drive a GUI seat; each keeps authentication inside its own CLI or isolated sign-in."""

from collections.abc import Callable
from dataclasses import dataclass

from llm import claude_client, model_catalog, opencode_client, openrouter_client, subscription_client, zai_client


@dataclass(frozen=True)
class Provider:
    id: str
    label: str
    auth: str
    events_file: str
    stderr_file: str
    discover: Callable
    validate_selection: Callable
    start: Callable
    require_game_only_events: Callable
    usage: Callable
    # The CLI's own explanation when a game stops early, or None when the trace has none.
    failure_reason: Callable = lambda _events: None


def codex_usage(events):
    return [event["usage"] for event in events if event.get("type") == "turn.completed"]


def codex_failure_reason(events):
    """Codex's message for a failed turn, such as an overloaded model or a reached usage limit."""
    for event in reversed(events):
        if event.get("type") == "turn.failed":
            message = (event.get("error") or {}).get("message")
            if message:
                return message
        if event.get("type") == "error" and event.get("message"):
            return event["message"]
    return None


# A plan's allowance does not come back within a game; a busy model or a dropped connection does.
LASTING_LIMITS = ("usage limit", "session limit", "weekly limit", "per-day", "quota", "insufficient", "allowance")


def lasting_limit(reason):
    return reason is not None and any(words in reason.lower() for words in LASTING_LIMITS)


# The provider has no room for this model right now; another of its models may still play.
AT_CAPACITY = ("at capacity", "overloaded", "try a different model")


def at_capacity(reason):
    return reason is not None and any(words in reason.lower() for words in AT_CAPACITY)


def codex_game_only_events(events):
    for event in events:
        if event.get("type") == "item.completed" and event["item"].get("type") in {
            "command_execution",
            "file_change",
            "web_search",
        }:
            raise RuntimeError("Information boundary violation: the player used a non-game tool")


PROVIDERS = {
    item.id: item
    for item in (
        Provider(
            id="chatgpt_subscription",
            label="ChatGPT",
            auth="existing Codex ChatGPT login",
            events_file="codex-events.jsonl",
            stderr_file="codex-stderr.log",
            discover=model_catalog.discover,
            validate_selection=model_catalog.validate_selection,
            start=subscription_client.start,
            require_game_only_events=codex_game_only_events,
            usage=codex_usage,
            failure_reason=codex_failure_reason,
        ),
        Provider(
            id=claude_client.PROVIDER,
            label="Claude",
            auth="existing Claude Code subscription login",
            events_file="claude-events.jsonl",
            stderr_file="claude-stderr.log",
            discover=claude_client.discover,
            validate_selection=claude_client.validate_selection,
            start=claude_client.start,
            require_game_only_events=claude_client.require_game_only_events,
            usage=claude_client.usage,
            failure_reason=claude_client.failure_reason,
        ),
        Provider(
            id=openrouter_client.PROVIDER,
            label="OpenRouter",
            auth="OpenRouter key in the isolated OpenCode sign-in",
            events_file="opencode-events.jsonl",
            stderr_file="opencode-stderr.log",
            discover=openrouter_client.discover,
            validate_selection=openrouter_client.validate_selection,
            start=openrouter_client.start,
            require_game_only_events=opencode_client.require_game_only_events,
            usage=opencode_client.usage,
            failure_reason=openrouter_client.failure_reason,
        ),
        Provider(
            id=zai_client.PROVIDER,
            label="GLM",
            auth="Z.ai GLM Coding Plan key in the isolated OpenCode sign-in",
            events_file="opencode-zai-events.jsonl",
            stderr_file="opencode-zai-stderr.log",
            discover=zai_client.discover,
            validate_selection=zai_client.validate_selection,
            start=zai_client.start,
            require_game_only_events=opencode_client.require_game_only_events,
            usage=opencode_client.usage,
            failure_reason=zai_client.failure_reason,
        ),
    )
}


def get(provider_id):
    if provider_id not in PROVIDERS:
        raise ValueError(f"Unknown subscription provider: {provider_id}")
    return PROVIDERS[provider_id]


def discover_all(providers=None):
    """Return every signed-in provider's catalog; one missing sign-in does not block the others."""
    available, unavailable = [], []
    for provider in (providers or PROVIDERS).values():
        try:
            available.append(provider.discover())
        except Exception as error:  # noqa: BLE001 - reported with the catalog, never fatal per provider
            unavailable.append({"id": provider.id, "reason": str(error)})
    if not available:
        raise RuntimeError("No subscription player is available: " + "; ".join(item["reason"] for item in unavailable))
    return {"providers": available, "unavailable": unavailable}


def catalog_entry(catalog, provider_id):
    for entry in catalog["providers"]:
        if entry["id"] == provider_id:
            return entry
    raise ValueError(f"{provider_id} is not signed in; refresh the model list after signing in")
