import asyncio
from datetime import datetime, timedelta, timezone

import httpx
from fastapi.testclient import TestClient

from app.jolpica import JolpicaClient, ScheduleService
from app.main import create_app
from app.models import Circuit, Event, SeasonSchedule, Session
from app.openf1 import OpenF1Unavailable
from app.session_finish import (
    SessionFinishService,
    finish_ready,
    in_watch_window,
    last_session_status,
    listed_sessions,
)

# Singapore 2026. Listed ends are OpenF1's date_end. Finish times are the
# last SessionStatus line, which is later than the listing for the sprint
# and qualifying, and earlier for the race.
SPRINT_LISTED_END = datetime.fromisoformat("2026-10-10T09:30:00+00:00")
SPRINT_FINISHED = datetime.fromisoformat("2026-10-10T10:16:19.195000+00:00")
QUALI_LISTED_END = datetime.fromisoformat("2026-10-10T14:00:00+00:00")
QUALI_PHASE_3_FINISHED = datetime.fromisoformat("2026-10-10T14:30:00.112000+00:00")
RACE_LISTED_END = datetime.fromisoformat("2026-10-11T14:00:00+00:00")
RACE_FINISHED = datetime.fromisoformat("2026-10-11T13:45:40.086000+00:00")


def test_the_watch_opens_at_the_listed_end_and_closes_after_five_hours():
    assert in_watch_window(SPRINT_LISTED_END, SPRINT_LISTED_END - timedelta(seconds=1)) is False
    assert in_watch_window(SPRINT_LISTED_END, SPRINT_LISTED_END) is True
    assert in_watch_window(SPRINT_LISTED_END, SPRINT_FINISHED) is True
    assert in_watch_window(SPRINT_LISTED_END, SPRINT_LISTED_END + timedelta(hours=5)) is False


def test_flags_after_the_chequered_flag_do_not_hide_session_finished():
    messages = [
        _status("2026-10-11T12:03:39.077000+00:00", "SESSION STARTED"),
        {"category": "Flag", "date": "2026-10-11T13:45:40+00:00", "message": "CHEQUERED FLAG"},
        _status("2026-10-11T13:45:40.086000+00:00", "SESSION FINISHED"),
        {"category": "Other", "date": "2026-10-11T13:48:13+00:00", "message": "ALL PASS HOLDERS MAY ACCESS THE PIT LANE"},
    ]

    assert last_session_status(messages)[0] == "SESSION FINISHED"


def test_qualifying_uses_the_last_phase_not_the_first_finish():
    ready_at = QUALI_PHASE_3_FINISHED + timedelta(minutes=30)

    assert finish_ready(_qualifying_messages(), ready_at - timedelta(seconds=1)) is None
    assert finish_ready(_qualifying_messages(), ready_at) == QUALI_PHASE_3_FINISHED


def test_a_finish_line_inside_the_free_window_stays_quiet():
    assert finish_ready(_sprint_messages(), SPRINT_FINISHED) is None
    assert finish_ready(_sprint_messages(), SPRINT_FINISHED + timedelta(minutes=30)) == SPRINT_FINISHED


def test_an_aborted_session_is_not_finished():
    messages = [_status("2026-10-09T12:35:51+00:00", "SESSION ABORTED", phase=1)]

    assert finish_ready(messages, datetime(2026, 10, 9, 13, 30, tzinfo=timezone.utc)) is None


def test_a_moved_or_cancelled_session_is_not_listed():
    schedule = _singapore_schedule()
    schedule.events[0].sessions[2] = schedule.events[0].sessions[2].model_copy(
        update={"start": datetime(2026, 10, 12, 12, tzinfo=timezone.utc)}
    )
    rows = _openf1_rows()
    rows.append(
        {
            "session_name": "Day 1",
            "session_key": 1,
            "date_start": "2026-10-07T01:00:00+00:00",
            "date_end": "2026-10-07T09:00:00+00:00",
            "is_cancelled": False,
        }
    )
    rows.append(
        {
            "session_name": "Race",
            "session_key": 2,
            "date_start": "2026-10-11T12:00:00+00:00",
            "date_end": None,
            "is_cancelled": True,
        }
    )
    schedule.events[0].sessions.append(
        Session(
            type="FP1",
            name="Practice 1",
            start=datetime(2026, 10, 9, 8, 30, tzinfo=timezone.utc),
            cancelled=False,
        )
    )
    rows.append(_openf1("Practice 1", 11378, "2026-10-09T08:30:00+00:00", "2026-10-09T09:30:00+00:00"))

    listed = listed_sessions(schedule, rows)

    assert [session.session_type for session in listed] == ["FP1", "SR", "Q"]


def test_listed_end_alone_does_not_finish_the_sprint():
    calls = {"race_control": []}
    service = _service(
        _handlers(calls, messages_for=lambda key: [_status("2026-10-10T09:03:00+00:00", "SESSION STARTED")] if key == 11383 else []),
        SPRINT_LISTED_END,
    )

    watch = asyncio.run(service.watch(2026))

    assert watch.sessions == []
    assert calls["race_control"] == [11383]


def test_singapore_sprint_waits_until_the_free_window():
    too_early = asyncio.run(_watch(SPRINT_FINISHED, _messages_at))
    ready = asyncio.run(_watch(SPRINT_FINISHED + timedelta(minutes=30), _messages_at))

    assert too_early.sessions == []
    assert [(session.session_type, session.finished_at) for session in ready.sessions] == [("SR", SPRINT_FINISHED)]


def test_singapore_qualifying_reports_the_last_phase_only():
    during_q3 = datetime(2026, 10, 10, 14, 18, tzinfo=timezone.utc)
    ready_at = QUALI_PHASE_3_FINISHED + timedelta(minutes=30)

    during = asyncio.run(_watch(during_q3, _messages_at))
    ready = asyncio.run(_watch(ready_at, _messages_at))

    # The sprint is still inside its five-hour window. Qualifying's last
    # finish is not yet 30 minutes old, so that phase is not announced.
    assert [session.session_type for session in during.sessions] == ["SR"]
    session = ready.sessions[0]
    assert session.session_type == "Q"
    assert session.session_name == "Qualifying"
    assert session.finished_at == QUALI_PHASE_3_FINISHED
    assert session.listed_end == QUALI_LISTED_END


def test_singapore_race_finished_early_and_is_not_the_listed_end():
    at_listed_end = asyncio.run(_watch(RACE_LISTED_END, _messages_at))
    ready_at = RACE_FINISHED + timedelta(minutes=30)
    ready = asyncio.run(_watch(ready_at, _messages_at))
    after_cap = asyncio.run(_watch(RACE_LISTED_END + timedelta(hours=5), _messages_at))

    assert at_listed_end.sessions == []
    assert [(session.round, session.event_name, session.session_type) for session in ready.sessions] == [
        (17, "Singapore Grand Prix", "R")
    ]
    assert ready.sessions[0].finished_at == RACE_FINISHED
    assert after_cap.sessions == []


def test_a_finish_on_the_next_day_is_not_checked():
    calls = {"race_control": []}
    next_day = SPRINT_LISTED_END + timedelta(days=1)
    service = _service(_handlers(calls, messages_for=_messages_at), next_day)

    watch = asyncio.run(service.watch(2026))

    assert watch.sessions == []
    assert calls["race_control"] == []


def test_a_blocked_race_control_request_stays_quiet_and_can_succeed_later():
    calls = {"race_control": 0}

    def openf1(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/sessions"):
            return httpx.Response(200, json=_openf1_rows())
        calls["race_control"] += 1
        if calls["race_control"] == 1:
            return httpx.Response(403)
        assert request.url.params["category"] == "SessionStatus"
        return httpx.Response(200, json=_sprint_messages())

    service = _service(openf1, SPRINT_FINISHED + timedelta(minutes=30))

    first = asyncio.run(service.watch(2026))
    second = asyncio.run(service.watch(2026))

    assert first.sessions == []
    assert second.sessions[0].session_type == "SR"


def test_a_failed_session_list_is_an_error_and_a_season_before_2023_is_empty():
    calls = {"openf1": 0, "jolpica": 0}

    def jolpica(request: httpx.Request) -> httpx.Response:
        calls["jolpica"] += 1
        if request.url.path.endswith("/2022/"):
            return httpx.Response(404)
        return httpx.Response(200, json=_jolpica_payload())

    def openf1(request: httpx.Request) -> httpx.Response:
        calls["openf1"] += 1
        return httpx.Response(503)

    service = _service(openf1, RACE_FINISHED + timedelta(minutes=30), jolpica=jolpica)

    async def run():
        before = await service.watch(2022)
        try:
            await service.watch(2026)
        except OpenF1Unavailable:
            return before
        raise AssertionError("session list failure should stay unavailable")

    before = asyncio.run(run())

    assert before.sessions == []
    assert calls["jolpica"] == 1
    assert calls["openf1"] == 1


def test_route_returns_the_race_once_the_finish_record_is_readable():
    ready_at = RACE_FINISHED + timedelta(minutes=30)

    with _client(ready_at) as client:
        response = client.get("/seasons/2026/session-finish")

    assert response.status_code == 200
    assert response.json() == {
        "season": 2026,
        "sessions": [
            {
                "round": 17,
                "event_name": "Singapore Grand Prix",
                "session_type": "R",
                "session_name": "Race",
                "listed_end": "2026-10-11T14:00:00Z",
                "finished_at": "2026-10-11T13:45:40Z",
            }
        ],
    }


def test_route_maps_a_missing_season_and_a_down_feed():
    def jolpica(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    def openf1(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("openf1 should not be called")

    with _client(RACE_LISTED_END, jolpica=jolpica, openf1=openf1) as client:
        missing = client.get("/seasons/2026/session-finish")

    assert missing.status_code == 404
    assert missing.json()["detail"] == "Season not found"

    def jolpica_ok(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_jolpica_payload())

    def openf1_down(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(503)

    with _client(RACE_LISTED_END, jolpica=jolpica_ok, openf1=openf1_down) as client:
        down = client.get("/seasons/2026/session-finish")

    assert down.status_code == 502
    assert down.json()["detail"] == "Session finish provider unavailable"


def _singapore_schedule() -> SeasonSchedule:
    return SeasonSchedule(
        season=2026,
        events=[
            Event(
                round=17,
                name="Singapore Grand Prix",
                circuit=Circuit(name="Marina Bay Street Circuit", locality="Singapore", country="Singapore"),
                sessions=[
                    Session(type="SR", name="Sprint", start=datetime(2026, 10, 10, 9, tzinfo=timezone.utc), cancelled=False),
                    Session(type="Q", name="Qualifying", start=datetime(2026, 10, 10, 13, tzinfo=timezone.utc), cancelled=False),
                    Session(type="R", name="Race", start=datetime(2026, 10, 11, 12, tzinfo=timezone.utc), cancelled=False),
                ],
            )
        ],
    )


def _openf1_rows() -> list[dict]:
    return [
        _openf1("Sprint", 11383, "2026-10-10T09:00:00+00:00", "2026-10-10T09:30:00+00:00"),
        _openf1("Qualifying", 11384, "2026-10-10T13:00:00+00:00", "2026-10-10T14:00:00+00:00"),
        _openf1("Race", 11388, "2026-10-11T12:00:00+00:00", "2026-10-11T14:00:00+00:00"),
    ]


def _openf1(name: str, key: int, start: str, end: str) -> dict:
    return {
        "session_name": name,
        "session_key": key,
        "date_start": start,
        "date_end": end,
        "is_cancelled": False,
    }


def _status(when: str, message: str, phase: int | None = None) -> dict:
    return {"category": "SessionStatus", "date": when, "message": message, "qualifying_phase": phase}


def _sprint_messages() -> list[dict]:
    return [
        _status("2026-10-10T09:36:30.869000+00:00", "SESSION STARTED"),
        _status("2026-10-10T10:16:19.195000+00:00", "SESSION FINISHED"),
    ]


def _qualifying_messages() -> list[dict]:
    return [
        _status("2026-10-10T13:30:00.122000+00:00", "SESSION STARTED", 1),
        _status("2026-10-10T13:48:00.100000+00:00", "SESSION FINISHED", 1),
        _status("2026-10-10T13:55:00.116000+00:00", "SESSION STARTED", 2),
        _status("2026-10-10T14:10:00.102000+00:00", "SESSION FINISHED", 2),
        _status("2026-10-10T14:17:00.106000+00:00", "SESSION STARTED", 3),
        _status("2026-10-10T14:30:00.112000+00:00", "SESSION FINISHED", 3),
    ]


def _race_messages() -> list[dict]:
    return [
        _status("2026-10-11T12:03:39.077000+00:00", "SESSION STARTED"),
        {"category": "Flag", "date": "2026-10-11T13:45:40+00:00", "message": "CHEQUERED FLAG"},
        _status("2026-10-11T13:45:40.086000+00:00", "SESSION FINISHED"),
        {"category": "Other", "date": "2026-10-11T13:48:13+00:00", "message": "ALL PASS HOLDERS MAY ACCESS THE PIT LANE"},
    ]


def _messages_at(session_key: int) -> list[dict]:
    if session_key == 11383:
        return _sprint_messages()
    if session_key == 11384:
        return _qualifying_messages()
    if session_key == 11388:
        return _race_messages()
    return []


def _jolpica_payload() -> dict:
    slots = []
    for session in _singapore_schedule().events[0].sessions:
        slots.append(
            {
                "code": session.type,
                "title": session.name,
                "timestamp": session.start.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "sessions": [{"type": session.type, "is_cancelled": False}],
            }
        )
    return {
        "data": {
            "year": 2026,
            "events": [
                {
                    "round": {"id": "round_singapore", "number": 17, "name": "Singapore Grand Prix"},
                    "circuit": {"name": "Marina Bay Street Circuit", "locality": "Singapore", "country": "Singapore"},
                    "schedule": slots,
                }
            ],
        }
    }


def _handlers(calls: dict, messages_for):
    def openf1(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/sessions"):
            assert request.url.params["year"] == "2026"
            return httpx.Response(200, json=_openf1_rows())
        assert request.url.params["category"] == "SessionStatus"
        calls["race_control"].append(int(request.url.params["session_key"]))
        return httpx.Response(200, json=messages_for(int(request.url.params["session_key"])))

    return openf1


async def _watch(now: datetime, messages_for):
    service = _service(_handlers({"race_control": []}, messages_for), now)
    return await service.watch(2026)


def _service(openf1, now: datetime, jolpica=None) -> SessionFinishService:
    async def no_sleep(_delay: float) -> None:
        return None

    def default_jolpica(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_jolpica_payload())

    jolpica_http = httpx.AsyncClient(
        transport=httpx.MockTransport(jolpica or default_jolpica),
        base_url="https://api.jolpi.ca",
    )
    openf1_http = httpx.AsyncClient(
        transport=httpx.MockTransport(openf1),
        base_url="https://api.openf1.org/v1",
    )
    schedules = ScheduleService(JolpicaClient(client=jolpica_http, sleep=no_sleep), ttl_seconds=60)
    return SessionFinishService(openf1_http, schedules, now=lambda: now)


def _client(now: datetime, jolpica=None, openf1=None) -> TestClient:
    if openf1 is None:
        openf1 = _handlers({"race_control": []}, _messages_at)
    service = _service(openf1, now, jolpica=jolpica)
    return TestClient(create_app(service._schedules, finishes=service))
