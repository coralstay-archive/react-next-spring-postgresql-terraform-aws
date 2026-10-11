"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";

import { FormError } from "@/components/auth/form-error";
import { LoginForm } from "@/components/auth/login-form";
import { PhoneOtpForm } from "@/components/auth/phone-otp-form";
import { SocialButtons } from "@/components/auth/social-buttons";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { safeNext, useSessionChanged, type Me } from "@/lib/auth";
import { errorMessage, NEXT_STORAGE_KEY } from "@/lib/errors";

export function LoginView() {
  const params = useSearchParams();
  const router = useRouter();
  const sessionChanged = useSessionChanged();
  const next = safeNext(params.get("next"));
  const error = params.get("error");

  const loggedIn = (me: Me) => {
    sessionChanged(me);
    router.replace(next);
  };

  const rememberNext = () => {
    try {
      window.sessionStorage.setItem(NEXT_STORAGE_KEY, next);
    } catch {
      // 저장소를 못 쓰면 로그인 뒤 홈으로 간다.
    }
  };

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle>
          <h1 className="text-xl font-semibold">로그인</h1>
        </CardTitle>
        <CardDescription>
          계정이 없으면{" "}
          <Link href="/signup/" className="underline underline-offset-4">
            가입
          </Link>
          하세요. 소셜·휴대폰 로그인은 처음이면 계정이 새로 만들어집니다.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6">
        <FormError message={error ? errorMessage(error) : null} />
        <Tabs defaultValue="email">
          <TabsList className="w-full">
            <TabsTrigger value="email">이메일·비밀번호</TabsTrigger>
            <TabsTrigger value="phone">휴대폰</TabsTrigger>
          </TabsList>
          <TabsContent value="email" className="pt-2">
            <LoginForm onLoggedIn={loggedIn} />
          </TabsContent>
          <TabsContent value="phone" className="pt-2">
            <PhoneOtpForm onVerified={loggedIn} />
          </TabsContent>
        </Tabs>
        <Separator />
        <SocialButtons onNavigate={rememberNext} />
      </CardContent>
    </Card>
  );
}
