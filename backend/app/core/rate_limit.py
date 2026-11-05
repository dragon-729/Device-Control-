"""Small in-memory sliding-window rate limiter (single-process deployments)."""
from __future__ import annotations

import threading
from collections import defaultdict, deque
from typing import Callable, Deque, Dict


class RateLimiter:
    def __init__(self, clock: Callable[[], float]):
        self._clock = clock
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str, limit: int, window_seconds: float) -> bool:
        """Record a hit; return False when `key` exceeded `limit` hits in the window."""
        now = self._clock()
        with self._lock:
            q = self._hits[key]
            while q and q[0] <= now - window_seconds:
                q.popleft()
            if len(q) >= limit:
                return False
            q.append(now)
            if len(self._hits) > 50_000:  # bound memory: drop idle keys
                for k in [k for k, v in self._hits.items() if not v]:
                    del self._hits[k]
            return True

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()
