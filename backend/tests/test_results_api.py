import json
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

from app.jolpica import JolpicaClient, ScheduleService
from app.main import create_app
from app.results import ResultsService

FIXTURES = Path(__file__).parent / "fixtures"
SCHEDULE = json.loads((FIXTURES / "jolpica_schedule_2026.json").read_text(encoding="utf-8"))
QUALIFYING = json.loads((FIXTURES / "jolpica_qualifying_2026_r1.json").read_text(encoding="utf-8"))
SPRINT_QUALIFYING = json.loads(
    (FIXTURES / "jolpica_sprint_qualifying_2026_r2.json").read_text(encoding="utf-8")
)
RACE = json.loads((FIXTURES / "jolpica_race_2026_r1.json").read_text(encoding="utf-8"))
SPRINT_RACE = json.loads((FIXTURES / "jolpica_sprint_race_2026_r2.json").read_text(encoding="utf-8"))


def test_qualifying_route_uses_the_provider_round_id_and_caches():
    calls = {"schedule": 0, "results": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            calls["schedule"] += 1
            return httpx.Response(200, json=SCHEDULE)
        if request.url.path == "/f1/alpha/results/round_hidden_australia/Q/":
            calls["results"] += 1
            return httpx.Response(200, json=QUALIFYING)
        return httpx.Response(404)

    with _client(handler) as client:
        first = client.get("/seasons/2026/rounds/1/sessions/Q/results")
        second = client.get("/seasons/2026/rounds/1/sessions/Q/results")

    assert first.status_code == 200
    body = first.json()
    assert body["event_name"] == "Australian Grand Prix"
    assert body["start"] == "2026-03-07T05:00:00Z"
    assert body["results"][0]["driver"]["code"] == "RUS"
    assert body["results"][0]["segments"][0] == {"code": "Q1", "position": 1, "time": "1:19.507"}
    assert second.json() == body
    assert calls == {"schedule": 1, "results": 1}


def test_sprint_qualifying_route():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        if request.url.path == "/f1/alpha/results/round_hidden_china/SQ/":
            return httpx.Response(200, json=SPRINT_QUALIFYING)
        return httpx.Response(404)

    with _client(handler) as client:
        response = client.get("/seasons/2026/rounds/2/sessions/SQ/results")

    assert response.status_code == 200
    assert response.json()["session_type"] == "SQ"
    assert response.json()["segment_codes"] == ["SQ1", "SQ2", "SQ3"]


def test_sprint_qualifying_is_404_when_the_weekend_has_none():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        raise AssertionError(request.url.path)

    with _client(handler) as client:
        response = client.get("/seasons/2026/rounds/1/sessions/SQ/results")

    assert response.status_code == 404
    assert response.json()["detail"] == "Session not on this round"


def test_unknown_round_is_404():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        raise AssertionError(request.url.path)

    with _client(handler) as client:
        response = client.get("/seasons/2026/rounds/9/sessions/Q/results")

    assert response.status_code == 404
    assert response.json()["detail"] == "Round not found"


def test_race_and_sprint_race_routes():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        if request.url.path == "/f1/alpha/results/round_hidden_australia/R/":
            return httpx.Response(200, json=RACE)
        if request.url.path == "/f1/alpha/results/round_hidden_china/SR/":
            return httpx.Response(200, json=SPRINT_RACE)
        return httpx.Response(404)

    with _client(handler) as client:
        race = client.get("/seasons/2026/rounds/1/sessions/R/results")
        sprint = client.get("/seasons/2026/rounds/2/sessions/SR/results")
        missing = client.get("/seasons/2026/rounds/1/sessions/SR/results")

    assert race.status_code == 200
    assert race.json()["results"][0]["points"] == 25
    assert race.json()["results"][0]["grid"] == 1
    assert "segment_codes" not in race.json()
    assert sprint.status_code == 200
    assert sprint.json()["session_type"] == "SR"
    assert sprint.json()["results"][0]["points"] == 8
    assert missing.status_code == 404
    assert missing.json()["detail"] == "Session not on this round"


def test_practice_results_are_not_this_endpoint():
    def handler(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("provider should not be called")

    with _client(handler) as client:
        response = client.get("/seasons/2026/rounds/1/sessions/FP1/results")

    assert response.status_code == 422


def test_missing_provider_results_are_404_and_not_cached():
    calls = {"results": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/f1/alpha/schedules/2026/":
            return httpx.Response(200, json=SCHEDULE)
        calls["results"] += 1
        return httpx.Response(404)

    with _client(handler) as client:
        first = client.get("/seasons/2026/rounds/1/sessions/Q/results")
        second = client.get("/seasons/2026/rounds/1/sessions/Q/results")

    assert first.status_code == 404
    assert second.status_code == 404
    assert calls["results"] == 2


def _client(handler) -> TestClient:
    async def no_sleep(_delay: float) -> None:
        return None

    http = httpx.AsyncClient(
        transport=httpx.MockTransport(handler),
        base_url="https://api.jolpi.ca",
    )
    jolpica = JolpicaClient(client=http, sleep=no_sleep)
    schedules = ScheduleService(jolpica, ttl_seconds=60)
    results = ResultsService(schedules, jolpica, ttl_seconds=60)
    return TestClient(create_app(schedules, results))
