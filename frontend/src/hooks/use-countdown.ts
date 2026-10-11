"use client";

import { useCallback, useEffect, useState } from "react";

/** 초 단위 카운트다운. 남은 시간은 마감 시각 기준으로 계산해 탭이 잠들었다 깨어나도 맞다. */
export function useCountdown() {
  const [deadline, setDeadline] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) return;
    const id = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= deadline) clearInterval(id);
    }, 1000);
    return () => clearInterval(id);
  }, [deadline]);

  const start = useCallback((seconds: number) => {
    const current = Date.now();
    setNow(current);
    setDeadline(current + seconds * 1000);
  }, []);

  const reset = useCallback(() => setDeadline(null), []);

  const remaining = deadline === null ? 0 : Math.max(0, Math.ceil((deadline - now) / 1000));
  return { remaining, running: remaining > 0, started: deadline !== null, start, reset };
}

export function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}
