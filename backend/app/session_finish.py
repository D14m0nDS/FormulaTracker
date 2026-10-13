from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import httpx

from app.jolpica import ScheduleService
from app.models import FinishedSession, SeasonSchedule, SessionFinishWatch
from app.openf1 import LIVE_MARGIN, OpenF1Unavailable, parse_openf1_time

# OpenF1 sessions have date_end from 2023. Earlier seasons have no finish notice.
FIRST_SESSION_FINISH_YEAR = 2023
# A delay on the same day still fits. A session moved to another day can be missed.
FINISH_WATCH = timedelta(hours=5)
FINISHED_STATUS = "SESSION FINISHED"

# Jolpica session codes. Testing days ("Day 1") are intentionally absent.
OPENF1_SESSION_NAMES = {
    "Practice 1": "FP1",
    "Practice 2": "FP2",
    "Practice 3": "FP3",
    "Sprint Qualifying": "SQ",
    "Sprint": "SR",
    "Qualifying": "Q",
    "Race": "R",
}
TYPE_ORDER = ("FP1", "FP2", "FP3", "SQ", "SR", "Q", "R")


@dataclass(frozen=True)
class ListedSession:
    round: int
    event_name: str
    session_type: str
    session_name: str
    session_key: int
    listed_end: datetime


def in_watch_window(listed_end: datetime, now: datetime) -> bool:
    return listed_end <= now < listed_end + FINISH_WATCH


def last_session_status(messages: list) -> tuple[str, datetime] | None:
    """Latest SessionStatus line.

    Qualifying posts one finished line per phase. Flags and pit notes can
    follow the chequered flag, so only SessionStatus counts, and the latest
    of those wins.
    """
    best_rank: tuple | None = None
    best: tuple[str, datetime] | None = None
    for index, row in enumerate(messages):
        if not isinstance(row, dict) or row.get("category") != "SessionStatus":
            continue
        when = parse_openf1_time(row.get("date"))
        message = row.get("message")
        if when is None or not isinstance(message, str) or not message.strip():
            continue
        phase = row.get("qualifying_phase")
        phase_rank = phase if isinstance(phase, int) and not isinstance(phase, bool) else -1
        rank = (when, phase_rank, index)
        if best_rank is None or rank >= best_rank:
            best_rank = rank
            best = (message.strip(), when)
    return best


def finish_ready(messages: list, now: datetime) -> datetime | None:
    """The finish time once the last status is readable.

    date_end is the listed end, not the real one. OpenF1's free window opens
    30 minutes after the real end, so a SESSION FINISHED line newer than that
    is ignored and the next check tries again.
    """
    last = last_session_status(messages)
    if last is None:
        return None
    message, when = last
    if message != FINISHED_STATUS:
        return None
    if when + LIVE_MARGIN > now:
        return None
    return when


def listed_sessions(schedule: SeasonSchedule, rows: list) -> list[ListedSession]:
    by_start: dict[tuple[str, datetime], tuple] = {}
    for event in schedule.events:
        for session in event.sessions:
            if session.cancelled:
                continue
            start = session.start.astimezone(timezone.utc)
            by_start[(session.type, start)] = (event, session)

    listed: list[ListedSession] = []
    seen: set[tuple[str, datetime]] = set()
    for row in rows:
        if not isinstance(row, dict) or row.get("is_cancelled"):
            continue
        app_type = OPENF1_SESSION_NAMES.get(row.get("session_name"))
        if app_type is None:
            continue
        start = parse_openf1_time(row.get("date_start"))
        end = parse_openf1_time(row.get("date_end"))
        key = row.get("session_key")
        if start is None or end is None or isinstance(key, bool) or not isinstance(key, int):
            continue
        identity = (app_type, start)
        if identity in seen or identity not in by_start:
            continue
        seen.add(identity)
        event, session = by_start[identity]
        listed.append(
            ListedSession(
                round=event.round,
                event_name=event.name,
                session_type=app_type,
                session_name=session.name or app_type,
                session_key=key,
                listed_end=end,
            )
        )
    listed.sort(key=lambda item: (item.round, _type_rank(item.session_type)))
    return listed


class SessionFinishService:
    def __init__(self, client: httpx.AsyncClient, schedules: ScheduleService, *, now=None):
        self._client = client
        self._schedules = schedules
        self._now = now or (lambda: datetime.now(timezone.utc))

    async def watch(self, year: int) -> SessionFinishWatch:
        if year < FIRST_SESSION_FINISH_YEAR:
            return SessionFinishWatch(season=year, sessions=[])
        schedule = await self._schedules.get_schedule(year)
        rows = await self._get("/sessions", {"year": year})
        if not isinstance(rows, list):
            raise OpenF1Unavailable("unexpected sessions payload")
        now = self._now()
        finished: list[FinishedSession] = []
        for session in listed_sessions(schedule, rows):
            if not in_watch_window(session.listed_end, now):
                continue
            try:
                messages = await self._get(
                    "/race_control",
                    {"session_key": session.session_key, "category": "SessionStatus"},
                )
            except OpenF1Unavailable:
                continue
            if not isinstance(messages, list):
                continue
            finished_at = finish_ready(messages, now)
            if finished_at is None:
                continue
            finished.append(
                FinishedSession(
                    round=session.round,
                    event_name=session.event_name,
                    session_type=session.session_type,
                    session_name=session.session_name,
                    listed_end=session.listed_end,
                    finished_at=finished_at,
                )
            )
        return SessionFinishWatch(season=year, sessions=finished)

    async def _get(self, path: str, params: dict) -> list | dict:
        try:
            response = await self._client.get(path, params=params)
        except httpx.HTTPError as exc:
            raise OpenF1Unavailable("provider request failed") from exc
        if response.status_code != 200:
            raise OpenF1Unavailable("provider request failed")
        try:
            return response.json()
        except ValueError as exc:
            raise OpenF1Unavailable("unexpected payload") from exc


def _type_rank(session_type: str) -> int:
    try:
        return TYPE_ORDER.index(session_type)
    except ValueError:
        return len(TYPE_ORDER)
