from dataclasses import dataclass
from datetime import datetime, timezone

from app.models import (
    Circuit,
    ClassificationEntry,
    Driver,
    Event,
    FastestLap,
    RaceEntry,
    RaceResults,
    SeasonSchedule,
    SegmentResult,
    Session,
    SessionResults,
)


class ProviderPayloadError(Exception):
    """Jolpica returned JSON that this app cannot use."""


@dataclass(frozen=True)
class ProviderRound:
    provider_id: str
    sessions: frozenset[str]


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


def read_schedule(payload: dict) -> tuple[SeasonSchedule, dict[int, ProviderRound]]:
    schedule = normalize_schedule(payload)
    kept = {event.round for event in schedule.events}
    rounds = {
        number: provider_round
        for number, provider_round in _index_provider_rounds(payload).items()
        if number in kept
    }
    return schedule, rounds


def normalize_session_results(
    payload: dict,
    *,
    season: int,
    round_number: int,
    session_type: str,
) -> SessionResults:
    _data, title, start, event_name, timezone_name, rows = _open_results(
        payload,
        season=season,
        round_number=round_number,
        session_type=session_type,
    )
    segment_codes = _segment_codes(_data.get("component_keys"))
    return SessionResults(
        season=season,
        round=round_number,
        event_name=event_name,
        session_type=session_type,
        session_name=title,
        start=start,
        timezone=timezone_name,
        segment_codes=segment_codes,
        results=[entry for row in rows if (entry := _classification(row, segment_codes)) is not None],
    )


def normalize_race_results(
    payload: dict,
    *,
    season: int,
    round_number: int,
    session_type: str,
) -> RaceResults:
    _data, title, start, event_name, timezone_name, rows = _open_results(
        payload,
        season=season,
        round_number=round_number,
        session_type=session_type,
    )
    return RaceResults(
        season=season,
        round=round_number,
        event_name=event_name,
        session_type=session_type,
        session_name=title,
        start=start,
        timezone=timezone_name,
        results=[entry for row in rows if (entry := _race_entry(row)) is not None],
    )


def _open_results(
    payload: dict,
    *,
    season: int,
    round_number: int,
    session_type: str,
) -> tuple[dict, str, datetime, str, str | None, list]:
    data = payload.get("data")
    if not isinstance(data, dict):
        raise ProviderPayloadError("results payload has no data object")

    payload_season = _nested_int(data.get("season"), "year")
    payload_round = _nested_int(data.get("round"), "number")
    code = data.get("code")
    if payload_season != season or payload_round != round_number or code != session_type:
        raise ProviderPayloadError("results do not match the requested session")

    title = data.get("title")
    start = _parse_start(data.get("timestamp"))
    event_name = _nested_text(data.get("round"), "name")
    if not isinstance(title, str) or not title or start is None or not event_name:
        raise ProviderPayloadError("results payload is missing session identity")

    rows = data.get("results")
    if not isinstance(rows, list):
        raise ProviderPayloadError("results payload has no results list")

    timezone_name = data.get("timezone")
    return (
        data,
        title,
        start,
        event_name,
        timezone_name if isinstance(timezone_name, str) and timezone_name else None,
        rows,
    )


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


def _index_provider_rounds(payload: dict) -> dict[int, ProviderRound]:
    data = payload.get("data")
    events = data.get("events") if isinstance(data, dict) else None
    if not isinstance(events, list):
        return {}

    indexed: dict[int, ProviderRound] = {}
    for raw in events:
        if not isinstance(raw, dict):
            continue
        round_info = raw.get("round")
        if not isinstance(round_info, dict):
            continue
        number = round_info.get("number")
        provider_id = round_info.get("id")
        if not isinstance(number, int) or number in indexed:
            continue
        if not isinstance(provider_id, str) or not provider_id:
            continue
        indexed[number] = ProviderRound(provider_id, frozenset(_session_codes(raw.get("schedule"))))
    return indexed


def _session_codes(schedule: object) -> list[str]:
    if not isinstance(schedule, list):
        return []
    return [
        slot["code"]
        for slot in schedule
        if isinstance(slot, dict) and isinstance(slot.get("code"), str) and slot["code"]
    ]


def _classification(row: object, segment_codes: list[str]) -> ClassificationEntry | None:
    if not isinstance(row, dict):
        return None
    driver = _driver(row.get("driver"), row.get("car_number"))
    team = _nested_text(row.get("team"), "name")
    if driver is None or not team:
        return None

    position = row.get("position")
    position_value = position if isinstance(position, int) else None
    position_text = row.get("position_text")
    if not isinstance(position_text, str) or not position_text:
        position_text = str(position_value) if position_value is not None else ""

    status = row.get("status")
    time = row.get("time")
    components = row.get("components")
    component_map = components if isinstance(components, dict) else {}
    return ClassificationEntry(
        position=position_value,
        position_text=position_text,
        classified=row.get("is_classified") is True,
        status=status if isinstance(status, str) and status else None,
        time=time if isinstance(time, str) and time else None,
        driver=driver,
        team=team,
        segments=_segments(component_map, segment_codes),
    )


def _race_entry(row: object) -> RaceEntry | None:
    if not isinstance(row, dict):
        return None
    driver = _driver(row.get("driver"), row.get("car_number"))
    team = _nested_text(row.get("team"), "name")
    if driver is None or not team:
        return None

    position = row.get("position")
    position_value = position if isinstance(position, int) and not isinstance(position, bool) else None
    position_text = row.get("position_text")
    if not isinstance(position_text, str) or not position_text:
        position_text = str(position_value) if position_value is not None else ""

    status = row.get("status")
    time = row.get("time")
    components = row.get("components")
    component_map = components if isinstance(components, dict) else {}
    return RaceEntry(
        position=position_value,
        position_text=position_text,
        classified=row.get("is_classified") is True,
        status=status if isinstance(status, str) and status else None,
        time=time if isinstance(time, str) and time else None,
        points=_decimal(row.get("points")),
        laps=_whole(row.get("laps")),
        grid=_grid(component_map),
        fastest_lap=_fastest_lap(component_map),
        driver=driver,
        team=team,
    )


def _grid(components: dict) -> int | None:
    grid = components.get("GRID")
    if not isinstance(grid, dict):
        return None
    return _whole(grid.get("position"))


def _fastest_lap(components: dict) -> FastestLap | None:
    flap = components.get("FLAP")
    if not isinstance(flap, dict):
        return None
    position = _whole(flap.get("position"))
    time = flap.get("time")
    time_value = time if isinstance(time, str) and time else None
    if position is None and time_value is None:
        return None
    return FastestLap(position=position, time=time_value)


def _decimal(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def _whole(value: object) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def _driver(raw: object, car_number: object) -> Driver | None:
    if not isinstance(raw, dict):
        return None
    code = raw.get("abbreviation")
    given_name = raw.get("given_name")
    family_name = raw.get("family_name")
    if not isinstance(code, str) or not isinstance(given_name, str) or not isinstance(family_name, str):
        return None
    if not code or not family_name:
        return None
    return Driver(
        code=code,
        given_name=given_name,
        family_name=family_name,
        number=_whole(car_number),
    )


def _segments(components: dict, segment_codes: list[str]) -> list[SegmentResult]:
    # Knocked-out drivers omit later segments. Keep component_keys order so
    # Q3 cannot appear before Q1 when the provider object is unordered.
    codes = segment_codes or [code for code in components if isinstance(code, str)]
    segments: list[SegmentResult] = []
    for code in codes:
        component = components.get(code)
        if not isinstance(component, dict):
            continue
        position = component.get("position")
        time = component.get("time")
        segments.append(
            SegmentResult(
                code=code,
                position=position if isinstance(position, int) else None,
                time=time if isinstance(time, str) and time else None,
            )
        )
    return segments


def _segment_codes(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [code for code in value if isinstance(code, str) and code]


def _nested_int(raw: object, key: str) -> int | None:
    if not isinstance(raw, dict):
        return None
    value = raw.get(key)
    return value if isinstance(value, int) else None


def _nested_text(raw: object, key: str) -> str:
    if not isinstance(raw, dict):
        return ""
    return _text(raw.get(key))
