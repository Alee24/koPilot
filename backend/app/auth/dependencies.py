from dataclasses import dataclass

from fastapi import Depends, Header, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import jwt

from app.auth.jwt import decode_token

_bearer = HTTPBearer(auto_error=False)


@dataclass
class AuthSubject:
    subject_id: str
    subject_type: str  # "user" or "device"
    device_id: str | None = None


async def get_optional_subject(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
) -> AuthSubject | None:
    if credentials is not None:
        try:
            payload = decode_token(credentials.credentials)
            sub = payload.get("sub")
            typ = payload.get("typ", "user")
            if not sub:
                raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject")
            return AuthSubject(subject_id=sub, subject_type=typ, device_id=payload.get("device_id") or x_device_id)
        except jwt.PyJWTError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or expired authentication token",
            ) from exc
    if x_device_id and len(x_device_id) >= 8:
        return AuthSubject(subject_id=x_device_id, subject_type="device", device_id=x_device_id)
    return None


async def require_user_subject(
    subject: AuthSubject | None = Depends(get_optional_subject),
) -> AuthSubject:
    if subject is None or subject.subject_type != "user":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User authentication required for this operation",
        )
    return subject


async def require_device_or_user(
    subject: AuthSubject | None = Depends(get_optional_subject),
) -> AuthSubject:
    if subject is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Provide a valid Bearer token or X-Device-Id header",
        )
    return subject
