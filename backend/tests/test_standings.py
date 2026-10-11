import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.jolpica import JolpicaClient, ScheduleService
from app.main import create_app
from app.standings import StandingsPayloadError, StandingsService, normalize_standings

FIXTURE = json.loads(
    (Path(__file__).parent / "fixtures" / "jolpica_standings_2026.json").read_text(encoding="utf-8")
)


def test_standings_parse_points_order_rows_and_drop_provider_ids():
    standings = normalize_standings(FIXTURE["drivers"], FIXTURE["constructors"], season=2026)

    assert standings.season == 2026
    assert standings.after_round == 17
    assert [row.driver.code for row in standings.drivers] == ["ANT", "RUS"]
    assert standings.drivers[0].points == 340
    assert standings.drivers[0].wins == 8
    assert standings.drivers[0].driver.number == 12
    assert standings.drivers[0].teams == ["Mercedes"]
    assert standings.drivers[1].points == 12.5
    assert standings.drivers[1].teams == ["Mercedes", "Other"]
    assert standings.constructors[0].team == "Mercedes"
    assert standings.constructors[0].points == 578
    dumped = standings.model_dump_json()
    assert "driver_hidden" not in dumped
    assert "antonelli" not in dumped
    assert "constructorId" not in dumped
    assert "wikipedia" not in dumped


def test_ineligible_driver_stays_last_with_a_dash():
    drivers = json.loads(json.dumps(FIXTURE["drivers"]))
    rows = drivers["MRData"]["StandingsTable"]["StandingsLists"][0]["DriverStandings"]
    rows.append(
        {
            "positionText": "-",
            "points": "0",
            "wins": "0",
            "Driver": {
                "code": "WIN",
                "givenName": "Markus",
                "familyName": "Winkelhock",
            },
            "Constructors": [{"name": "Spyker"}],
        }
    )

    standings = normalize_standings(drivers, FIXTURE["constructors"], season=2026)
    unranked = standings.drivers[-1]

    assert [row.driver.code for row in standings.drivers] == ["ANT", "RUS", "WIN"]
    assert unranked.position is None
    assert unranked.position_text == "-"
    assert unranked.points == 0
    assert unranked.teams == ["Spyker"]


def test_mismatched_standings_rounds_are_rejected():
    constructors = json.loads(json.dumps(FIXTURE["constructors"]))
    constructors["MRData"]["StandingsTable"]["round"] = "16"
    constructors["MRData"]["StandingsTable"]["StandingsLists"][0]["round"] = "16"

    with pytest.raises(StandingsPayloadError):
        normalize_standings(FIXTURE["drivers"], constructors, season=2026)


def test_standings_route_pages_and_caches():
    calls = {"drivers": 0, "constructors": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/driverStandings.json"):
            calls["drivers"] += 1
            if request.url.params["offset"] == "0":
                return httpx.Response(200, json=_driver_page(["2"], "101"))
            return httpx.Response(200, json=_driver_page(["1"], "101"))
        if request.url.path.endswith("/constructorStandings.json"):
            calls["constructors"] += 1
            return httpx.Response(200, json=FIXTURE["constructors"])
        return httpx.Response(404)

    with _client(handler) as client:
        first = client.get("/seasons/2026/standings")
        second = client.get("/seasons/2026/standings")

    assert first.status_code == 200
    assert [row["driver"]["code"] for row in first.json()["drivers"]] == ["ANT", "RUS"]
    assert first.json()["after_round"] == 17
    assert second.json() == first.json()
    assert calls == {"drivers": 2, "constructors": 1}


def test_missing_standings_are_404():
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    with _client(handler) as client:
        response = client.get("/seasons/2026/standings")

    assert response.status_code == 404
    assert response.json()["detail"] == "Standings not found"


def _driver_page(positions: list[str], total: str) -> dict:
    rows = []
    for position in positions:
        code = "ANT" if position == "1" else "RUS"
        family = "Antonelli" if position == "1" else "Russell"
        given = "Andrea Kimi" if position == "1" else "George"
        rows.append(
            {
                "position": position,
                "positionText": position,
                "points": "340" if position == "1" else "12.5",
                "wins": "8" if position == "1" else "0",
                "Driver": {
                    "driverId": "hidden",
                    "code": code,
                    "givenName": given,
                    "familyName": family,
                    "permanentNumber": "12" if position == "1" else "63",
                },
                "Constructors": [{"constructorId": "hidden", "name": "Mercedes"}],
            }
        )
    return {
        "MRData": {
            "total": total,
            "StandingsTable": {
                "season": "2026",
                "round": "17",
                "StandingsLists": [{"season": "2026", "round": "17", "DriverStandings": rows}],
            },
        }
    }


def _client(handler) -> TestClient:
    async def no_sleep(_delay: float) -> None:
        return None

    http = httpx.AsyncClient(
        transport=httpx.MockTransport(handler),
        base_url="https://api.jolpi.ca",
    )
    jolpica = JolpicaClient(client=http, sleep=no_sleep)
    schedules = ScheduleService(jolpica, ttl_seconds=60)
    standings = StandingsService(jolpica, ttl_seconds=60)
    return TestClient(create_app(schedules, None, standings))
