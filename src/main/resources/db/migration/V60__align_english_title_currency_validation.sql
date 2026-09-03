-- 인명 Jo는 허용하고 숫자 뒤의 조·억 음역과 원화 단위 음역만 차단한다.
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE translation_memory
    DROP CONSTRAINT translation_memory_english_title_script,
    ADD CONSTRAINT translation_memory_english_title_script CHECK (
        content_kind NOT IN ('NEWS_TITLE', 'DISCLOSURE_TITLE')
        OR target_locale <> 'en'
        OR status <> 'READY'
        OR (
            translated_text !~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ㐀-䶿一-鿿]'
            AND translated_text !~* '\y[0-9][0-9,.]*[[:space:]]*(eok|jo)\y|\y(eok|jo)[ -]?won\y|\yman[ -]?won\y'
        )
    );

ALTER TABLE news_article
    DROP CONSTRAINT news_article_english_title_script,
    ADD CONSTRAINT news_article_english_title_script CHECK (
        english_title IS NULL OR (
            english_title !~ '[ㄱ-ㅎㅏ-ㅣ가-힣ぁ-ヿ㐀-䶿一-鿿]'
            AND english_title !~* '\y[0-9][0-9,.]*[[:space:]]*(eok|jo)\y|\y(eok|jo)[ -]?won\y|\yman[ -]?won\y'
        )
    );
