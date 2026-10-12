import { readFileSync } from "node:fs";
import { crc32, deflateSync } from "node:zlib";
import { expect, type APIResponse, type BrowserContext, type Page } from "@playwright/test";

/** E2E 헬퍼 (TASK-18#5). 스택은 scripts/e2e.sh 가 띄운다. */

export interface User {
  email: string;
  password: string;
  nickname: string;
}

const RUN = Date.now().toString(36).slice(-5);

/** 실행마다 다른 사용자(E2E_KEEP 으로 스택을 남겨 다시 돌려도 409 가 나지 않게). */
export function newUser(tag: string): User {
  return { email: `${tag}-${RUN}@example.com`, password: "e2e-pass-1234", nickname: `${tag}${RUN}` };
}

/** scripts/e2e.sh 가 가입시키고 백엔드를 재시작해 ADMIN 으로 올려 둔 계정(TASK-21#3: 가입만으로는 ADMIN 이 아니다). */
export function adminUser(): User {
  return {
    email: process.env.E2E_ADMIN_EMAIL ?? "admin-e2e@example.com",
    password: process.env.E2E_ADMIN_PASSWORD ?? "e2e-admin-1234",
    nickname: process.env.E2E_ADMIN_NICKNAME ?? "관리자e2e",
  };
}

export async function signup(page: Page, user: User) {
  await page.goto("/signup/");
  await page.getByLabel("이메일").fill(user.email);
  await page.getByLabel("비밀번호").fill(user.password);
  await page.getByLabel("닉네임").fill(user.nickname);
  await page.getByRole("button", { name: "가입하기" }).click();
  // 가입 → 자동 로그인 → 홈. 헤더에 닉네임이 보인다.
  await expect(page.getByLabel("로그인한 사용자")).toHaveText(user.nickname);
}

export async function login(page: Page, user: User) {
  await page.goto("/login/");
  const form = page.getByRole("tabpanel", { name: "이메일·비밀번호" });
  await form.getByLabel("이메일").fill(user.email);
  await form.getByLabel("비밀번호").fill(user.password);
  await form.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page.getByLabel("로그인한 사용자")).toHaveText(user.nickname);
}

export async function logout(page: Page) {
  await page.getByRole("button", { name: "로그아웃" }).click();
  await expect(page.getByRole("link", { name: "로그인" })).toBeVisible();
}

/** 브라우저 컨텍스트의 세션 쿠키로 API 를 부른다. 쓰기는 XSRF-TOKEN 쿠키를 헤더로 보낸다(SPA 와 같은 방식). */
export async function apiAs(
  context: BrowserContext,
  method: "GET" | "POST" | "PATCH" | "DELETE",
  path: string,
  data?: unknown,
): Promise<APIResponse> {
  let token = (await context.cookies()).find((c) => c.name === "XSRF-TOKEN")?.value;
  if (!token && method !== "GET") {
    await context.request.get("/api/auth/csrf");
    token = (await context.cookies()).find((c) => c.name === "XSRF-TOKEN")?.value;
  }
  return context.request.fetch(path, {
    method,
    data,
    headers: token ? { "X-XSRF-TOKEN": token } : {},
  });
}

/** 무작위 국내 휴대폰 번호: 화면 입력용 010-XXXX-XXXX 와 로그에 찍히는 E.164. */
export function randomPhone(): { display: string; e164: string } {
  const digits = String(Math.floor(Math.random() * 1e8)).padStart(8, "0");
  return { display: `010-${digits.slice(0, 4)}-${digits.slice(4)}`, e164: `+8210${digits}` };
}

function otpCodes(e164: string): string[] {
  const path = process.env.E2E_BACKEND_LOG;
  if (!path) throw new Error("E2E_BACKEND_LOG 가 없다 — scripts/e2e.sh 로 실행한다");
  const log = readFileSync(path, "utf8");
  const re = new RegExp(`\\[SMS console\\] to=${e164.replace("+", "\\+")} message=\\[board\\] 인증번호 (\\d{6})`, "g");
  return [...log.matchAll(re)].map((m) => m[1]);
}

/** SMS_PROVIDER=console 이 백엔드 로그에 남긴 인증번호 중 `before`개 다음 것을 기다려 읽는다. */
export async function waitForOtpCode(e164: string, before: number): Promise<string> {
  let codes: string[] = [];
  await expect.poll(() => (codes = otpCodes(e164)).length, { message: `로그에서 ${e164} 인증번호 대기` }).toBeGreaterThan(before);
  return codes[codes.length - 1];
}

export function otpCount(e164: string): number {
  return otpCodes(e164).length;
}

/** 단색 PNG(테스트 업로드용). */
export function makePng(width = 160, height = 100, rgb: [number, number, number] = [37, 99, 235]): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: width }, () => rgb).flat())]);
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

/** 스크린샷(시각 확인용, frontend/e2e/artifacts/screenshots). */
export async function shot(page: Page, name: string) {
  await page.screenshot({ path: `e2e/artifacts/screenshots/${name}.png`, fullPage: true });
}
