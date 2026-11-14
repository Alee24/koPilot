import asyncio
import time
from collections import defaultdict, deque

from fastapi import HTTPException, status

from app.config.settings import Settings, get_settings

try:
    import redis.asyncio as aioredis
except ImportError:  # pragma: no cover
    aioredis = None


class RateLimiter:
    """Sliding-window rate limiter with Redis support and in-memory fallback."""

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()
        self._memory_buckets: dict[str, deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()
        self._redis = None
        if self._settings.redis_enabled and aioredis is not None:
            self._redis = aioredis.from_url(
                self._settings.redis_url,
                encoding="utf-8",
                decode_responses=True,
            )

    async def check_limit(self, key: str, limit: int, window_seconds: int) -> None:
        now = time.time()
        if self._redis is not None:
            try:
                redis_key = f"rl:{key}"
                pipe = self._redis.pipeline()
                pipe.zremrangebyscore(redis_key, 0, now - window_seconds)
                pipe.zadd(redis_key, {str(now): now})
                pipe.zcard(redis_key)
                pipe.expire(redis_key, window_seconds)
                results = await pipe.execute()
                count = int(results[2])
                if count > limit:
                    raise HTTPException(
                        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                        detail="Rate limit exceeded. Please wait before retrying.",
                    )
                return
            except HTTPException:
                raise
            except Exception:
                # Fallback gracefully to in-memory limiter if Redis is temporarily unreachable
                pass

        async with self._lock:
            bucket = self._memory_buckets[key]
            cutoff = now - window_seconds
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= limit:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="Rate limit exceeded. Please wait before retrying.",
                )
            bucket.append(now)

    async def reset(self) -> None:
        async with self._lock:
            self._memory_buckets.clear()


_rate_limiter: RateLimiter | None = None


def get_rate_limiter() -> RateLimiter:
    global _rate_limiter
    if _rate_limiter is None:
        _rate_limiter = RateLimiter()
    return _rate_limiter
