-- 계정 생성 시점의 상태 대신 현재 서류와 비교 결과를 단일 기준으로 사용한다.
CREATE VIEW user_tax_progress AS
SELECT u.id AS user_id,
  CASE WHEN EXISTS (
    SELECT 1 FROM chat_room r JOIN tax_conversation_state s ON s.room_id = r.id
    WHERE r.user_id = u.id AND r.context_type = 'TAX_GUIDE' AND r.deleted_at IS NULL
      AND s.comparison->>'verificationStatus' = 'VERIFIED'
      AND s.comparison->'crossCheck'->>'matched' = 'true'
      AND (SELECT count(DISTINCT d.document_type) FROM tax_document d
           WHERE d.user_id = u.id AND d.deleted_at IS NULL AND d.status = 'VERIFIED'
             AND d.storage_key NOT LIKE 'purged/%') = 3
  ) THEN 'VERIFIED'
  WHEN EXISTS (SELECT 1 FROM chat_room r WHERE r.user_id = u.id AND r.context_type = 'TAX_GUIDE' AND r.deleted_at IS NULL)
    OR EXISTS (SELECT 1 FROM tax_document d WHERE d.user_id = u.id AND d.deleted_at IS NULL)
    THEN 'IN_PROGRESS'
  ELSE 'NOT_STARTED' END AS status
FROM user_account u;
