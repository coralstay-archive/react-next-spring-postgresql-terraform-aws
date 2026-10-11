import { Alert, AlertDescription } from "@/components/ui/alert";

/** 폼 오류 한 줄(role="alert" 라 스크린리더가 바로 읽는다). */
export function FormError({ message }: { message: string | null | undefined }) {
  if (!message) return null;
  return (
    <Alert variant="destructive">
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}
