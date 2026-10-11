import { buttonVariants } from "@/components/ui/button";
import { SOCIAL_PROVIDERS, socialLoginHref } from "@/lib/providers";

/**
 * 소셜 5종 로그인. fetch 가 아니라 일반 링크(페이지 이동)다: 백엔드가 제공자 인가 화면으로 302 하고, 끝나면
 * /auth/callback/ 으로 돌려보낸다. 설정되지 않은 제공자는 콜백에서 unknown_provider 로 안내된다.
 */
export function SocialButtons({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <ul className="grid gap-2" aria-label="소셜 로그인">
      {SOCIAL_PROVIDERS.map(({ id, label }) => (
        <li key={id}>
          <a
            href={socialLoginHref(id)}
            onClick={onNavigate}
            className={buttonVariants({ variant: "outline", className: "w-full" })}
          >
            {label}로 로그인
          </a>
        </li>
      ))}
    </ul>
  );
}
