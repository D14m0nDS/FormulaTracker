from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Path, Request

from app.jolpica import JolpicaClient, ProviderUnavailable, ScheduleService, SeasonNotFound
from app.models import SeasonSchedule


def create_app(service: ScheduleService | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if service is None:
            app.state.schedules = ScheduleService(JolpicaClient())
            yield
            await app.state.schedules.aclose()
        else:
            app.state.schedules = service
            yield

    app = FastAPI(title="FormulaTracker", lifespan=lifespan)

    def get_schedules(request: Request) -> ScheduleService:
        return request.app.state.schedules

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/seasons/{year}/schedule", response_model=SeasonSchedule)
    async def season_schedule(
        year: int = Path(ge=1950, le=2100),
        schedules: ScheduleService = Depends(get_schedules),
    ) -> SeasonSchedule:
        try:
            return await schedules.get_schedule(year)
        except SeasonNotFound:
            raise HTTPException(status_code=404, detail="Season not found") from None
        except ProviderUnavailable:
            raise HTTPException(status_code=502, detail="Schedule provider unavailable") from None

    return app


app = create_app()
