from fastapi import APIRouter

from app.api.v1 import account, auth, config, places, routes, telemetry, trips, voice, weather

api_v1_router = APIRouter(prefix="/api/v1")
api_v1_router.include_router(config.router)
api_v1_router.include_router(auth.router)
api_v1_router.include_router(voice.router)
api_v1_router.include_router(routes.router)
api_v1_router.include_router(weather.router)
api_v1_router.include_router(places.router)
api_v1_router.include_router(telemetry.router)
api_v1_router.include_router(trips.router)
api_v1_router.include_router(account.router)
