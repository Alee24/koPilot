import uuid

from sqlalchemy import CheckConstraint, ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import AuditMixin, Base, SoftDeleteMixin


class UserAccount(Base, AuditMixin, SoftDeleteMixin):
    """Optional user account for cross-device preference sync and trip backup."""

    __tablename__ = "user_accounts"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False, index=True)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    display_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)

    preferences: Mapped["UserPreferences | None"] = relationship(
        back_populates="user",
        uselist=False,
        cascade="all, delete-orphan",
    )
    devices: Mapped[list["DeviceSession"]] = relationship(
        back_populates="user",
        cascade="all, delete-orphan",
    )
    trips: Mapped[list["TripRecord"]] = relationship(
        back_populates="user",
        cascade="all, delete-orphan",
    )

    __table_args__ = (
        CheckConstraint("length(email) >= 5", name="ck_user_email_min_len"),
    )


class UserPreferences(Base, AuditMixin):
    """Synchronized user driving, voice, camera, and privacy preferences."""

    __tablename__ = "user_preferences"

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )
    user_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        unique=True,
        nullable=False,
    )
    language: Mapped[str] = mapped_column(String(16), default="en", nullable=False)
    distance_unit: Mapped[str] = mapped_column(String(8), default="km", nullable=False)
    voice_name: Mapped[str] = mapped_column(String(32), default="marin", nullable=False)
    speed_alert_threshold_kmh: Mapped[int] = mapped_column(default=5, nullable=False)
    hazard_advisories_enabled: Mapped[bool] = mapped_column(default=False, nullable=False)
    telemetry_opt_in: Mapped[bool] = mapped_column(default=False, nullable=False)
    preferences_json: Mapped[str] = mapped_column(Text, default="{}", nullable=False)

    user: Mapped["UserAccount"] = relationship(back_populates="preferences")

    __table_args__ = (
        Index("ix_user_preferences_user_id", "user_id"),
        CheckConstraint("speed_alert_threshold_kmh >= 0 AND speed_alert_threshold_kmh <= 40", name="ck_pref_speed_threshold"),
    )
