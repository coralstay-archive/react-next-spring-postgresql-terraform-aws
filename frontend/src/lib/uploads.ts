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
 *
 * 정체·상한·취소(TASK-31#5, FE-26, 유저 결정): PUT 은 XHR 이다 — fetch 에는 업로드 진행 이벤트가 없어 '진행이 멈췄다'를 알 수 없다.
 * 진행 이벤트가 {@link UPLOAD_STALL_MS}(30초) 동안 없으면 `upload_stalled`, 시작부터 {@link UPLOAD_MAX_MS}(5분, presigned URL 만료)를 넘기면
 * `upload_timeout` 으로 끊는다. `signal` 이 취소되면 presign·PUT·confirm 어디서든 멈추고 AbortError(호출자 취소)로 끝난다.
 * 재시도는 presign 부터 새로 한다(같은 URL 재PUT 은 `If-None-Match: *` 라 412).
 */

export const ALLOWED_IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"] as const;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
/** 글당 첨부 수 상한(서버 `attachmentIds` 최대 10). */
export const MAX_ATTACHMENTS = 10;
/** 업로드 진행 이벤트가 이만큼 없으면 포기한다(유저 결정 30초). */
export const UPLOAD_STALL_MS = 30_000;
/** PUT 전체 상한 = presigned URL 유효 시간(5분). 이보다 오래 걸리면 서명이 만료돼 어차피 실패한다. */
export const UPLOAD_MAX_MS = 5 * 60_000;

export interface UploadOptions {
  /** 진행률(0~100). PUT 구간만 알린다. */
  onProgress?: (percent: number) => void;
  /** 취소(편집기의 '업로드 취소'·언마운트). */
  signal?: AbortSignal;
  /** 테스트용. 기본 {@link UPLOAD_STALL_MS}·{@link UPLOAD_MAX_MS}. */
  stallMs?: number;
  maxMs?: number;
}

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

/**
 * 업로드 전체. 실패는 {@link ApiError}(S3 PUT 실패는 `upload_failed`, 정체 `upload_stalled`, 상한 `upload_timeout`), 취소는 AbortError.
 */
export async function uploadImage(file: File, options: UploadOptions = {}): Promise<Attachment> {
  const { onProgress, signal } = options;
  const invalid = validateImageFile(file);
  if (invalid) throw new ApiError(400, precheckCode(file), invalid);
  signal?.throwIfAborted();

  const presigned = await api<Presigned>("/attachments/presign", {
    method: "POST",
    body: { contentType: file.type.toLowerCase(), size: file.size, filename: file.name.slice(0, 255) || undefined },
    signal,
  });
  await putObject(presigned, file, options);
  onProgress?.(100);
  return api<Attachment>(`/attachments/${presigned.attachmentId}/confirm`, { method: "POST", signal });
}

function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException("업로드를 취소했습니다", "AbortError");
}

/**
 * S3(또는 로컬 MinIO)로 직접 PUT. 쿠키·CSRF 는 보내지 않는다(다른 오리진, 서명이 인증이다).
 * 정체 타이머는 진행 이벤트마다 다시 건다. 정체·상한·취소면 먼저 결과를 정한 뒤 xhr.abort() 한다(뒤따르는 onabort 는 무시된다).
 */
function putObject(presigned: Presigned, file: File, options: UploadOptions): Promise<void> {
  const { onProgress, signal, stallMs = UPLOAD_STALL_MS, maxMs = UPLOAD_MAX_MS } = options;
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortReason(signal));
      return;
    }
    const xhr = new XMLHttpRequest();
    let settled = false;
    let stallTimer: ReturnType<typeof setTimeout> | undefined;
    const settle = (error?: unknown) => {
      if (settled) return;
      settled = true;
      clearTimeout(stallTimer);
      clearTimeout(capTimer);
      signal?.removeEventListener("abort", onAbort);
      if (error === undefined) resolve();
      else reject(error);
    };
    const giveUp = (error: unknown) => {
      settle(error);
      xhr.abort();
    };
    const armStall = () => {
      clearTimeout(stallTimer);
      stallTimer = setTimeout(() => giveUp(new ApiError(0, "upload_stalled", ERROR_MESSAGES.upload_stalled)), stallMs);
    };
    const onAbort = () => giveUp(abortReason(signal!));
    const capTimer = setTimeout(() => giveUp(new ApiError(0, "upload_timeout", ERROR_MESSAGES.upload_timeout)), maxMs);
    signal?.addEventListener("abort", onAbort, { once: true });

    xhr.open(presigned.method || "PUT", presigned.uploadUrl);
    for (const [name, value] of Object.entries(presigned.headers ?? {})) xhr.setRequestHeader(name, value);
    xhr.upload.onprogress = (event) => {
      armStall();
      if (event.lengthComputable && event.total > 0) onProgress?.(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) settle();
      else settle(new ApiError(xhr.status, "upload_failed", ERROR_MESSAGES.upload_failed));
    };
    xhr.onerror = () => settle(new ApiError(0, "upload_failed", ERROR_MESSAGES.upload_failed));
    xhr.onabort = () => settle(new ApiError(0, "upload_failed", ERROR_MESSAGES.upload_failed));
    armStall();
    xhr.send(file);
  });
}
