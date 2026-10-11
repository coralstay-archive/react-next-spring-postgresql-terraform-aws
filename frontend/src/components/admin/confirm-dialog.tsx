"use client";

import { useId, useState, type ReactNode } from "react";

import { ApiErrorAlert } from "@/components/board/api-error-alert";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { REASON_MAX } from "@/lib/admin";

export interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: ReactNode;
  confirmLabel: string;
  destructive?: boolean;
  /** 사유 입력칸(선택, 500자 이하, 감사 로그에 남는다)을 보인다. */
  withReason?: boolean;
  pending?: boolean;
  /** 서버 오류. 다이얼로그는 열린 채로 한국어 안내를 보인다. */
  error?: unknown;
  onConfirm: (reason: string) => void;
}

/**
 * 관리 액션 확인 다이얼로그 (TASK-15#1). `window.confirm` 대신 접근 가능한 alertdialog(포커스 가둠·Esc 닫기)를 쓴다.
 * 성공하면 호출자가 `open` 을 false 로 바꾼다. 열 때마다 내용을 새로 그리도록 호출자가 `key` 를 바꿔 준다.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  destructive,
  withReason,
  pending,
  error,
  onConfirm,
}: ConfirmDialogProps) {
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string | null>(null);
  const reasonId = useId();

  const confirm = () => {
    if (withReason && reason.length > REASON_MAX) {
      setReasonError(`사유는 ${REASON_MAX}자 이하여야 합니다`);
      return;
    }
    setReasonError(null);
    onConfirm(withReason ? reason : "");
  };

  return (
    <AlertDialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <AlertDialogContent data-testid="admin-confirm-dialog" className="sm:max-w-md">
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {withReason && (
          <div className="grid gap-1.5">
            <Label htmlFor={reasonId}>사유 (선택, 감사 로그에 남습니다)</Label>
            <Textarea
              id={reasonId}
              data-testid="admin-reason-input"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              aria-invalid={reasonError ? true : undefined}
              rows={3}
            />
            <p className="text-muted-foreground text-right text-xs">
              {reason.length}/{REASON_MAX}
            </p>
          </div>
        )}
        {reasonError ? (
          <Alert variant="destructive" data-testid="admin-confirm-error">
            <AlertDescription>{reasonError}</AlertDescription>
          </Alert>
        ) : (
          <ApiErrorAlert error={error} testId="admin-confirm-error" />
        )}
        <AlertDialogFooter>
          <AlertDialogCancel data-testid="admin-confirm-cancel" disabled={pending}>
            취소
          </AlertDialogCancel>
          <AlertDialogAction
            variant={destructive ? "destructive" : "default"}
            data-testid="admin-confirm-submit"
            disabled={pending}
            onClick={confirm}
          >
            {confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
