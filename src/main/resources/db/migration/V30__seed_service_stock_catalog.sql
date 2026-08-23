CREATE TABLE service_stock_catalog (
    stock_code VARCHAR(6) PRIMARY KEY REFERENCES service_stock_universe (stock_code),
    dart_corp_code VARCHAR(8) NOT NULL UNIQUE,
    name_ko VARCHAR(200) NOT NULL,
    name_en VARCHAR(300) NOT NULL,
    market VARCHAR(16) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT service_stock_catalog_code_format CHECK (stock_code ~ '^[0-9A-Z]{6}$'),
    CONSTRAINT service_stock_catalog_dart_code_format CHECK (dart_corp_code ~ '^[0-9]{8}$'),
    CONSTRAINT service_stock_catalog_market_value CHECK (market IN ('KOSPI', 'KOSDAQ'))
);

INSERT INTO service_stock_catalog (stock_code, dart_corp_code, name_ko, name_en, market)
VALUES
    ('000100', '00145109', '유한양행', 'YUHAN CORPORATION', 'KOSPI'),
    ('000150', '00117212', '두산', 'DOOSAN CO.,LTD', 'KOSPI'),
    ('000270', '00106641', '기아', 'KIA CORPORATION', 'KOSPI'),
    ('000660', '00164779', 'SK하이닉스', 'SK hynix Inc.', 'KOSPI'),
    ('000720', '00164478', '현대건설', 'HYUNDAI ENGINEERING & CONSTRUCTION CO.,LTD', 'KOSPI'),
    ('000810', '00139214', '삼성화재해상보험', 'SAMSUNG FIRE & MARINE INSURANCE CO.,LTD', 'KOSPI'),
    ('003230', '00126955', '삼양식품', 'Samyang Foods Inc.', 'KOSPI'),
    ('003490', '00113526', '대한항공', 'KOREAN AIR LINES CO.,LTD', 'KOSPI'),
    ('003550', '00120021', 'LG', 'LG Corp.', 'KOSPI'),
    ('003670', '00155276', '포스코퓨처엠', 'POSCO FUTURE M CO., LTD.', 'KOSPI'),
    ('005380', '00164742', '현대자동차', 'HYUNDAI MOTOR CO', 'KOSPI'),
    ('005490', '00155319', 'POSCO홀딩스', 'POSCO Holdings Inc.', 'KOSPI'),
    ('005830', '00159102', 'DB손해보험', 'DB INSURANCE CO.,LTD', 'KOSPI'),
    ('005930', '00126380', '삼성전자', 'SAMSUNG ELECTRONICS CO,.LTD', 'KOSPI'),
    ('005940', '00120182', 'NH투자증권', 'NH INVESTMENT & SECURITIES CO.,LTD.', 'KOSPI'),
    ('006400', '00126362', '삼성SDI', 'SAMSUNG SDI CO.,LTD', 'KOSPI'),
    ('006800', '00111722', '미래에셋증권', 'MIRAE ASSET SECURITIES CO.,LTD.', 'KOSPI'),
    ('009150', '00126371', '삼성전기', 'SAMSUNG ELECTRO-MECHANICS CO.,LTD', 'KOSPI'),
    ('009540', '00164830', 'HD한국조선해양', 'HD KOREA SHIPBUILDING & OFFSHORE ENGINEERING CO., LTD.', 'KOSPI'),
    ('010120', '00105855', '엘에스일렉트릭', 'LS ELECTRIC CO., LTD', 'KOSPI'),
    ('010130', '00102858', '고려아연', 'KOREA ZINC INC', 'KOSPI'),
    ('010140', '00126478', '삼성중공업', 'SAMSUNG HEAVY INDUSTRIES CO.,LTD', 'KOSPI'),
    ('010950', '00138279', 'S-Oil', 'S-Oil Corporation', 'KOSPI'),
    ('011200', '00164645', 'HMM', 'HMM CO.,LTD', 'KOSPI'),
    ('012330', '00164788', '현대모비스', 'HYUNDAI MOBIS CO.,LTD', 'KOSPI'),
    ('012450', '00126566', '한화에어로스페이스', 'HANWHA AEROSPACE CO., LTD.', 'KOSPI'),
    ('0126Z0', '01965324', '삼성에피스홀딩스', 'SAMSUNG EPIS HOLDINGS Co., Ltd.', 'KOSPI'),
    ('015760', '00159193', '한국전력공사', 'KOREA ELECTRIC POWER CORPORATION', 'KOSPI'),
    ('017670', '00159023', 'SK텔레콤', 'SK TELECOM CO.,LTD', 'KOSPI'),
    ('018260', '00126186', '삼성에스디에스', 'SAMSUNG SDS CO., LTD.', 'KOSPI'),
    ('024110', '00149646', '기업은행', 'INDUSTRIAL BANK OF KOREA', 'KOSPI'),
    ('028260', '00149655', '삼성물산', 'SAMSUNG C&T CORPORATION', 'KOSPI'),
    ('028300', '00199252', 'HLB', 'HLB Co.,LTD.', 'KOSDAQ'),
    ('032640', '00231363', 'LG유플러스', 'LG Uplus Corp', 'KOSPI'),
    ('032830', '00126256', '삼성생명', 'Samsung Life Insurance co., Ltd', 'KOSPI'),
    ('033780', '00244455', '케이티앤지', 'KT&G Corporation', 'KOSPI'),
    ('034020', '00159616', '두산에너빌리티', 'DOOSAN ENERBILITY CO., LTD.', 'KOSPI'),
    ('034220', '00105873', 'LG디스플레이', 'LG Display Co., Ltd.', 'KOSPI'),
    ('034730', '00181712', 'SK', 'SK Inc.', 'KOSPI'),
    ('035420', '00266961', 'NAVER', 'NAVER Corporation', 'KOSPI'),
    ('035720', '00258801', '카카오', 'Kakao Corp.', 'KOSPI'),
    ('042660', '00111704', '한화오션', 'Hanwha Ocean Co., Ltd.', 'KOSPI'),
    ('042700', '00161383', '한미반도체', 'HANMI Semiconductor CO., LTD.', 'KOSPI'),
    ('047050', '00124504', '포스코인터내셔널', 'POSCO INTERNATIONAL', 'KOSPI'),
    ('047810', '00309503', '한국항공우주', 'KOREA AEROSPACE INDUSTRIES, LTD.', 'KOSPI'),
    ('051910', '00356361', 'LG화학', 'LG CHEM LTD', 'KOSPI'),
    ('055550', '00382199', '신한지주', 'SHINHAN FINANCIAL GROUP CO.,LTD', 'KOSPI'),
    ('064350', '00302926', '현대로템', 'Hyundai-Rotem Co.', 'KOSPI'),
    ('066570', '00401731', 'LG전자', 'LG ELECTRONICS INC.', 'KOSPI'),
    ('068270', '00413046', '셀트리온', 'Celltrion, Inc.', 'KOSPI'),
    ('071050', '00432102', '한국금융지주', 'Korea Investment Holdings Co., Ltd', 'KOSPI'),
    ('079550', '00503668', 'LIG디펜스앤에어로스페이스', 'LIG Defense&Aerospace Co., Ltd.', 'KOSPI'),
    ('086280', '00360595', '현대글로비스', 'HYUNDAI GLOVIS Co., LTD.', 'KOSPI'),
    ('086520', '00536541', '에코프로', 'ECOPRO CO.,LTD', 'KOSDAQ'),
    ('086790', '00547583', '하나금융지주', 'Hana Financial Group Inc.', 'KOSPI'),
    ('090430', '00583424', '아모레퍼시픽', 'AMOREPACIFIC CORP.', 'KOSPI'),
    ('096770', '00631518', 'SK이노베이션', 'SK Innovation Co., Ltd.', 'KOSPI'),
    ('105560', '00688996', 'KB금융', 'KB Financial Group Inc.', 'KOSPI'),
    ('138040', '00860332', '메리츠금융지주', 'MERITZ FINANCIAL GROUP INC.', 'KOSPI'),
    ('161390', '00937324', '한국타이어앤테크놀로지', 'HANKOOK TIRE & TECHNOLOGY CO.,LTD', 'KOSPI'),
    ('196170', '00989619', '알테오젠', 'Alteogen Inc.', 'KOSDAQ'),
    ('207940', '00877059', '삼성바이오로직스', 'SAMSUNG BIOLOGICS CO.,LTD.', 'KOSPI'),
    ('247540', '01160363', '에코프로비엠', 'ECOPRO BM CO.,LTD.', 'KOSDAQ'),
    ('259960', '00760971', '크래프톤', 'KRAFTON, Inc.', 'KOSPI'),
    ('267250', '01205709', 'HD현대', 'HD HYUNDAI CO.,LTD.', 'KOSPI'),
    ('267260', '01205851', 'HD현대일렉트릭', 'HD HYUNDAI ELECTRIC CO.,LTD', 'KOSPI'),
    ('272210', '00339391', '한화시스템', 'HANWHA SYSTEMS Co., Ltd.', 'KOSPI'),
    ('278470', '01190568', '에이피알', 'APR Co., Ltd.', 'KOSPI'),
    ('298040', '01316245', '효성중공업', 'Hyosung Heavy Industries Corporation', 'KOSPI'),
    ('316140', '01350869', '우리금융지주', 'Woori Financial Group Inc.', 'KOSPI'),
    ('323410', '01133217', '카카오뱅크', 'KakaoBank Corp.', 'KOSPI'),
    ('329180', '01390344', 'HD현대중공업', 'HD HYUNDAI HEAVY INDUSTRIES CO.,LTD.', 'KOSPI'),
    ('352820', '01204056', '하이브', 'HYBE Co., Ltd.', 'KOSPI'),
    ('373220', '01515323', 'LG에너지솔루션', 'LG ENERGY SOLUTION, LTD.', 'KOSPI'),
    ('402340', '01596425', 'SK스퀘어', 'SK Square Co., Ltd.', 'KOSPI');

INSERT INTO issuer (
    id, dart_corp_code, name_ko, name_en, corporation_class, created_at, updated_at
)
SELECT
    MD5('kmarket:issuer:' || catalog.dart_corp_code)::UUID,
    catalog.dart_corp_code,
    catalog.name_ko,
    catalog.name_en,
    CASE WHEN catalog.market = 'KOSPI' THEN 'Y' ELSE 'K' END,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM service_stock_catalog catalog
ON CONFLICT (dart_corp_code) DO UPDATE
SET name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    corporation_class = EXCLUDED.corporation_class,
    updated_at = EXCLUDED.updated_at;

INSERT INTO security (
    id, issuer_id, stock_code, market, created_at, updated_at,
    common_stock, active, master_updated_at
)
SELECT
    MD5('kmarket:security:' || catalog.stock_code)::UUID,
    issuer.id,
    catalog.stock_code,
    catalog.market,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    TRUE,
    TRUE,
    CURRENT_TIMESTAMP
FROM service_stock_catalog catalog
JOIN issuer ON issuer.dart_corp_code = catalog.dart_corp_code
ON CONFLICT (stock_code) DO UPDATE
SET issuer_id = EXCLUDED.issuer_id,
    market = EXCLUDED.market,
    common_stock = TRUE,
    active = TRUE,
    master_updated_at = EXCLUDED.master_updated_at,
    updated_at = EXCLUDED.updated_at;

INSERT INTO stock_alias (security_id, alias, normalized_alias, locale)
SELECT security.id, catalog.stock_code, LOWER(catalog.stock_code), 'und'
FROM service_stock_catalog catalog
JOIN security ON security.stock_code = catalog.stock_code
ON CONFLICT (security_id, normalized_alias) DO NOTHING;

INSERT INTO stock_alias (security_id, alias, normalized_alias, locale)
SELECT security.id, catalog.name_ko, LOWER(catalog.name_ko), 'ko'
FROM service_stock_catalog catalog
JOIN security ON security.stock_code = catalog.stock_code
ON CONFLICT (security_id, normalized_alias) DO NOTHING;

INSERT INTO stock_alias (security_id, alias, normalized_alias, locale)
SELECT security.id, catalog.name_en, LOWER(catalog.name_en), 'en'
FROM service_stock_catalog catalog
JOIN security ON security.stock_code = catalog.stock_code
ON CONFLICT (security_id, normalized_alias) DO NOTHING;
