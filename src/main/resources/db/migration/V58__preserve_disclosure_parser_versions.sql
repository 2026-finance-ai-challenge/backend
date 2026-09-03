-- 동일 원문도 파서가 달라지면 별도 문서 버전으로 보존한다.
ALTER TABLE disclosure_document DROP CONSTRAINT disclosure_document_content;
ALTER TABLE disclosure_document ADD CONSTRAINT disclosure_document_content
    UNIQUE (disclosure_id, source_filename, content_hash, parser_version);
