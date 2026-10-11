"use client";

import { useId, useState, type FormEvent } from "react";

import { FormError } from "@/components/auth/form-error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { formatCountdown, useCountdown } from "@/hooks/use-countdown";
import { api, isApiError } from "@/lib/api";
import type { Me } from "@/lib/auth";
import { errorMessage } from "@/lib/errors";

interface OtpChallenge {
  challengeId: string;
  expiresIn: number;
}

/** 429 에 Retry-After 가 없을 때 기다릴 시간(번호당 60초 1회 규칙). */
const DEFAULT_RETRY_SECONDS = 60;

/**
 * 휴대폰 OTP: 번호 → `POST /api/auth/phone/otp`(202 {challengeId, expiresIn}) → 인증번호 → `POST /api/auth/phone/verify`.
 * `link` 면 `?link=true` 로 현재 계정에 PHONE 을 연결한다(내 계정 화면).
 * 인증번호 유효시간(3분)과 429 재시도 대기(Retry-After)를 각각 카운트다운으로 보인다.
 */
export function PhoneOtpForm({
  onVerified,
  link = false,
  verifyLabel = "확인",
}: {
  onVerified: (me: Me) => void;
  link?: boolean;
  verifyLabel?: string;
}) {
  const ids = useId();
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const expiry = useCountdown();
  const retry = useCountdown();

  const send = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSending(true);
    try {
      const challenge = await api<OtpChallenge>("/auth/phone/otp", { method: "POST", body: { phone } });
      setChallengeId(challenge.challengeId);
      setCode("");
      expiry.start(challenge.expiresIn);
    } catch (e) {
      setError(errorMessage(e));
      if (isApiError(e) && e.status === 429) retry.start(e.retryAfter ?? DEFAULT_RETRY_SECONDS);
    } finally {
      setSending(false);
    }
  };

  const verify = async (event: FormEvent) => {
    event.preventDefault();
    if (!challengeId) return;
    setError(null);
    setVerifying(true);
    try {
      const me = await api<Me>("/auth/phone/verify", {
        method: "POST",
        query: link ? { link: true } : undefined,
        body: { challengeId, code },
      });
      onVerified(me);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setVerifying(false);
    }
  };

  const expired = challengeId !== null && expiry.started && !expiry.running;

  return (
    <div className="grid gap-4">
      <form onSubmit={send} className="grid gap-2" noValidate>
        <Label htmlFor={`${ids}-phone`}>휴대폰 번호</Label>
        <div className="flex gap-2">
          <Input
            id={`${ids}-phone`}
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            placeholder="010-1234-5678"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            required
          />
          <Button type="submit" variant="outline" disabled={sending || retry.running || !phone.trim()}>
            {retry.running
              ? `${retry.remaining}초 후 다시 시도`
              : challengeId
                ? "인증번호 다시 받기"
                : "인증번호 받기"}
          </Button>
        </div>
      </form>

      {challengeId && (
        <form onSubmit={verify} className="grid gap-2" noValidate>
          <Label htmlFor={`${ids}-code`}>인증번호</Label>
          <div className="flex gap-2">
            <Input
              id={`${ids}-code`}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              maxLength={6}
              placeholder="6자리"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
              aria-describedby={`${ids}-timer`}
            />
            <Button type="submit" disabled={verifying || expired || code.length !== 6}>
              {verifyLabel}
            </Button>
          </div>
          {expired ? (
            <p id={`${ids}-timer`} className="text-destructive text-sm">
              인증번호가 만료되었습니다. 인증번호를 다시 받으세요.
            </p>
          ) : (
            <p id={`${ids}-timer`} className="text-muted-foreground text-sm">
              남은 시간{" "}
              <span role="timer" aria-live="off" className="font-mono">
                {formatCountdown(expiry.remaining)}
              </span>
            </p>
          )}
        </form>
      )}

      <FormError message={error} />
    </div>
  );
}
