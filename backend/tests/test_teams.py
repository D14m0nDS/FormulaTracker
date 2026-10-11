import asyncio

import httpx

from app.jolpica import JolpicaClient
from app.teams import TeamColorsService, normalize_team_colors


def test_a_team_from_another_year_and_a_bad_color_are_dropped():
    teams = normalize_team_colors(
        {
            "data": [
                {
                    "name": "Ferrari",
                    "primary_color": "#ed1131",
                    "seasons": [{"year": 2026}],
                },
                {
                    "name": "Sauber",
                    "primary_color": "#01C00E",
                    "seasons": [{"year": 2025}],
                },
                {
                    "name": "Mystery",
                    "primary_color": "red",
                    "seasons": [{"year": 2026}],
                },
                {
                    "name": "Ferrari",
                    "primary_color": "#000000",
                    "seasons": [{"year": 2026}],
                },
            ]
        },
        2026,
    )

    assert [(team.name, team.color) for team in teams.teams] == [("Ferrari", "#ED1131")]


def test_team_colors_are_cached():
    calls = {"teams": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["teams"] += 1
        return httpx.Response(
            200,
            json={
                "data": [
                    {
                        "name": "Mercedes",
                        "primary_color": "#00D7B6",
                        "seasons": [{"year": 2026}],
                    }
                ]
            },
        )

    client = JolpicaClient(
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://api.jolpi.ca")
    )
    service = TeamColorsService(client)

    async def run():
        first = await service.get_teams(2026)
        second = await service.get_teams(2026)
        return first, second

    first, second = asyncio.run(run())
    assert first.teams[0].color == "#00D7B6"
    assert second == first
    assert calls["teams"] == 1
