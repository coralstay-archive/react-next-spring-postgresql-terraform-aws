/**
 * 가입 입력 검증. 백엔드 `SignupRequest`·`PasswordPolicy` 와 같은 규칙·문장이다(서버가 최종 판단).
 * - 이메일: 앞뒤 공백 제거, 필수, 320자 이하, `^[^@\s]+@[^@\s]+\.[^@\s]+$`
 * - 비밀번호: 그대로(공백 포함), 8자(코드 포인트) 이상, UTF-8 72바이트 이하(BCrypt 한계), 문자·숫자 각각 하나 이상
 * - 닉네임: 앞뒤 공백 제거, 2~20자(Java String 길이 = UTF-16 단위)
 */
export interface SignupInput {
  email: string;
  password: string;
  nickname: string;
}

export type FieldErrors<T> = Partial<Record<keyof T, string>>;

const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
export const PASSWORD_MESSAGE = "비밀번호는 8자 이상 72바이트 이하이고 문자와 숫자를 모두 포함해야 합니다";

export function isValidPassword(password: string): boolean {
  return (
    [...password].length >= 8 &&
    new TextEncoder().encode(password).length <= 72 &&
    /\p{L}/u.test(password) &&
    /\p{Nd}/u.test(password)
  );
}

export function normalizeSignup(input: SignupInput): SignupInput {
  return { email: input.email.trim(), password: input.password, nickname: input.nickname.trim() };
}

export function validateSignup(raw: SignupInput): FieldErrors<SignupInput> {
  const input = normalizeSignup(raw);
  const errors: FieldErrors<SignupInput> = {};

  if (!input.email) errors.email = "이메일을 입력하세요";
  else if (input.email.length > 320) errors.email = "이메일이 너무 깁니다";
  else if (!EMAIL_SHAPE.test(input.email)) errors.email = "이메일 형식이 아닙니다";

  if (!isValidPassword(input.password)) errors.password = PASSWORD_MESSAGE;

  if (!input.nickname) errors.nickname = "닉네임을 입력하세요";
  else if (input.nickname.length < 2 || input.nickname.length > 20) errors.nickname = "닉네임은 2~20자입니다";

  return errors;
}
