import { api, ApiError } from "@/lib/api";
import type { Attachment } from "@/lib/board";
import { ERROR_MESSAGES } from "@/lib/errors";

/**
 * 이미지 업로드 (TASK-11 흐름, TASK-14#3).
 * 1. 클라이언트 선검증(서버 B1과 같은 타입·크기) → 2. `POST /api/attachments/presign` → 3. 받은 `uploadUrl` 로 `PUT`
 * (`headers` 를 그대로, 본문은 파일 그대로; 진행률 때문에 XHR) → 4. `POST /api/attachments/{id}/confirm`.
 * `headers` 에는 서명된 `If-None-Match: *`(덮어쓰기 금지, TASK-26#2)가 있다 — 빼면 서명 불일치로 거부되고, 이미 올린 키에 다시 PUT 하면
 * 412 라 `upload_failed` 다.
 * 글 작성·수정 때 확정된 첨부 id 를 `attachmentIds` 로 보낸다.
 */

export const ALLOWED_IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"] as const;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
/** 글당 첨부 수 상한(서버 `attachmentIds` 최대 10). */
export const MAX_ATTACHMENTS = 10;

interface Presigned {
  attachmentId: number;
  key: string;
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  expiresAt: string;
}

/** 통과하면 null, 아니면 한국어 문장. */
export function validateImageFile(file: File): string | null {
  if (!(ALLOWED_IMAGE_TYPES as readonly string[]).includes(file.type.toLowerCase())) {
    return ERROR_MESSAGES.attachment_type_not_allowed;
  }
  if (file.size <= 0) return "빈 파일은 올릴 수 없습니다.";
  if (file.size > MAX_IMAGE_BYTES) return ERROR_MESSAGES.attachment_too_large;
  return null;
}

function precheckCode(file: File): string {
  return validateImageFile(file) === ERROR_MESSAGES.attachment_too_large ? "attachment_too_large" : "attachment_type_not_allowed";
}

/** 업로드 전체. 진행률(0~100)은 PUT 구간만 알린다. 실패는 {@link ApiError}(S3 PUT 실패는 `upload_failed`). */
export async function uploadImage(file: File, onProgress?: (percent: number) => void): Promise<Attachment> {
  const invalid = validateImageFile(file);
  if (invalid) throw new ApiError(400, precheckCode(file), invalid);

  const presigned = await api<Presigned>("/attachments/presign", {
    method: "POST",
    body: { contentType: file.type.toLowerCase(), size: file.size, filename: file.name.slice(0, 255) || undefined },
  });
  await putObject(presigned, file, onProgress);
  onProgress?.(100);
  return api<Attachment>(`/attachments/${presigned.attachmentId}/confirm`, { method: "POST" });
}

/** S3(또는 로컬 MinIO)로 직접 PUT. 쿠키·CSRF 는 보내지 않는다(다른 오리진, 서명이 인증이다). */
function putObject(presigned: Presigned, file: File, onProgress?: (percent: number) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(presigned.method || "PUT", presigned.uploadUrl);
    for (const [name, value] of Object.entries(presigned.headers ?? {})) xhr.setRequestHeader(name, value);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress?.(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else reject(new ApiError(xhr.status, "upload_failed", ERROR_MESSAGES.upload_failed));
    };
    xhr.onerror = () => reject(new ApiError(0, "upload_failed", ERROR_MESSAGES.upload_failed));
    xhr.onabort = () => reject(new ApiError(0, "upload_failed", ERROR_MESSAGES.upload_failed));
    xhr.send(file);
  });
}
