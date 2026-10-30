import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import { isApiError } from "@/lib/api";
import {
  confirmAttachment,
  isRetryableConfirmError,
  MAX_IMAGE_BYTES,
  UPLOAD_MAX_MS,
  UPLOAD_STALL_MS,
  uploadImage,
  validateImageFile,
} from "@/lib/uploads";
import { presignHandlers } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";
import { installFakeXhr } from "@/test/xhr";

function png(bytes = 8, name = "a.png") {
  return new File([new Uint8Array(bytes)], name, { type: "image/png" });
}

// TASK-14#3 이미지 업로드: 클라이언트 선검증 → presign → S3 PUT(진행률) → confirm.
describe("TASK-14#3 이미지 선검증(서버 B1 미러)", () => {
  it("TASK-14#3 png·jpeg·webp·gif 만, 5MB 이하만", () => {
    expect(validateImageFile(png())).toBeNull();
    expect(validateImageFile(new File(["x"], "a.jpg", { type: "image/jpeg" }))).toBeNull();
    expect(validateImageFile(new File(["x"], "a.webp", { type: "image/webp" }))).toBeNull();
    expect(validateImageFile(new File(["x"], "a.gif", { type: "image/gif" }))).toBeNull();
    expect(validateImageFile(new File(["x"], "a.svg", { type: "image/svg+xml" }))).toBe(
      "PNG·JPEG·WebP·GIF 이미지만 올릴 수 있습니다.",
    );
    expect(validateImageFile(new File(["x"], "a.txt", { type: "" }))).toBe("PNG·JPEG·WebP·GIF 이미지만 올릴 수 있습니다.");
    expect(MAX_IMAGE_BYTES).toBe(5 * 1024 * 1024);
    expect(validateImageFile(png(MAX_IMAGE_BYTES))).toBeNull();
    expect(validateImageFile(png(MAX_IMAGE_BYTES + 1))).toBe("이미지는 5MB 이하만 올릴 수 있습니다.");
    expect(validateImageFile(png(0))).toBe("빈 파일은 올릴 수 없습니다.");
  });
});

describe("TASK-14#3 업로드 흐름", () => {
  it("TASK-14#3 presign → 받은 headers 로 PUT → confirm, 진행률을 알린다", async () => {
    const calls = presignHandlers();
    server.use(csrfHandler(), ...calls.handlers);
    const progress: number[] = [];

    const attachment = await uploadImage(png(8, "cat.png"), { onProgress: (p) => progress.push(p) });

    expect(calls.presignBody).toEqual({ contentType: "image/png", size: 8, filename: "cat.png" });
    expect(calls.putHeaders?.get("content-type")).toBe("image/png");
    expect(calls.putHeaders?.get("x-amz-tagging")).toBe("status=pending");
    expect(calls.putSize).toBe(8);
    expect(calls.confirmed).toEqual([100]);
    expect(attachment).toEqual({ id: 100, url: "/uploads/1/100.png", contentType: "image/png", size: 8, status: "CONFIRMED" });
    expect(progress.at(-1)).toBe(100);
  });

  it("TASK-26#2 TASK-26#B1 서명된 덮어쓰기 금지 헤더(If-None-Match: *)를 그대로 보낸다", async () => {
    const calls = presignHandlers();
    server.use(csrfHandler(), ...calls.handlers);

    await uploadImage(png());

    expect(calls.putHeaders?.get("if-none-match")).toBe("*");
  });

  it("TASK-26#2 TASK-26#B1 이미 있는 키라 412 면 upload_failed 이고 confirm 하지 않는다", async () => {
    const calls = presignHandlers({ putStatus: 412 });
    server.use(csrfHandler(), ...calls.handlers);

    const error = await uploadImage(png()).catch((e: unknown) => e);
    expect(isApiError(error) && error.code).toBe("upload_failed");
    expect(calls.confirmed).toEqual([]);
  });

  it("TASK-14#3 S3 PUT 이 실패하면 upload_failed 이고 confirm 하지 않는다", async () => {
    const calls = presignHandlers({ putStatus: 403 });
    server.use(csrfHandler(), ...calls.handlers);

    const error = await uploadImage(png()).catch((e: unknown) => e);
    expect(isApiError(error) && error.code).toBe("upload_failed");
    expect(calls.confirmed).toEqual([]);
  });

  it("TASK-14#3 presign 503 uploads_unavailable 은 그대로 ApiError", async () => {
    server.use(csrfHandler(), http.post("*/api/attachments/presign", () => apiError(503, "uploads_unavailable")));
    const error = await uploadImage(png()).catch((e: unknown) => e);
    expect(isApiError(error) && error.code).toBe("uploads_unavailable");
  });

  it("TASK-14#3 선검증에 걸리면 요청하지 않는다", async () => {
    const error = await uploadImage(new File(["x"], "a.txt", { type: "text/plain" })).catch((e: unknown) => e);
    expect(isApiError(error) && error.code).toBe("attachment_type_not_allowed");
  });
});

// TASK-31#5 업로드 정체·상한·취소. S3 PUT 은 XHR(fetch 에는 업로드 진행 이벤트가 없다)이라 진행 이벤트를 직접 내는 대역을 쓴다.
describe("TASK-31#5 업로드 정체 timeout·전체 상한·취소(FE-26)", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  function startWithFakeXhr(options: Parameters<typeof uploadImage>[1] = {}) {
    const calls = presignHandlers();
    server.use(csrfHandler(), ...calls.handlers);
    const xhrs = installFakeXhr();
    const result = uploadImage(png(), options).then(
      (value) => ({ ok: true as const, value }),
      (error: unknown) => ({ ok: false as const, error }),
    );
    return { calls, xhrs, result };
  }

  it("TASK-31#5 정체 기준은 30초, 전체 상한은 presigned URL 만료와 같은 5분", () => {
    expect(UPLOAD_STALL_MS).toBe(30_000);
    expect(UPLOAD_MAX_MS).toBe(5 * 60_000);
  });

  it("TASK-31#5 진행 이벤트가 30초 동안 없으면 PUT 을 끊고 upload_stalled, confirm 하지 않는다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setTimeout", "clearTimeout"] });
    const { calls, xhrs, result } = startWithFakeXhr();
    await vi.waitFor(() => expect(xhrs.instances).toHaveLength(1));

    await vi.advanceTimersByTimeAsync(UPLOAD_STALL_MS - 1_000);
    xhrs.instances[0].progress(4, 8); // 진행이 있으면 다시 30초를 준다
    await vi.advanceTimersByTimeAsync(UPLOAD_STALL_MS - 1_000);
    expect(xhrs.instances[0].aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1_000);

    const outcome = await result;
    expect(outcome.ok).toBe(false);
    expect(!outcome.ok && isApiError(outcome.error) && outcome.error.code).toBe("upload_stalled");
    expect(xhrs.instances[0].aborted).toBe(true);
    expect(calls.confirmed).toEqual([]);
  });

  it("TASK-31#5 진행 중이어도 전체 상한(5분)을 넘기면 끊고 upload_timeout", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setTimeout", "clearTimeout"] });
    const { xhrs, result } = startWithFakeXhr();
    await vi.waitFor(() => expect(xhrs.instances).toHaveLength(1));

    for (let elapsed = 0; elapsed < UPLOAD_MAX_MS; elapsed += 20_000) {
      xhrs.instances[0].progress(1, 8);
      await vi.advanceTimersByTimeAsync(20_000);
    }

    const outcome = await result;
    expect(!outcome.ok && isApiError(outcome.error) && outcome.error.code).toBe("upload_timeout");
    expect(xhrs.instances[0].aborted).toBe(true);
  });

  it("TASK-31#5 signal 로 취소하면 PUT 을 abort 하고 AbortError 로 끝나며 confirm 하지 않는다", async () => {
    const controller = new AbortController();
    const { calls, xhrs, result } = startWithFakeXhr({ signal: controller.signal });
    await vi.waitFor(() => expect(xhrs.instances).toHaveLength(1));

    controller.abort();

    const outcome = await result;
    expect(!outcome.ok && (outcome.error as Error).name).toBe("AbortError");
    expect(xhrs.instances[0].aborted).toBe(true);
    expect(calls.confirmed).toEqual([]);
  });

  it("TASK-31#5 presign 전에 이미 취소됐으면 아무 요청도 하지 않는다", async () => {
    const controller = new AbortController();
    controller.abort();
    const { calls, xhrs, result } = startWithFakeXhr({ signal: controller.signal });

    const outcome = await result;
    expect(!outcome.ok && (outcome.error as Error).name).toBe("AbortError");
    expect(calls.presignBody).toBeUndefined();
    expect(xhrs.instances).toHaveLength(0);
  });

  it("TASK-31#5 진행 이벤트(정상 응답)면 confirm 까지 간다", async () => {
    const { calls, xhrs, result } = startWithFakeXhr();
    await vi.waitFor(() => expect(xhrs.instances).toHaveLength(1));
    expect(xhrs.instances[0].headers["if-none-match"]).toBe("*");
    xhrs.instances[0].progress(8, 8);
    xhrs.instances[0].respond(200);

    const outcome = await result;
    expect(outcome.ok).toBe(true);
    expect(calls.confirmed).toEqual([100]);
  });
});

// TASK-31#6 PUT 은 됐는데 confirm 이 실패: 같은 URL 재PUT 은 If-None-Match: * 라 412 이므로 confirm 만 다시 한다.
describe("TASK-31#6 confirm 만 다시 시도(FE-27)", () => {
  it.each([
    ["503 storage_unavailable", () => apiError(503, "storage_unavailable")],
    ["네트워크 오류", () => HttpResponse.error()],
    ["429 rate_limited", () => apiError(429, "rate_limited")],
  ])("TASK-31#6 confirm 이 %s 면 attachmentId 를 담은 '다시 시도할 수 있는' 오류이고, confirmAttachment 로 확정된다", async (_name, fail) => {
    const calls = presignHandlers();
    let confirms = 0;
    server.use(
      csrfHandler(),
      http.post("*/api/attachments/:id/confirm", () => (confirms++ === 0 ? fail() : undefined)),
      ...calls.handlers,
    );

    const error = await uploadImage(png()).catch((e: unknown) => e);
    expect(isRetryableConfirmError(error)).toBe(true);
    expect(isRetryableConfirmError(error) && error.attachmentId).toBe(100);

    const attachment = await confirmAttachment(100);
    expect(attachment).toMatchObject({ id: 100, status: "CONFIRMED" });
    expect(calls.putSize).toBe(8);
  });

  it.each([
    [410, "attachment_expired"],
    [404, "attachment_not_found"],
    [400, "attachment_mismatch"],
  ])("TASK-31#6 confirm 이 %i %s 면 다시 시도할 수 없다(다시 올려야 한다)", async (status, code) => {
    const calls = presignHandlers();
    server.use(csrfHandler(), http.post("*/api/attachments/:id/confirm", () => apiError(status, code)), ...calls.handlers);

    const error = await uploadImage(png()).catch((e: unknown) => e);
    expect(isApiError(error) && error.code).toBe(code);
    expect(isRetryableConfirmError(error)).toBe(false);
  });
});
