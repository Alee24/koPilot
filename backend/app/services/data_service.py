from datetime import datetime, timezone
import json

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeviceSession, TelemetryEvent, TripRecord, UsageMeter


async def ensure_device_session(
    db: AsyncSession,
    device_id: str,
    app_version: str = "1.0.0",
    android_sdk: int | None = None,
    device_model: str | None = None,
    user_id: str | None = None,
) -> DeviceSession:
    result = await db.execute(select(DeviceSession).where(DeviceSession.device_id == device_id))
    device = result.scalar_one_or_none()
    now = datetime.now(timezone.utc)
    if device is None:
        device = DeviceSession(
            device_id=device_id,
            app_version=app_version,
            android_sdk=android_sdk,
            device_model=device_model,
            user_id=user_id,
            last_seen_at=now,
            created_by=device_id,
            updated_by=device_id,
        )
        db.add(device)
    else:
        device.last_seen_at = now
        device.app_version = app_version or device.app_version
        if user_id and device.user_id is None:
            device.user_id = user_id
        device.updated_by = device_id
    await db.commit()
    await db.refresh(device)
    return device


async def record_usage(db: AsyncSession, device_id: str, resource_type: str, units: int = 1) -> None:
    await ensure_device_session(db, device_id=device_id)
    meter = UsageMeter(
        device_id=device_id,
        resource_type=resource_type,
        units=units,
        created_by=device_id,
        updated_by=device_id,
    )
    db.add(meter)
    await db.commit()


async def record_telemetry_event(
    db: AsyncSession,
    device_id: str,
    event_type: str,
    severity: str,
    app_version: str,
    payload: dict,
) -> TelemetryEvent:
    await ensure_device_session(db, device_id=device_id, app_version=app_version)
    event = TelemetryEvent(
        device_id=device_id,
        event_type=event_type,
        severity=severity,
        app_version=app_version,
        payload_json=json.dumps(payload, separators=(",", ":")),
        created_by=device_id,
        updated_by=device_id,
    )
    db.add(event)
    await db.commit()
    await db.refresh(event)
    return event


async def create_trip_record(
    db: AsyncSession,
    device_id: str,
    user_id: str | None,
    origin_label: str | None,
    destination_label: str | None,
    started_at: datetime,
    ended_at: datetime | None,
    distance_km: float,
    duration_seconds: int,
    max_speed_kmh: float,
    avg_speed_kmh: float,
    signs_detected_count: int,
    alerts_triggered_count: int,
) -> TripRecord:
    await ensure_device_session(db, device_id=device_id, user_id=user_id)
    trip = TripRecord(
        device_id=device_id,
        user_id=user_id,
        origin_label=origin_label,
        destination_label=destination_label,
        started_at=started_at,
        ended_at=ended_at,
        distance_km=distance_km,
        duration_seconds=duration_seconds,
        max_speed_kmh=max_speed_kmh,
        avg_speed_kmh=avg_speed_kmh,
        signs_detected_count=signs_detected_count,
        alerts_triggered_count=alerts_triggered_count,
        created_by=user_id or device_id,
        updated_by=user_id or device_id,
    )
    db.add(trip)
    await db.commit()
    await db.refresh(trip)
    return trip
