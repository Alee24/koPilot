import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, Float, ForeignKey, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import AuditMixin, Base, SoftDeleteMixin


class TripRecord(Base, AuditMixin, SoftDeleteMixin):
    """Persisted driving trip summary (no continuous raw GPS trace unless explicitly authorized)."""

    __tablename__ = "trip_records"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    device_id: Mapped[str] = mapped_column(
        String(128),
        ForeignKey("device_sessions.device_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    user_id: Mapped[str | None] = mapped_column(
        String(36),
        ForeignKey("user_accounts.id", ondelete="SET NULL"),
        nullable=True,
        index=True,
    )
    origin_label: Mapped[str | None] = mapped_column(String(255), nullable=True)
    destination_label: Mapped[str | None] = mapped_column(String(255), nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    distance_km: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    duration_seconds: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_speed_kmh: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    avg_speed_kmh: Mapped[float] = mapped_column(Float, default=0.0, nullable=False)
    signs_detected_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    alerts_triggered_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    user: Mapped["UserAccount | None"] = relationship(back_populates="trips")

    __table_args__ = (
        CheckConstraint("distance_km >= 0", name="ck_trip_distance_non_neg"),
        CheckConstraint("duration_seconds >= 0", name="ck_trip_duration_non_neg"),
        Index("ix_trip_records_started_at", "started_at"),
    )


class TelemetryEvent(Base, AuditMixin):
    """Privacy-conscious operational telemetry and error events (no PII, no raw video)."""

    __tablename__ = "telemetry_events"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    device_id: Mapped[str] = mapped_column(
        String(128),
        ForeignKey("device_sessions.device_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    event_type: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    severity: Mapped[str] = mapped_column(String(16), default="info", nullable=False)
    app_version: Mapped[str] = mapped_column(String(32), default="1.0.0", nullable=False)
    payload_json: Mapped[str] = mapped_column(Text, default="{}", nullable=False)


class UsageMeter(Base, AuditMixin):
    """Tracks API usage per device for cost control and quota enforcement."""

    __tablename__ = "usage_meters"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    device_id: Mapped[str] = mapped_column(
        String(128),
        ForeignKey("device_sessions.device_id", ondelete="CASCADE"),
        nullable=False,
    )
    resource_type: Mapped[str] = mapped_column(String(32), nullable=False)
    units: Mapped[int] = mapped_column(Integer, default=1, nullable=False)

    __table_args__ = (
        Index("ix_usage_meters_device_resource", "device_id", "resource_type"),
        CheckConstraint("units > 0", name="ck_usage_units_pos"),
    )
