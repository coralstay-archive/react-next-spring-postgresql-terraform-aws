"use client";

import { useId, useState, type FormEvent } from "react";

import { FormError } from "@/components/auth/form-error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import type { Me } from "@/lib/auth";
import { errorMessage } from "@/lib/errors";

/** LOCAL 로그인: `POST /api/auth/login`(세션 ID 교체 + 새 XSRF-TOKEN). */
export function LoginForm({ onLoggedIn }: { onLoggedIn: (me: Me) => void }) {
  const ids = useId();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      onLoggedIn(await api<Me>("/auth/login", { method: "POST", body: { email, password } }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-2">
        <Label htmlFor={`${ids}-email`}>이메일</Label>
        <Input
          id={`${ids}-email`}
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </div>
      <div className="grid gap-2">
        <Label htmlFor={`${ids}-password`}>비밀번호</Label>
        <Input
          id={`${ids}-password`}
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
      </div>
      <FormError message={error} />
      <Button type="submit" disabled={pending || !email || !password}>
        로그인
      </Button>
    </form>
  );
}
