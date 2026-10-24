"""Session results from OpenF1's free history.

Jolpica can publish a session hours or days after it ends. OpenF1 has the
classification a few minutes after the official results are out, but it is
blocked during its live window, so it is only asked when Jolpica has no rows
yet. It fills the same models, so the app does not know which source answered.
"""

import asyncio
import math
import time
from datetime import datetime, timezone

import httpx

from app.models import (
    ClassificationEntry,
    Driver,
    Event,
    PracticeEntry,
    PracticeResults,
    RaceEntry,
    RaceResults,
    SegmentResult,
    Session,
    SessionResults,
)
from app.openf1 import OpenF1Unavailable, get_openf1_json, parse_openf1_time
from app.session_finish import OPENF1_SESSION_NAMES

FIRST_RESULTS_YEAR = 2023
SESSIONS_TTL_SECONDS = 15 * 60
# OpenF1's free tier is limited per second, and opening a weekend can ask for
# several sessions at once, so every call is spaced out.
REQUEST_SPACING_SECONDS = 0.4

SEGMENT_CODES = {"Q": ["Q1", "Q2", "Q3"], "SQ": ["SQ1", "SQ2", "SQ3"]}
# OpenF1 publishes the starting grid under the qualifying session that set it.
GRID_SESSION_TYPES = {"R": "Q", "SR": "SQ"}
RACE_TYPES = {"R", "SR"}
PRACTICE_TYPES = {"FP1", "FP2", "FP3"}

# The app finds team logos and colors by the names Jolpica uses.
TEAM_NAMES = {
    "Alpine": "Alpine F1 Team",
    "Cadillac": "Cadillac F1 Team",
    "Kick Sauber": "Sauber",
    "RB": "RB F1 Team",
    "Racing Bulls": "RB F1 Team",
    "Red Bull Racing": "Red Bull",
}

ResultSet = SessionResults | RaceResults | PracticeResults


def format_duration(seconds: object) -> str | None:
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)):
        return None
    if not math.isfinite(seconds) or seconds < 0:
        return None
    millis = round(seconds * 1000)
    hours, rest = divmod(millis, 3_600_000)
    minutes, rest = divmod(rest, 60_000)
    whole_seconds, fraction = divmod(rest, 1000)
    if hours:
        return f"{hours}:{minutes:02d}:{whole_seconds:02d}.{fraction:03d}"
    return f"{minutes}:{whole_seconds:02d}.{fraction:03d}"


def find_session(sessions: list, session: Session) -> dict | None:
    # Same rule as the session-finish notice: the type and the exact UTC start
    # must agree, so a shifted or renamed session is never matched by guess.
    for row in sessions:
        if not isinstance(row, dict) or row.get("is_cancelled"):
            continue
        if OPENF1_SESSION_NAMES.get(row.get("session_name")) != session.type:
            continue
        if parse_openf1_time(row.get("date_start")) != session.start:
            continue
        key = row.get("session_key")
        if isinstance(key, int) and not isinstance(key, bool):
            return row
    return None


def build_results(
    year: int,
    event: Event,
    session: Session,
    rows: list,
    drivers: list,
    grid: dict[int, int],
) -> ResultSet:
    people = _people(drivers)
    identity = {
        "season": year,
        "round": event.round,
        "event_name": event.name,
        "session_type": session.type,
        "session_name": session.name,
        "start": session.start,
        "timezone": session.timezone,
    }
    if session.type in RACE_TYPES:
        entries = [entry for row in rows if (entry := _race_entry(row, people, grid)) is not None]
        return RaceResults(**identity, results=_by_position(entries))
    if session.type in PRACTICE_TYPES:
        entries = [entry for row in rows if (entry := _practice_entry(row, people)) is not None]
        return PracticeResults(**identity, results=_by_position(entries))
    codes = SEGMENT_CODES.get(session.type, [])
    entries = [entry for row in rows if (entry := _classification(row, people, codes)) is not None]
    return SessionResults(**identity, segment_codes=codes, results=_by_position(entries))


class OpenF1ResultsService:
    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        now=None,
        sleep=asyncio.sleep,
        spacing_seconds: float = REQUEST_SPACING_SECONDS,
    ) -> None:
        self._client = client
        self._now = now or (lambda: datetime.now(timezone.utc))
        self._sleep = sleep
        self._spacing_seconds = spacing_seconds
        self._sessions: dict[int, tuple[float, list]] = {}
        self._pace = asyncio.Lock()
        self._last_request = 0.0

    async def fetch(self, year: int, event: Event, session: Session) -> ResultSet | None:
        """Results for one listed session, or None when OpenF1 cannot supply them."""
        if year < FIRST_RESULTS_YEAR or session.start > self._now():
            return None
        try:
            sessions = await self._sessions_for(year)
            match = find_session(sessions, session)
            if match is None:
                return None
            key = match["session_key"]
            rows = await self._get("/session_result", {"session_key": key})
            drivers = await self._get("/drivers", {"session_key": key})
        except OpenF1Unavailable:
            return None
        if not isinstance(rows, list) or not rows or not isinstance(drivers, list):
            return None
        grid = await self._grid(sessions, match, session)
        results = build_results(year, event, session, rows, drivers, grid)
        return results if results.results else None

    async def _grid(self, sessions: list, match: dict, session: Session) -> dict[int, int]:
        grid_type = GRID_SESSION_TYPES.get(session.type)
        meeting = match.get("meeting_key")
        if grid_type is None or meeting is None:
            return {}
        setter = next(
            (
                row
                for row in sessions
                if isinstance(row, dict)
                and not row.get("is_cancelled")
                and row.get("meeting_key") == meeting
                and OPENF1_SESSION_NAMES.get(row.get("session_name")) == grid_type
                and isinstance(row.get("session_key"), int)
            ),
            None,
        )
        if setter is None:
            return {}
        try:
            rows = await self._get("/starting_grid", {"session_key": setter["session_key"]})
        except OpenF1Unavailable:
            return {}
        if not isinstance(rows, list):
            return {}
        grid: dict[int, int] = {}
        for row in rows:
            if not isinstance(row, dict):
                continue
            number = row.get("driver_number")
            position = _position(row.get("position"))
            if isinstance(number, int) and not isinstance(number, bool) and position is not None:
                grid[number] = position
        return grid

    async def _sessions_for(self, year: int) -> list:
        item = self._sessions.get(year)
        if item is not None and item[0] > time.monotonic():
            return item[1]
        rows = await self._get("/sessions", {"year": year})
        if not isinstance(rows, list):
            raise OpenF1Unavailable("unexpected sessions payload")
        self._sessions[year] = (time.monotonic() + SESSIONS_TTL_SECONDS, rows)
        return rows

    async def _get(self, path: str, params: dict) -> list | dict:
        async with self._pace:
            wait = self._spacing_seconds - (time.monotonic() - self._last_request)
            if wait > 0:
                await self._sleep(wait)
            try:
                return await get_openf1_json(self._client, path, params)
            finally:
                self._last_request = time.monotonic()


def _people(drivers: list) -> dict[int, tuple[Driver, str]]:
    people: dict[int, tuple[Driver, str]] = {}
    for row in drivers:
        if not isinstance(row, dict):
            continue
        number = row.get("driver_number")
        code = str(row.get("name_acronym") or "").strip().upper()
        team = str(row.get("team_name") or "").strip()
        if not isinstance(number, int) or isinstance(number, bool) or not code or not team:
            continue
        driver = Driver(
            code=code,
            given_name=str(row.get("first_name") or "").strip(),
            family_name=str(row.get("last_name") or "").strip() or code,
            number=number,
        )
        people[number] = (driver, TEAM_NAMES.get(team, team))
    return people


def _person(row: dict, people: dict[int, tuple[Driver, str]]) -> tuple[Driver, str] | None:
    number = row.get("driver_number")
    if not isinstance(number, int) or isinstance(number, bool):
        return None
    return people.get(number)


def _race_entry(row: object, people: dict, grid: dict[int, int]) -> RaceEntry | None:
    if not isinstance(row, dict) or (person := _person(row, people)) is None:
        return None
    driver, team = person
    position = _position(row.get("position"))
    gap = row.get("gap_to_leader")
    lapped = isinstance(gap, str) and "LAP" in gap.upper()
    return RaceEntry(
        position=position,
        position_text=_position_text(position),
        classified=position is not None and row.get("dsq") is not True,
        status=_status(row, lapped=lapped),
        time=format_duration(row.get("duration")),
        points=_number(row.get("points")),
        laps=_whole(row.get("number_of_laps")),
        grid=grid.get(driver.number) if driver.number is not None else None,
        driver=driver,
        team=team,
    )


def _practice_entry(row: object, people: dict) -> PracticeEntry | None:
    if not isinstance(row, dict) or (person := _person(row, people)) is None:
        return None
    driver, team = person
    position = _position(row.get("position"))
    return PracticeEntry(
        position=position,
        position_text=_position_text(position),
        classified=position is not None,
        time=format_duration(row.get("duration")),
        laps=_whole(row.get("number_of_laps")),
        driver=driver,
        team=team,
    )


def _classification(row: object, people: dict, codes: list[str]) -> ClassificationEntry | None:
    if not isinstance(row, dict) or (person := _person(row, people)) is None:
        return None
    driver, team = person
    position = _position(row.get("position"))
    durations = row.get("duration") if isinstance(row.get("duration"), list) else []
    # A driver knocked out earlier has null later segments. Keep only the timed
    # ones, in segment order, and let the last of them be the headline time.
    segments = [
        SegmentResult(code=code, position=None, time=time_text)
        for index, code in enumerate(codes)
        if index < len(durations) and (time_text := format_duration(durations[index])) is not None
    ]
    status = _status(row, lapped=False)
    if not segments and status == "FINISHED":
        # OpenF1 does not say why a driver has no lap, only that none was set.
        status = "NO TIME"
    return ClassificationEntry(
        position=position,
        position_text=_position_text(position),
        classified=position is not None and row.get("dsq") is not True,
        status=status,
        time=segments[-1].time if segments else None,
        driver=driver,
        team=team,
        segments=segments,
    )


def _status(row: dict, *, lapped: bool) -> str:
    if row.get("dsq") is True:
        return "DISQUALIFIED"
    if row.get("dns") is True:
        return "DID_NOT_START"
    if row.get("dnf") is True:
        return "RETIRED"
    return "LAPPED" if lapped else "FINISHED"


def _by_position(entries: list) -> list:
    return sorted(entries, key=lambda entry: (entry.position is None, entry.position or 0))


def _position(value: object) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        return None
    return value


def _position_text(position: int | None) -> str:
    return str(position) if position is not None else "NC"


def _whole(value: object) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def _number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)
