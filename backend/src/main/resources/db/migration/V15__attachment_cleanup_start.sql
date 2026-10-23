-- TASK-26#4 (BE-36) 배포 전 데이터에도 유예 기간(app.uploads.orphan-grace-period, 기본 7일)을 배포 시각부터 준다.
-- 1) V13 이전에 글에서 뗀 확정 첨부는 detached_at 이 비어 있어 confirmed_at 기준으로 첫 정리에 바로 지워질 수 있다 → 배포 시각으로 채운다.
--    (확정만 하고 붙이지 않은 첨부도 같이 배포 시각부터 센다)
UPDATE attachments SET detached_at = now() WHERE status = 'CONFIRMED' AND post_id IS NULL AND detached_at IS NULL;
-- 2) 삭제 글 첨부는 글의 deleted_at 이 기준이라 오래전에 삭제된 글의 이미지가 배포 직후 첫 정리에 지워진다. deleted_at 은 글의 사실이라
--    바꾸지 않고, 정리 시작 시각을 한 행으로 남겨 OrphanAttachmentCleanup 이 이 시각 + 유예 전에는 삭제 글 첨부를 지우지 않는다.
CREATE TABLE attachment_cleanup_start (
	id         SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
	started_at TIMESTAMPTZ NOT NULL
);
INSERT INTO attachment_cleanup_start (started_at) VALUES (now());
