import { expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import {
  adminUser,
  apiAs,
  login,
  logout,
  makePng,
  newUser,
  otpCount,
  randomPhone,
  shot,
  signup,
  waitForOtpCode,
} from "./helpers";

/**
 * TASK-18#5 Playwright 스모크 시나리오. 실제 로컬 스택(PostgreSQL·MinIO·백엔드·next dev 프록시)을 대상으로 한 흐름을 직렬로 돈다.
 * 실행: make e2e (scripts/e2e.sh)
 */
test.describe.configure({ mode: "serial" });

const userA = newUser("writer");
const userB = newUser("other");
const admin = adminUser();
const token = `e2e${Date.now().toString(36)}`;
const post = { title: `E2E 스모크 글 ${token}`, content: `Playwright 가 쓴 본문입니다.\n두 번째 줄 ${token}`, tag: `E2E 태그` };
let postId = 0;

let ctxA: BrowserContext;
let ctxB: BrowserContext;
let pageA: Page;
let pageB: Page;

async function newPage(browser: Browser): Promise<[BrowserContext, Page]> {
  const ctx = await browser.newContext();
  return [ctx, await ctx.newPage()];
}

test.beforeAll(async ({ browser }) => {
  [ctxA, pageA] = await newPage(browser);
  [ctxB, pageB] = await newPage(browser);
});

test.afterAll(async () => {
  await ctxA?.close();
  await ctxB?.close();
});

test("TASK-18#5 A 가입 → 로그아웃 → 로그인", async () => {
  await signup(pageA, userA);
  await expect(pageA.getByTestId("post-list-empty").or(pageA.getByTestId("post-list"))).toBeVisible();
  await shot(pageA, "01-home-after-signup");
  await logout(pageA);
  await login(pageA, userA);
});

test("TASK-18#5 A 글쓰기: 카테고리·태그·이미지 업로드", async () => {
  const page = pageA;
  await page.goto("/");
  await page.getByTestId("new-post-link").click();
  await expect(page.getByTestId("post-editor")).toBeVisible();
  await page.getByTestId("editor-title").fill(post.title);
  await page.getByTestId("editor-category").selectOption("free");
  await page.getByTestId("editor-content").fill(post.content);
  await page.getByTestId("editor-tag-input").fill(post.tag);
  await expect(page.getByTestId("editor-tag-preview")).toHaveText("저장될 이름: #e2e 태그");
  await page.getByTestId("editor-tag-input").press("Enter");
  await expect(page.getByTestId("editor-tags")).toContainText("#e2e 태그");

  // presign → MinIO 로 브라우저가 직접 PUT → confirm. 완료되면 미리보기 이미지가 뜬다.
  await page.getByTestId("editor-file-input").setInputFiles({ name: "e2e.png", mimeType: "image/png", buffer: makePng() });
  const preview = page.getByTestId("editor-attachments").getByRole("img", { name: "첨부 이미지 1" });
  await expect(preview).toBeVisible();
  await expect.poll(() => preview.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(160);
  await expect(page.getByTestId("editor-upload-progress")).toHaveCount(0);
  await shot(page, "02-editor-filled");

  await page.getByTestId("editor-submit").click();
  await expect(page).toHaveURL(/\/posts\/view\/\?id=\d+/);
  postId = Number(new URL(page.url()).searchParams.get("id"));
  await expect(page.getByTestId("post-title")).toHaveText(post.title);
  await expect(page.getByTestId("post-content")).toHaveText(post.content);
  await expect(page.getByTestId("post-author")).toHaveText(userA.nickname);
  const attached = page.getByTestId("post-attachments").getByRole("img").first();
  await expect.poll(() => attached.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(160);
  await expect(page.getByTestId("post-edit-link")).toBeVisible();
  await expect(page.getByTestId("post-delete-button")).toBeVisible();
  await expect(page.getByText("첫 댓글을 남겨 보세요.")).toBeVisible();
  await shot(page, "03-post-detail");
});

test("TASK-18#5 목록·태그·검색에 나온다", async () => {
  const page = pageA;
  await page.goto("/");
  await expect(page.getByTestId("post-item-link").filter({ hasText: post.title })).toBeVisible();
  await shot(page, "04-list");

  await page.getByTestId("tag-chip").filter({ hasText: "e2e 태그" }).click();
  await expect(page).toHaveURL(/tag=/);
  await expect(page.getByTestId("post-item-link").filter({ hasText: post.title })).toBeVisible();

  await page.goto("/");
  await page.getByTestId("search-input").fill(token);
  await page.getByTestId("search-submit").click();
  await expect(page).toHaveURL(new RegExp(`q=${token}`));
  await expect(page.getByTestId("post-item")).toHaveCount(1);
  await expect(page.getByTestId("post-item-link")).toHaveText(post.title);
  await shot(page, "05-search");
});

test("TASK-18#5 좋아요 + 댓글 → 로그아웃", async () => {
  const page = pageA;
  await page.goto(`/posts/view/?id=${postId}`);
  await expect(page.getByTestId("like-count")).toHaveText("0");
  await page.getByTestId("like-button").click();
  await expect(page.getByTestId("like-count")).toHaveText("1");
  // 새로 고쳐도 서버 값이 1 이다(낙관적 반영만이 아니다)
  await page.reload();
  await expect(page.getByTestId("like-count")).toHaveText("1");

  await page.getByTestId("comment-input").fill("작성자의 첫 댓글");
  await page.getByTestId("comment-submit").click();
  await expect(page.getByTestId("comment-item").filter({ hasText: "작성자의 첫 댓글" })).toBeVisible();
  await shot(page, "06-like-comment");
  await logout(page);
});

test("TASK-18#5 B 는 A 의 글을 수정·삭제할 수 없다(버튼 없음, API PATCH 403)", async () => {
  const page = pageB;
  await signup(page, userB);
  await page.goto(`/posts/view/?id=${postId}`);
  await expect(page.getByTestId("post-title")).toHaveText(post.title);
  await expect(page.getByTestId("post-edit-link")).toHaveCount(0);
  await expect(page.getByTestId("post-delete-button")).toHaveCount(0);

  const res = await apiAs(ctxB, "PATCH", `/api/posts/${postId}`, { title: "B 가 바꾼 제목" });
  expect(res.status()).toBe(403);
  expect((await res.json()).error).toBe("forbidden");

  // B 의 쓰기 자체는 된다(정지 전): 댓글
  await page.getByTestId("comment-input").fill("B 의 댓글");
  await page.getByTestId("comment-submit").click();
  await expect(page.getByTestId("comment-item").filter({ hasText: "B 의 댓글" })).toBeVisible();
  await shot(page, "07-other-user-view");
});

test("TASK-18#5 TASK-21#3 관리자(ADMIN_EMAIL, 기동 시 승격)가 B 를 정지하면 B 의 다음 쓰기가 실패한다", async ({ browser }) => {
  const [ctx, page] = await newPage(browser);
  // TASK-21#3: 가입만으로는 ADMIN 이 아니다. e2e.sh 가 가입 → 재시작으로 올려 둔 계정에 로그인한다
  await login(page, admin);
  await expect(page.getByRole("link", { name: "관리자" })).toBeVisible();
  await page.getByRole("link", { name: "관리자" }).click();
  await expect(page.getByTestId("admin-view")).toBeVisible();

  await page.getByTestId("admin-user-search-input").fill(userB.nickname);
  await page.getByTestId("admin-user-search-submit").click();
  const row = page.getByTestId("admin-user-row").filter({ hasText: userB.nickname });
  await expect(row).toHaveCount(1);
  await row.getByTestId("admin-user-suspend").click();
  const dialog = page.getByRole("alertdialog");
  await expect(dialog).toBeVisible();
  await dialog.getByTestId("admin-reason-input").fill("E2E 정지 시험");
  await shot(page, "08-admin-suspend-dialog");
  await dialog.getByTestId("admin-confirm-submit").click();
  await expect(dialog).toBeHidden();
  await expect(row.getByTestId("admin-user-status")).toHaveText("정지");
  await shot(page, "09-admin-users");

  await page.getByTestId("admin-tab-actions").click();
  await expect(page.getByTestId("admin-action-row").first()).toContainText("E2E 정지 시험");
  await ctx.close();

  // B: 정지가 세션을 지웠으므로 열려 있던 화면의 다음 쓰기(댓글)는 401 이다. 앱은 401 unauthorized 면 me 를 null 로 바꿔
  // 헤더·댓글 폼이 로그아웃 상태("로그인하고 댓글 쓰기")로 바뀐다. 댓글은 생기지 않는다.
  await pageB.getByTestId("comment-input").fill("정지 뒤 댓글");
  await pageB.getByTestId("comment-submit").click();
  await expect(pageB.getByRole("link", { name: "로그인하고 댓글 쓰기" })).toBeVisible();
  await expect(pageB.getByRole("link", { name: "로그인", exact: true })).toBeVisible();
  await shot(pageB, "10-suspended-write");
  const comments = await (await ctxB.request.get(`/api/posts/${postId}/comments`)).json();
  expect(comments.items.map((c: { content: string }) => c.content)).not.toContain("정지 뒤 댓글");

  // 다시 로그인해도 403 suspended
  const res = await apiAs(ctxB, "POST", "/api/auth/login", { email: userB.email, password: userB.password });
  expect(res.status()).toBe(403);
  expect((await res.json()).error).toBe("suspended");
});

test("TASK-18#5 휴대폰 OTP 로그인(백엔드 로그의 인증번호)", async ({ browser }) => {
  const [ctx, page] = await newPage(browser);
  const phone = randomPhone();
  await page.goto("/login/");
  await page.getByRole("tab", { name: "휴대폰" }).click();
  await page.getByLabel("휴대폰 번호").fill(phone.display);
  const before = otpCount(phone.e164);
  await page.getByRole("button", { name: "인증번호 받기" }).click();
  await expect(page.getByRole("timer")).toBeVisible();
  const code = await waitForOtpCode(phone.e164, before);

  // 틀린 번호를 먼저 넣으면 401 invalid_otp 안내
  await page.getByLabel("인증번호").fill(code === "000000" ? "111111" : "000000");
  await page.getByRole("button", { name: "확인" }).click();
  await expect(page.getByRole("alert")).toBeVisible();

  await page.getByLabel("인증번호").fill(code);
  await shot(page, "11-otp");
  await page.getByRole("button", { name: "확인" }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByLabel("로그인한 사용자")).toBeVisible();
  const me = await (await ctx.request.get("/api/auth/me")).json();
  expect(me.providers).toEqual(["PHONE"]);
  await ctx.close();
});

test("TASK-18#5 글 삭제 확인 창: 취소하면 남고, 확인하면 지워진다", async () => {
  const page = pageA;
  await login(page, userA);
  await page.goto(`/posts/view/?id=${postId}`);
  await expect(page.getByTestId("post-delete-button")).toBeVisible();

  page.once("dialog", (d) => {
    expect(d.type()).toBe("confirm");
    expect(d.message()).toBe("글을 삭제할까요?");
    void d.dismiss();
  });
  await page.getByTestId("post-delete-button").click();
  await expect(page).toHaveURL(new RegExp(`id=${postId}`));
  await expect(page.getByTestId("post-title")).toHaveText(post.title);

  page.once("dialog", (d) => void d.accept());
  await page.getByTestId("post-delete-button").click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId("post-item-link").filter({ hasText: post.title })).toHaveCount(0);
  const res = await ctxA.request.get(`/api/posts/${postId}`);
  expect(res.status()).toBe(404);
  await shot(page, "12-after-delete");
});
