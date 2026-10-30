-- TASK-26#4 (BE-36) 연결 해제·삭제 글 첨부의 지연 삭제. 글에서 뗀 시각을 남겨 유예 기간(app.uploads.orphan-grace-period, 기본 7일)이
-- 지나면 앱 스케줄러(OrphanAttachmentCleanup)가 행을 지우고 S3 객체를 지운다. 글에 붙어 있는 동안은 비운다.
-- 글에 붙인 적 없는 확정 첨부는 confirmed_at 이 기준이다(coalesce).
ALTER TABLE attachments ADD COLUMN detached_at TIMESTAMPTZ;
ALTER TABLE attachments ADD CONSTRAINT attachments_detached_unlinked_ck CHECK (post_id IS NULL OR detached_at IS NULL);
CREATE INDEX attachments_unlinked_since_ix ON attachments ((coalesce(detached_at, confirmed_at)))
	WHERE status = 'CONFIRMED' AND post_id IS NULL;
-- 삭제 글 첨부 정리: 유예 기간이 지난 삭제 글
CREATE INDEX posts_deleted_at_ix ON posts (deleted_at) WHERE deleted_at IS NOT NULL;
