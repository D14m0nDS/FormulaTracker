import asyncio
import copy
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.jolpica import JolpicaClient, ScheduleService
from app.main import create_app
from app.normalize import normalize_schedule
from app.openf1_results import OpenF1ResultsService, format_duration
from app.results import ResultsService

FIXTURES = Path(__file__).parent / "fixtures"
SCHEDULE = json.loads((FIXTURES / "jolpica_schedule_2026.json").read_text(encoding="utf-8"))
QUALIFYING = json.loads((FIXTURES / "jolpica_qualifying_2026_r1.json").read_text(encoding="utf-8"))
PRACTICE = json.loads((FIXTURES / "jolpica_practice_2026_r1.json").read_text(encoding="utf-8"))
RACE = json.loads((FIXTURES / "jolpica_race_2026_r1.json").read_text(encoding="utf-8"))

AFTER_THE_SEASON_OPENER = datetime(2026, 4, 1, tzinfo=timezone.utc)
SESSION_NAMES = {
    "FP1": "Practice 1",
    "Q": "Qualifying",
    "R": "Race",
    "SQ": "Sprint Qualifying",
    "SR": "Sprint",
}
EVENTS = {event.round: event for event in normalize_schedule(SCHEDULE).events}

DRIVERS = [
    {"driver_number": 63, "name_acronym": "RUS", "first_name": "George", "last_name": "Russell", "team_name": "Mercedes"},
    {"driver_number": 16, "name_acronym": "LEC", "first_name": "Charles", "last_name": "Leclerc", "team_name": "Ferrari"},
    {"driver_number": 3, "name_acronym": "VER", "first_name": "Max", "last_name": "Verstappen", "team_name": "Red Bull Racing"},
    {"driver_number": 23, "name_acronym": "ALB", "first_name": "Alexander", "last_name": "Albon", "team_name": "Williams"},
    {"driver_number": 18, "name_acronym": "STR", "first_name": "Lance", "last_name": "Stroll", "team_name": "Aston Martin"},
    {"driver_number": 5, "name_acronym": "BOR", "first_name": "Gabriel", "last_name": "Bortoleto", "team_name": "Audi"},
]


def _row(number, position, *, laps=50, duration=None, gap=None, points=None, dnf=False, dns=False, dsq=False):
    row = {
        "position": position,
        "driver_number": number,
        "number_of_laps": laps,
        "dnf": dnf,
        "dns": dns,
        "dsq": dsq,
        "duration": duration,
        "gap_to_leader": gap,
    }
    if points is not None:
        row["points"] = points
    return row


RACE_ROWS = [
    _row(63, 1, laps=57, duration=5400.5, gap=0, points=25.0),
    _row(16, 2, laps=57, duration=5407.75, gap=7.25, points=18.0),
    _row(3, 3, laps=56, gap="+1 LAP", points=0.0),
    _row(23, 4, laps=52, dnf=True, points=0.0),
    _row(18, None, laps=10, dnf=True, points=0.0),
    _row(5, None, laps=0, dns=True, points=0.0),
    _row(99, 7, laps=57, duration=5500.0, gap=99.5),
]


def _sessions(event, keys, *, meeting=7, shift=timedelta(0)):
    rows = []
    for session_type, key in keys.items():
        start = next(s for s in event.sessions if s.type == session_type).start + shift
        rows.append(
            {
                "session_key": key,
                "meeting_key": meeting,
                "session_name": SESSION_NAMES[session_type],
                "date_start": start.isoformat(),
                "date_end": (start + timedelta(hours=1)).isoformat(),
                "is_cancelled": False,
            }
        )
    return rows


def _openf1(sessions, results=None, grid=None, calls=None, drivers=None):
    results = results or {}
    grid = grid or {}

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if calls is not None:
            calls.append(path.rsplit("/", 1)[-1])
        if path.endswith("/sessions"):
            return httpx.Response(200, json=sessions)
        key = int(request.url.params["session_key"])
        if path.endswith("/session_result") and key in results:
            return httpx.Response(200, json=results[key])
        if path.endswith("/drivers"):
            return httpx.Response(200, json=DRIVERS if drivers is None else drivers)
        if path.endswith("/starting_grid") and key in grid:
            return httpx.Response(200, json=grid[key])
        return httpx.Response(404, json={"detail": "No results found."})

    return handler


def _blocked(_request: httpx.Request) -> httpx.Response:
    return httpx.Response(401, json={"detail": "Live data needs a paid plan."})


def _jolpica(*, results=None, status=200):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        if results is not None and status == 200:
            return httpx.Response(200, json=results)
        return httpx.Response(status)

    return handler


def _client(jolpica_handler, openf1_handler, *, now=AFTER_THE_SEASON_OPENER, clock=None) -> TestClient:
    async def no_sleep(_delay: float) -> None:
        return None

    jolpica_http = httpx.AsyncClient(
        transport=httpx.MockTransport(jolpica_handler), base_url="https://api.jolpi.ca"
    )
    openf1_http = httpx.AsyncClient(
        transport=httpx.MockTransport(openf1_handler), base_url="https://api.openf1.org/v1"
    )
    jolpica = JolpicaClient(client=jolpica_http, sleep=no_sleep)
    schedules = ScheduleService(jolpica, ttl_seconds=60)
    fallback = OpenF1ResultsService(
        openf1_http, now=lambda: now, sleep=no_sleep, spacing_seconds=0
    )
    options = {"clock": clock} if clock is not None else {}
    results = ResultsService(schedules, jolpica, ttl_seconds=900, fallback=fallback, **options)
    return TestClient(create_app(schedules, results))


def _empty(payload):
    copied = copy.deepcopy(payload)
    copied["data"]["results"] = []
    return copied


def test_a_race_missing_from_jolpica_is_filled_from_openf1():
    keys = {"Q": 104, "R": 108}
    openf1 = _openf1(
        _sessions(EVENTS[1], keys),
        results={108: RACE_ROWS},
        grid={104: [{"position": 2, "driver_number": 63}, {"position": 1, "driver_number": 16}]},
    )

    with _client(_jolpica(status=404), openf1) as client:
        response = client.get("/seasons/2026/rounds/1/sessions/R/results")

    assert response.status_code == 200
    body = response.json()
    assert body["event_name"] == EVENTS[1].name
    assert body["session_type"] == "R"
    assert body["start"] == "2026-03-08T04:00:00Z"
    rows = body["results"]
    assert [row["driver"]["code"] for row in rows] == ["RUS", "LEC", "VER", "ALB", "STR", "BOR"]
    winner = rows[0]
    assert winner["position"] == 1
    assert winner["time"] == "1:30:00.500"
    assert winner["points"] == 25
    assert winner["grid"] == 2
    assert winner["laps"] == 57
    assert winner["status"] == "FINISHED"
    assert winner["classified"] is True
    assert winner["team"] == "Mercedes"
    assert winner["driver"] == {"code": "RUS", "given_name": "George", "family_name": "Russell", "number": 63}
    assert rows[1]["time"] == "1:30:07.750"
    assert rows[1]["grid"] == 1


def test_lapped_retired_and_unclassified_race_rows_keep_their_meaning():
    openf1 = _openf1(_sessions(EVENTS[1], {"R": 108}), results={108: RACE_ROWS})

    with _client(_jolpica(status=404), openf1) as client:
        rows = client.get("/seasons/2026/rounds/1/sessions/R/results").json()["results"]

    lapped, retired_classified, retired, not_started = rows[2], rows[3], rows[4], rows[5]
    assert (lapped["status"], lapped["classified"], lapped["time"], lapped["laps"]) == ("LAPPED", True, None, 56)
    assert lapped["team"] == "Red Bull"
    assert (retired_classified["status"], retired_classified["classified"]) == ("RETIRED", True)
    assert retired_classified["position_text"] == "4"
    assert (retired["status"], retired["classified"], retired["position_text"]) == ("RETIRED", False, "NC")
    assert retired["position"] is None
    assert (not_started["status"], not_started["classified"]) == ("DID_NOT_START", False)
    assert "grid" in lapped and lapped["grid"] is None


def test_a_driver_missing_from_the_openf1_driver_list_is_left_out():
    openf1 = _openf1(_sessions(EVENTS[1], {"R": 108}), results={108: RACE_ROWS})

    with _client(_jolpica(status=404), openf1) as client:
        rows = client.get("/seasons/2026/rounds/1/sessions/R/results").json()["results"]

    assert 99 not in [row["driver"]["number"] for row in rows]


def test_qualifying_with_no_rows_from_jolpica_gets_segment_times():
    rows = [
        {
            "position": 1,
            "driver_number": 63,
            "number_of_laps": 18,
            "dnf": False,
            "dns": False,
            "dsq": False,
            "duration": [95.39, 94.278, 94.035],
            "gap_to_leader": [0.361, 0.08, 0.0],
        },
        {
            "position": 20,
            "driver_number": 23,
            "number_of_laps": 9,
            "dnf": False,
            "dns": False,
            "dsq": False,
            "duration": [97.433, None, None],
            "gap_to_leader": [2.404, None, None],
        },
        {
            "position": None,
            "driver_number": 18,
            "number_of_laps": 1,
            "dnf": True,
            "dns": False,
            "dsq": False,
            "duration": [None, None, None],
            "gap_to_leader": [None, None, None],
        },
        {
            "position": None,
            "driver_number": 5,
            "number_of_laps": 0,
            "dnf": False,
            "dns": False,
            "dsq": False,
            "duration": [None, None, None],
            "gap_to_leader": [None, None, None],
        },
    ]
    openf1 = _openf1(_sessions(EVENTS[1], {"Q": 104}), results={104: rows})

    with _client(_jolpica(results=_empty(QUALIFYING)), openf1) as client:
        body = client.get("/seasons/2026/rounds/1/sessions/Q/results").json()

    assert body["segment_codes"] == ["Q1", "Q2", "Q3"]
    pole, knocked_out, no_time, no_lap_set = body["results"]
    assert (no_lap_set["status"], no_lap_set["time"], no_lap_set["classified"]) == ("NO TIME", None, False)
    assert pole["time"] == "1:34.035"
    assert [(s["code"], s["time"]) for s in pole["segments"]] == [
        ("Q1", "1:35.390"),
        ("Q2", "1:34.278"),
        ("Q3", "1:34.035"),
    ]
    assert pole["status"] == "FINISHED"
    assert [(s["code"], s["time"]) for s in knocked_out["segments"]] == [("Q1", "1:37.433")]
    assert knocked_out["time"] == "1:37.433"
    assert no_time["segments"] == []
    assert no_time["time"] is None
    assert (no_time["status"], no_time["classified"], no_time["position_text"]) == ("RETIRED", False, "NC")


def test_practice_rows_carry_best_lap_and_lap_count():
    rows = [_row(63, 1, laps=21, duration=94.893, gap=0), _row(16, 2, laps=16, duration=95.061, gap=0.168)]
    openf1 = _openf1(_sessions(EVENTS[1], {"FP1": 101}), results={101: rows})

    with _client(_jolpica(status=404), openf1) as client:
        body = client.get("/seasons/2026/rounds/1/sessions/FP1/results").json()

    assert body["results"][0]["time"] == "1:34.893"
    assert body["results"][0]["laps"] == 21
    assert body["results"][1]["position_text"] == "2"
    assert "points" not in body["results"][0]


def test_jolpica_rows_are_used_and_openf1_is_not_asked():
    def never(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("OpenF1 should not be called")

    with _client(_jolpica(results=QUALIFYING), never) as client:
        response = client.get("/seasons/2026/rounds/1/sessions/Q/results")

    assert response.status_code == 200
    assert response.json()["results"][0]["driver"]["code"] == "RUS"


def test_a_blocked_openf1_leaves_the_jolpica_answer_unchanged():
    with _client(_jolpica(results=_empty(PRACTICE)), _blocked) as client:
        empty = client.get("/seasons/2026/rounds/1/sessions/FP1/results")
    with _client(_jolpica(status=404), _blocked) as client:
        missing = client.get("/seasons/2026/rounds/1/sessions/FP1/results")

    assert empty.status_code == 200
    assert empty.json()["results"] == []
    assert missing.status_code == 404
    assert missing.json()["detail"] == "Results not found"


def test_a_session_that_has_not_started_is_not_requested_from_openf1():
    calls: list[str] = []
    openf1 = _openf1(_sessions(EVENTS[1], {"FP1": 101}), calls=calls)
    before = datetime(2026, 3, 1, tzinfo=timezone.utc)

    with _client(_jolpica(status=404), openf1, now=before) as client:
        response = client.get("/seasons/2026/rounds/1/sessions/FP1/results")

    assert response.status_code == 404
    assert calls == []


def test_a_session_whose_start_differs_is_not_matched():
    openf1 = _openf1(
        _sessions(EVENTS[1], {"FP1": 101}, shift=timedelta(minutes=1)),
        results={101: [_row(63, 1, duration=94.893)]},
    )

    with _client(_jolpica(status=404), openf1) as client:
        response = client.get("/seasons/2026/rounds/1/sessions/FP1/results")

    assert response.status_code == 404


def test_the_sprint_grid_comes_from_sprint_qualifying_not_qualifying():
    event = EVENTS[2]
    sessions = _sessions(event, {"SQ": 201, "SR": 203, "Q": 204}, meeting=9)
    grid = {
        201: [{"position": 3, "driver_number": 63}],
        204: [{"position": 1, "driver_number": 63}],
    }
    openf1 = _openf1(sessions, results={203: [_row(63, 1, laps=19, duration=1800.25, gap=0, points=8.0)]}, grid=grid)
    sprint = next(s for s in event.sessions if s.type == "SR")
    http = httpx.AsyncClient(transport=httpx.MockTransport(openf1), base_url="https://api.openf1.org/v1")

    async def no_sleep(_delay: float) -> None:
        return None

    service = OpenF1ResultsService(http, now=lambda: AFTER_THE_SEASON_OPENER, sleep=no_sleep, spacing_seconds=0)
    results = asyncio.run(service.fetch(2026, event, sprint))

    assert results is not None
    assert results.results[0].grid == 3
    assert results.results[0].points == 8


def test_a_failed_grid_request_only_drops_the_grid():
    openf1 = _openf1(_sessions(EVENTS[1], {"Q": 104, "R": 108}), results={108: RACE_ROWS})

    with _client(_jolpica(status=404), openf1) as client:
        rows = client.get("/seasons/2026/rounds/1/sessions/R/results").json()["results"]

    assert rows[0]["grid"] is None
    assert rows[0]["points"] == 25


def test_openf1_before_2023_is_not_asked():
    calls: list[str] = []
    service_http = httpx.AsyncClient(
        transport=httpx.MockTransport(_openf1([], calls=calls)), base_url="https://api.openf1.org/v1"
    )
    service = OpenF1ResultsService(service_http, now=lambda: AFTER_THE_SEASON_OPENER)
    session = EVENTS[1].sessions[0]

    assert asyncio.run(service.fetch(2022, EVENTS[1], session)) is None
    assert calls == []


def test_empty_answers_are_asked_again_after_a_minute_and_fallback_rows_after_five():
    now = {"value": 0.0}
    jolpica_calls = {"results": 0}
    state = {"jolpica": None}

    def jolpica(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        jolpica_calls["results"] += 1
        if state["jolpica"] is None:
            return httpx.Response(200, json=_empty(PRACTICE))
        return httpx.Response(200, json=state["jolpica"])

    openf1 = _openf1(_sessions(EVENTS[1], {"FP1": 101}))
    path = "/seasons/2026/rounds/1/sessions/FP1/results"
    with _client(jolpica, openf1, clock=lambda: now["value"]) as client:
        assert client.get(path).json()["results"] == []
        now["value"] = 30
        client.get(path)
        assert jolpica_calls["results"] == 1
        now["value"] = 61
        client.get(path)
        assert jolpica_calls["results"] == 2

    rows = [_row(63, 1, laps=21, duration=94.893, gap=0)]
    openf1 = _openf1(_sessions(EVENTS[1], {"FP1": 101}), results={101: rows})
    jolpica_calls["results"] = 0
    now["value"] = 0.0
    with _client(jolpica, openf1, clock=lambda: now["value"]) as client:
        assert client.get(path).json()["results"][0]["time"] == "1:34.893"
        now["value"] = 299
        client.get(path)
        assert jolpica_calls["results"] == 1
        state["jolpica"] = PRACTICE
        now["value"] = 301
        official = client.get(path).json()
        assert jolpica_calls["results"] == 2
        assert official["results"][0]["time"] == "1:20.267"


@pytest.mark.parametrize(
    ("seconds", "expected"),
    [
        (94.893, "1:34.893"),
        (6120.785, "1:42:00.785"),
        (59.9996, "1:00.000"),
        (0, "0:00.000"),
        (95, "1:35.000"),
        (None, None),
        (True, None),
        ("94.893", None),
        (float("nan"), None),
        (-1, None),
    ],
)
def test_format_duration(seconds, expected):
    assert format_duration(seconds) == expected
