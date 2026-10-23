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
 * 챌린지 하나에 허용되는 틀린 시도(백엔드 `app.otp.max-attempts` 기본 5). 서버는 이에 이르면 챌린지를 폐기하지만 응답은 계속 같은
 * 401 `invalid_otp` 라 폼이 직접 센다(TASK-32#2, FE-18).
 */
export const OTP_MAX_ATTEMPTS = 5;

/**
 * 휴대폰 OTP: 번호 → `POST /api/auth/phone/otp`(202 {challengeId, expiresIn}) → 인증번호 → `POST /api/auth/phone/verify`.
 * `link` 면 `?link=true` 로 현재 계정에 PHONE 을 연결한다(내 계정 화면).
 * 인증번호 유효시간(3분)과 429 재시도 대기(Retry-After)를 각각 카운트다운으로 보인다.
 * 지금 챌린지에서 `invalid_otp` 가 {@link OTP_MAX_ATTEMPTS} 번이면(서버가 폐기했다) 인증번호 칸을 비우고 막은 뒤 다시 받기를 안내한다.
 * 다른 실패(503·409 등, TASK-25#5 로 같은 인증번호를 다시 쓸 수 있다)는 세지 않는다. 다시 받기 대기는 서버 429 `Retry-After` 를 따른다.
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
  // 지금 챌린지의 틀린 시도 수. 새 인증번호를 받으면 0.
  const [failures, setFailures] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const expiry = useCountdown();
  const retry = useCountdown();

  const locked = challengeId !== null && failures >= OTP_MAX_ATTEMPTS;

  const send = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSending(true);
    try {
      const challenge = await api<OtpChallenge>("/auth/phone/otp", { method: "POST", body: { phone } });
      setChallengeId(challenge.challengeId);
      setFailures(0);
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
    if (!challengeId || locked) return;
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
      if (isApiError(e) && e.code === "invalid_otp") {
        const count = failures + 1;
        setFailures(count);
        if (count >= OTP_MAX_ATTEMPTS) {
          // 폐기된 챌린지: 오류 한 줄 대신 잠금 안내(아래)만 보인다.
          setCode("");
          return;
        }
      }
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
              disabled={locked}
            />
            <Button type="submit" disabled={verifying || expired || locked || code.length !== 6}>
              {verifyLabel}
            </Button>
          </div>
          {locked ? (
            <p id={`${ids}-timer`} role="alert" className="text-destructive text-sm" data-testid="otp-locked">
              인증번호를 {OTP_MAX_ATTEMPTS}번 틀려 이 인증번호는 더 이상 쓸 수 없습니다. 위에서 인증번호를 다시 받으세요.
            </p>
          ) : expired ? (
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
