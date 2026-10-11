import json
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

from app.jolpica import JolpicaClient, ScheduleService
from app.main import create_app

FIXTURE = json.loads(
    (Path(__file__).parent / "fixtures" / "jolpica_schedule_2026.json").read_text(encoding="utf-8")
)


def test_schedule_route_returns_normalized_utc_times():
    calls = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        assert request.url.path == "/f1/alpha/schedules/2026/"
        return httpx.Response(200, json=FIXTURE)

    with _client(handler) as client:
        first = client.get("/seasons/2026/schedule")
        second = client.get("/seasons/2026/schedule")

    assert first.status_code == 200
    body = first.json()
    assert body["season"] == 2026
    assert body["events"][0]["name"] == "Australian Grand Prix"
    assert body["events"][0]["sessions"][0]["start"] == "2026-03-06T01:30:00Z"
    assert second.json() == body
    assert calls["count"] == 1


def test_unknown_season_is_404():
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    with _client(handler) as client:
        response = client.get("/seasons/1999/schedule")

    assert response.status_code == 404
    assert response.json()["detail"] == "Season not found"


def test_provider_failure_is_502_and_is_not_cached():
    calls = {"count": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        return httpx.Response(503)

    with _client(handler) as client:
        first = client.get("/seasons/2026/schedule")
        second = client.get("/seasons/2026/schedule")

    assert first.status_code == 502
    assert second.status_code == 502
    assert calls["count"] == 4


def test_transient_server_error_is_retried_once():
    calls = {"count": 0}

    def handler(_request: httpx.Request) -> httpx.Response:
        calls["count"] += 1
        if calls["count"] == 1:
            return httpx.Response(503)
        return httpx.Response(200, json=FIXTURE)

    with _client(handler) as client:
        response = client.get("/seasons/2026/schedule")

    assert response.status_code == 200
    assert calls["count"] == 2


def test_year_outside_the_supported_range_is_rejected():
    def handler(_request: httpx.Request) -> httpx.Response:
        raise AssertionError("provider should not be called")

    with _client(handler) as client:
        response = client.get("/seasons/1800/schedule")

    assert response.status_code == 422


def test_health():
    with _client(lambda _request: httpx.Response(500)) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def _client(handler) -> TestClient:
    async def no_sleep(_delay: float) -> None:
        return None

    http = httpx.AsyncClient(
        transport=httpx.MockTransport(handler),
        base_url="https://api.jolpi.ca",
    )
    service = ScheduleService(JolpicaClient(client=http, sleep=no_sleep), ttl_seconds=60)
    return TestClient(create_app(service))
