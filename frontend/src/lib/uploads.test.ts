import { http } from "msw";
import { describe, expect, it } from "vitest";

import { isApiError } from "@/lib/api";
import { MAX_IMAGE_BYTES, uploadImage, validateImageFile } from "@/lib/uploads";
import { presignHandlers } from "@/test/board";
import { apiError, csrfHandler, server } from "@/test/server";

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

    const attachment = await uploadImage(png(8, "cat.png"), (p) => progress.push(p));

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
