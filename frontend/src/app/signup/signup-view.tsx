"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useId, useRef, useState, type FormEvent } from "react";

import { FormError } from "@/components/auth/form-error";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, isApiError } from "@/lib/api";
import { loginHref, safeNext, useSessionChanged, type Me } from "@/lib/auth";
import { errorMessage } from "@/lib/errors";
import { normalizeSignup, validateSignup, type FieldErrors, type SignupInput } from "@/lib/validation";

const FIELDS: { name: keyof SignupInput; label: string; type: string; autoComplete: string; hint?: string }[] = [
  { name: "email", label: "이메일", type: "email", autoComplete: "email" },
  {
    name: "password",
    label: "비밀번호",
    type: "password",
    autoComplete: "new-password",
    hint: "8자 이상, 문자와 숫자를 모두 포함",
  },
  { name: "nickname", label: "닉네임", type: "text", autoComplete: "nickname", hint: "2~20자" },
];

/**
 * LOCAL 가입: `POST /api/auth/signup`(201, 자동 로그인 없음) → 같은 값으로 `POST /api/auth/login` → me 갱신 후 replace 이동(PRG).
 * 가입은 됐는데 자동 로그인이 실패하면(TASK-29#8, FE-14) 폼을 없애고 로그인 링크(next 유지)를 보인다 — 같은 폼을 다시 내면
 * 409 email_taken("이미 가입된 이메일")이 된다. 같은 틱의 두 번째 제출은 ref 로 막는다.
 */
export function SignupView() {
  const ids = useId();
  const router = useRouter();
  const params = useSearchParams();
  const sessionChanged = useSessionChanged();
  const [values, setValues] = useState<SignupInput>({ email: "", password: "", nickname: "" });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<SignupInput>>({});
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  // 가입은 됐지만 자동 로그인이 실패한 이유(있으면 폼 대신 로그인 안내).
  const [loginFailed, setLoginFailed] = useState<string | null>(null);
  const submitting = useRef(false);
  const next = safeNext(params.get("next"));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting.current) return;
    setError(null);
    const errors = validateSignup(values);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    const input = normalizeSignup(values);
    submitting.current = true;
    setPending(true);
    try {
      await api<Me>("/auth/signup", { method: "POST", body: input });
    } catch (e) {
      submitting.current = false;
      setPending(false);
      if (isApiError(e) && e.code === "email_taken") {
        setFieldErrors({ email: errorMessage(e) });
      } else if (isApiError(e) && e.fields) {
        setFieldErrors(e.fields as FieldErrors<SignupInput>);
      } else {
        setError(errorMessage(e));
      }
      return;
    }
    try {
      const me = await api<Me>("/auth/login", {
        method: "POST",
        body: { email: input.email, password: input.password },
      });
      sessionChanged(me);
      router.replace(next);
    } catch (e) {
      // 가입은 됐다. 로그인만 다시 하면 된다(폼은 다시 보이지 않는다).
      setLoginFailed(errorMessage(e));
    }
  };

  if (loginFailed !== null) {
    return (
      <Card className="w-full max-w-md" data-testid="signup-login-failed">
        <CardHeader>
          <CardTitle>
            <h1 className="text-xl font-semibold">가입은 완료됐습니다</h1>
          </CardTitle>
          <CardDescription>자동으로 로그인하지 못했습니다. 가입한 이메일과 비밀번호로 로그인하세요.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <FormError message={loginFailed} />
          <Link href={loginHref(next === "/" ? undefined : next)} className={buttonVariants()}>
            로그인하러 가기
          </Link>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle>
          <h1 className="text-xl font-semibold">가입</h1>
        </CardTitle>
        <CardDescription>
          이미 계정이 있으면{" "}
          <Link href="/login/" className="underline underline-offset-4">
            로그인
          </Link>
          하세요.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          {FIELDS.map((field) => {
            const id = `${ids}-${field.name}`;
            const fieldError = fieldErrors[field.name];
            return (
              <div key={field.name} className="grid gap-2">
                <Label htmlFor={id}>{field.label}</Label>
                <Input
                  id={id}
                  name={field.name}
                  type={field.type}
                  autoComplete={field.autoComplete}
                  value={values[field.name]}
                  onChange={(e) => setValues((v) => ({ ...v, [field.name]: e.target.value }))}
                  aria-invalid={fieldError ? true : undefined}
                  aria-describedby={`${id}-desc`}
                  required
                />
                <p id={`${id}-desc`} className={fieldError ? "text-destructive text-sm" : "text-muted-foreground text-xs"}>
                  {fieldError ?? field.hint}
                </p>
              </div>
            );
          })}
          <FormError message={error} />
          <Button type="submit" disabled={pending}>
            가입하기
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
