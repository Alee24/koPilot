import json

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import (
    AuthSubject,
    create_access_token,
    hash_password,
    require_user_subject,
    verify_password,
)
from app.models import UserAccount, UserPreferences, get_db_session
from app.schemas import (
    DeviceRegisterRequest,
    PreferencesPayload,
    TokenResponse,
    UserLoginRequest,
    UserRegisterRequest,
)
from app.services import ensure_device_session

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/device", response_model=TokenResponse, summary="Issue an anonymous device session token")
async def register_device(
    payload: DeviceRegisterRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    await ensure_device_session(
        db=db,
        device_id=payload.device_id,
        app_version=payload.app_version,
        android_sdk=payload.android_sdk,
        device_model=payload.device_model,
    )
    token, ttl = create_access_token(
        subject_id=payload.device_id,
        subject_type="device",
        extra={"device_id": payload.device_id},
    )
    return TokenResponse(
        access_token=token,
        expires_in_seconds=ttl,
        subject_type="device",
        subject_id=payload.device_id,
    )


@router.post("/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
async def register_user(
    payload: UserRegisterRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    existing = await db.execute(select(UserAccount).where(UserAccount.email == payload.email.lower()))
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An account with this email already exists.",
        )

    user = UserAccount(
        email=payload.email.lower(),
        password_hash=hash_password(payload.password),
        display_name=payload.display_name,
    )
    db.add(user)
    await db.flush()

    prefs = UserPreferences(
        user_id=user.id,
        created_by=user.id,
        updated_by=user.id,
    )
    db.add(prefs)
    await db.commit()
    await db.refresh(user)

    if payload.device_id:
        await ensure_device_session(db=db, device_id=payload.device_id, user_id=user.id)

    token, ttl = create_access_token(
        subject_id=user.id,
        subject_type="user",
        extra={"device_id": payload.device_id} if payload.device_id else None,
    )
    return TokenResponse(
        access_token=token,
        expires_in_seconds=ttl,
        subject_type="user",
        subject_id=user.id,
    )


@router.post("/login", response_model=TokenResponse)
async def login_user(
    payload: UserLoginRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    result = await db.execute(
        select(UserAccount).where(
            UserAccount.email == payload.email.lower(),
            UserAccount.is_deleted.is_(False),
        )
    )
    user = result.scalar_one_or_none()
    if user is None or not verify_password(payload.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password.",
        )

    token, ttl = create_access_token(subject_id=user.id, subject_type="user")
    return TokenResponse(
        access_token=token,
        expires_in_seconds=ttl,
        subject_type="user",
        subject_id=user.id,
    )


@router.get("/preferences", response_model=PreferencesPayload)
async def get_preferences(
    subject: AuthSubject = Depends(require_user_subject),
    db: AsyncSession = Depends(get_db_session),
) -> PreferencesPayload:
    result = await db.execute(select(UserPreferences).where(UserPreferences.user_id == subject.subject_id))
    prefs = result.scalar_one_or_none()
    if prefs is None:
        return PreferencesPayload()
    return PreferencesPayload(
        language=prefs.language,
        distance_unit=prefs.distance_unit,
        voice_name=prefs.voice_name,
        speed_alert_threshold_kmh=prefs.speed_alert_threshold_kmh,
        hazard_advisories_enabled=prefs.hazard_advisories_enabled,
        telemetry_opt_in=prefs.telemetry_opt_in,
        extra=json.loads(prefs.preferences_json or "{}"),
    )


@router.put("/preferences", response_model=PreferencesPayload)
async def update_preferences(
    payload: PreferencesPayload,
    subject: AuthSubject = Depends(require_user_subject),
    db: AsyncSession = Depends(get_db_session),
) -> PreferencesPayload:
    result = await db.execute(select(UserPreferences).where(UserPreferences.user_id == subject.subject_id))
    prefs = result.scalar_one_or_none()
    if prefs is None:
        prefs = UserPreferences(user_id=subject.subject_id, created_by=subject.subject_id)
        db.add(prefs)

    prefs.language = payload.language
    prefs.distance_unit = payload.distance_unit
    prefs.voice_name = payload.voice_name
    prefs.speed_alert_threshold_kmh = payload.speed_alert_threshold_kmh
    prefs.hazard_advisories_enabled = payload.hazard_advisories_enabled
    prefs.telemetry_opt_in = payload.telemetry_opt_in
    prefs.preferences_json = json.dumps(payload.extra, separators=(",", ":"))
    prefs.updated_by = subject.subject_id

    await db.commit()
    return payload
