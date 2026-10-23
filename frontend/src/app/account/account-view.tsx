"use client";

import { useSearchParams } from "next/navigation";
import { useId, useState, type FormEvent } from "react";

import { FormError } from "@/components/auth/form-error";
import { PhoneOtpForm } from "@/components/auth/phone-otp-form";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { useMe, useSessionChanged, type Me } from "@/lib/auth";
import { errorMessage, NEXT_STORAGE_KEY, oauthErrorMessage } from "@/lib/errors";
import { useIdentities, useRefreshAccount, useUnlinkIdentity, type Identity } from "@/lib/identities";
import {
  PROVIDER_LABELS,
  SOCIAL_PROVIDERS,
  socialIdOf,
  socialLinkHref,
  socialLoginHref,
} from "@/lib/providers";

function rememberAccountReturn() {
  try {
    window.sessionStorage.setItem(NEXT_STORAGE_KEY, "/account/");
  } catch {
    // 저장소를 못 쓰면 콜백 뒤 홈으로 간다.
  }
}

/**
 * 내 계정: 연결된 로그인 수단 목록·해제, 소셜(페이지 이동)·휴대폰(OTP ?link=true) 연결, 제한 세션 다시 인증(step-up).
 * 소셜 연결 결과는 백엔드가 `/account/?linked=<p>` 또는 `?error=<코드>` 로 돌려준다.
 */
export function AccountView() {
  const params = useSearchParams();
  const { data: me } = useMe();
  const linked = params.get("linked");
  const linkError = params.get("error");
  const [notice, setNotice] = useState<string | null>(() => {
    if (!linked) return null;
    const provider = SOCIAL_PROVIDERS.find((p) => p.id === linked);
    return `${provider?.label ?? linked} 계정을 연결했습니다.`;
  });
  const [error, setError] = useState<string | null>(() =>
    linkError !== null ? oauthErrorMessage(linkError, "link") : null,
  );

  if (!me) return null;

  return (
    <div className="grid w-full max-w-2xl gap-6">
      <h1 className="text-2xl font-semibold">내 계정</h1>
      {notice && (
        <div role="status" aria-label="알림">
          <Alert>
            <AlertDescription>{notice}</AlertDescription>
          </Alert>
        </div>
      )}
      <FormError message={error} />
      {me.stepUpRequired && <StepUpSection me={me} />}
      <IdentitiesSection
        onNotice={(message) => {
          setError(null);
          setNotice(message);
        }}
        onError={(message) => {
          setNotice(null);
          setError(message);
        }}
      />
      <LinkSection
        me={me}
        onLinked={(message) => {
          setError(null);
          setNotice(message);
        }}
      />
    </div>
  );
}

function IdentitiesSection({
  onNotice,
  onError,
}: {
  onNotice: (message: string) => void;
  onError: (message: string) => void;
}) {
  const identities = useIdentities();
  const unlink = useUnlinkIdentity();

  const remove = (identity: Identity) => {
    unlink.mutate(identity, {
      onSuccess: () => onNotice(`${PROVIDER_LABELS[identity.provider]} 연결을 해제했습니다.`),
      onError: (e) => onError(errorMessage(e)),
    });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-lg font-semibold">로그인 수단</h2>
        </CardTitle>
        <CardDescription>이 계정에 로그인할 수 있는 방법입니다. 마지막 하나는 해제할 수 없습니다.</CardDescription>
      </CardHeader>
      <CardContent>
        {identities.isPending ? (
          <p role="status">불러오는 중…</p>
        ) : identities.isError ? (
          <FormError message={errorMessage(identities.error)} />
        ) : (
          <ul aria-label="연결된 로그인 수단" className="divide-y">
            {identities.data.map((identity) => {
              const label = PROVIDER_LABELS[identity.provider];
              return (
                <li key={identity.id} className="flex items-center gap-3 py-3">
                  <div className="grid flex-1 gap-0.5">
                    <span className="font-medium">{label}</span>
                    {identity.email && <span className="text-muted-foreground text-sm">{identity.email}</span>}
                    <span className="text-muted-foreground text-xs">
                      {new Date(identity.linkedAt).toLocaleDateString("ko-KR")} 연결
                    </span>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    aria-label={`${label} 연결 해제`}
                    disabled={unlink.isPending}
                    onClick={() => remove(identity)}
                  >
                    해제
                  </Button>
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function LinkSection({ me, onLinked }: { me: Me; onLinked: (message: string) => void }) {
  const refresh = useRefreshAccount();
  const blocked = me.stepUpRequired;
  const missing = SOCIAL_PROVIDERS.filter((p) => !me.providers.includes(p.provider));

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-lg font-semibold">로그인 수단 연결</h2>
        </CardTitle>
        <CardDescription>
          {blocked
            ? "다시 인증한 뒤에 연결할 수 있습니다."
            : "다른 계정의 수단은 연결할 수 없습니다. 이메일이 같아도 자동으로 합쳐지지 않습니다."}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6">
        {missing.length > 0 && (
          <ul aria-label="소셜 계정 연결" className="flex flex-wrap gap-2">
            {missing.map(({ id, label }) => (
              <li key={id}>
                <a
                  href={blocked ? undefined : socialLinkHref(id)}
                  aria-disabled={blocked ? true : undefined}
                  role="link"
                  className={buttonVariants({
                    variant: "outline",
                    size: "sm",
                    className: blocked ? "pointer-events-none opacity-50" : undefined,
                  })}
                >
                  {label} 연결
                </a>
              </li>
            ))}
          </ul>
        )}
        {!blocked && (
          <section aria-label="휴대폰 번호 연결" className="grid gap-2">
            <h3 className="text-sm font-medium">휴대폰 번호 연결</h3>
            <PhoneOtpForm
              link
              verifyLabel="연결"
              onVerified={async () => {
                await refresh();
                onLinked("휴대폰 번호를 연결했습니다.");
              }}
            />
          </section>
        )}
      </CardContent>
    </Card>
  );
}

/** 제한 세션 풀기: LOCAL 이 있으면 비밀번호(`POST /api/auth/step-up/local`), 소셜·Cognito 는 그 수단으로 다시 로그인. */
function StepUpSection({ me }: { me: Me }) {
  const ids = useId();
  const sessionChanged = useSessionChanged();
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const hasLocal = me.stepUpProviders.includes("LOCAL");
  const social = me.stepUpProviders.map(socialIdOf).filter((id) => id !== null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      const updated = await api<Me>("/auth/step-up/local", { method: "POST", body: { password } });
      sessionChanged(updated);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  return (
    <section id="step-up" aria-labelledby={`${ids}-title`} className="scroll-mt-20">
      <Card>
        <CardHeader>
          <CardTitle>
            <h2 id={`${ids}-title`} className="text-lg font-semibold">
              다시 인증
            </h2>
          </CardTitle>
          <CardDescription>
            휴대폰 번호로 로그인한 세션은 읽기만 할 수 있습니다(번호가 다른 사람에게 다시 배정됐을 때를 대비한 보호).
            기존 로그인 수단으로 다시 인증하세요.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          {hasLocal && (
            <form onSubmit={submit} className="grid gap-2" noValidate>
              <Label htmlFor={`${ids}-password`}>비밀번호</Label>
              <div className="flex gap-2">
                <Input
                  id={`${ids}-password`}
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
                <Button type="submit" disabled={pending || !password}>
                  다시 인증
                </Button>
              </div>
            </form>
          )}
          {social.length > 0 && (
            <ul className="flex flex-wrap gap-2" aria-label="소셜 계정으로 다시 로그인">
              {social.map((id) => {
                const label = SOCIAL_PROVIDERS.find((p) => p.id === id)?.label ?? id;
                return (
                  <li key={id}>
                    <a
                      href={socialLoginHref(id)}
                      onClick={rememberAccountReturn}
                      className={buttonVariants({ variant: "outline", size: "sm" })}
                    >
                      {label}로 다시 로그인
                    </a>
                  </li>
                );
              })}
            </ul>
          )}
          <FormError message={error} />
        </CardContent>
      </Card>
    </section>
  );
}
