import asyncio
from datetime import datetime, timezone

import httpx

from app.openf1 import DriverPhotosService, normalize_driver_photos, select_race_session

NOW = datetime(2025, 12, 8, tzinfo=timezone.utc)


def test_a_race_still_inside_the_live_window_is_skipped():
    sessions = [
        {"session_name": "Race", "session_key": 1, "date_end": "2025-12-07T15:00:00+00:00", "is_cancelled": False},
        {"session_name": "Race", "session_key": 2, "date_end": "2025-12-07T23:40:00+00:00", "is_cancelled": False},
    ]

    chosen = select_race_session(sessions, datetime(2025, 12, 8, 0, 0, tzinfo=timezone.utc))

    assert chosen["session_key"] == 1


def test_a_missing_headshot_stays_empty_and_codes_are_unique():
    photos = normalize_driver_photos(
        [
            {"name_acronym": "ver", "last_name": "Verstappen", "headshot_url": "https://images.example/ver.png"},
            {"name_acronym": "VER", "last_name": "Verstappen", "headshot_url": None},
            {"name_acronym": "COL", "last_name": "Colapinto", "headshot_url": "  "},
            {"name_acronym": "", "last_name": "Nobody"},
        ]
    )

    assert [(photo.code, photo.photo_url) for photo in photos] == [
        ("COL", None),
        ("VER", "https://images.example/ver.png"),
    ]


def test_photos_come_from_the_latest_finished_race_and_are_cached():
    calls = {"sessions": 0, "drivers": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/sessions"):
            calls["sessions"] += 1
            return httpx.Response(
                200,
                json=[
                    {
                        "session_name": "Race",
                        "session_key": 10,
                        "date_end": "2025-03-16T07:00:00+00:00",
                        "is_cancelled": True,
                    },
                    {
                        "session_name": "Race",
                        "session_key": 20,
                        "date_end": "2025-12-07T15:00:00+00:00",
                        "is_cancelled": False,
                    },
                ],
            )
        assert request.url.params["session_key"] == "20"
        calls["drivers"] += 1
        return httpx.Response(
            200,
            json=[{"name_acronym": "NOR", "last_name": "Norris", "headshot_url": "https://images.example/nor.png"}],
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://api.openf1.org/v1")
    service = DriverPhotosService(client, now=lambda: NOW)

    async def run():
        first = await service.get_drivers(2025)
        second = await service.get_drivers(2025)
        before_photos = await service.get_drivers(2022)
        return first, second, before_photos

    first, second, before_photos = asyncio.run(run())

    assert first.season == 2025
    assert first.drivers[0].code == "NOR"
    assert first.drivers[0].photo_url == "https://images.example/nor.png"
    assert second == first
    assert before_photos.drivers == []
    assert calls == {"sessions": 1, "drivers": 1}
