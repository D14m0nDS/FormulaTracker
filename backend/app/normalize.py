from datetime import datetime, timezone

from app.models import Circuit, Event, SeasonSchedule, Session


class ProviderPayloadError(Exception):
    """Jolpica returned JSON that is not a usable season schedule."""


def normalize_schedule(payload: dict) -> SeasonSchedule:
    data = payload.get("data")
    if not isinstance(data, dict):
        raise ProviderPayloadError("schedule payload has no data object")

    year = data.get("year")
    if not isinstance(year, int):
        raise ProviderPayloadError("schedule payload has no season year")

    raw_events = data.get("events")
    if not isinstance(raw_events, list):
        raise ProviderPayloadError("schedule payload has no events list")

    events = [event for raw in raw_events if (event := _event(raw)) is not None]
    events.sort(key=lambda event: event.round)
    return SeasonSchedule(season=year, events=events)


def _event(raw: object) -> Event | None:
    if not isinstance(raw, dict):
        return None

    round_info = raw.get("round")
    if not isinstance(round_info, dict):
        return None

    number = round_info.get("number")
    name = round_info.get("name")
    if not isinstance(number, int) or not isinstance(name, str) or not name:
        return None

    round_cancelled = round_info.get("is_cancelled") is True
    schedule = raw.get("schedule")
    slots = schedule if isinstance(schedule, list) else []

    indexed: list[tuple[Session, int]] = []
    for index, slot in enumerate(slots):
        session = _session(slot, round_cancelled)
        if session is not None:
            indexed.append((session, index))

    # Reminders follow the published start. Keep the provider order when two
    # sessions share a timestamp.
    indexed.sort(key=lambda item: (item[0].start, item[1]))
    return Event(
        round=number,
        name=name,
        circuit=_circuit(raw.get("circuit")),
        sessions=[session for session, _index in indexed],
    )


def _session(slot: object, round_cancelled: bool) -> Session | None:
    if not isinstance(slot, dict):
        return None

    code = slot.get("code")
    title = slot.get("title")
    start = _parse_start(slot.get("timestamp"))
    if not isinstance(code, str) or not code or not isinstance(title, str) or not title or start is None:
        return None

    # One schedule slot is one reminder. Qualifying and sprint qualifying list
    # Q1/Q2/Q3 under a single published start, so those segments stay inside
    # the slot instead of becoming their own sessions.
    parts = slot.get("sessions")
    nested = parts if isinstance(parts, list) else []
    timezone_name = slot.get("timezone")
    return Session(
        type=code,
        name=title,
        start=start,
        timezone=timezone_name if isinstance(timezone_name, str) and timezone_name else None,
        cancelled=_cancelled(round_cancelled, nested),
    )


def _cancelled(round_cancelled: bool, parts: list) -> bool:
    if round_cancelled:
        return True
    flags = [
        part.get("is_cancelled")
        for part in parts
        if isinstance(part, dict) and "is_cancelled" in part
    ]
    return bool(flags) and all(flag is True for flag in flags)


def _circuit(raw: object) -> Circuit:
    if not isinstance(raw, dict):
        return Circuit(name="", locality="", country="")
    return Circuit(
        name=_text(raw.get("name")),
        locality=_text(raw.get("locality")),
        country=_text(raw.get("country")),
    )


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _parse_start(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)
