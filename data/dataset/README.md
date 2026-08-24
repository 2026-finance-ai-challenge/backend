# 수집 데이터 백업

2026년 8월 24일 기준 지원 종목 공시 원문과 PostgreSQL RAG 데이터의 복원용 백업이다.

- `opendart-archives.tar.part-*`: 종목별 OpenDART API ZIP 및 DART 뷰어 대체 ZIP
- `kmarket.pg_dump.part-*`: PostgreSQL 사용자 정의 형식 백업
- 분할 파일은 Git LFS로 관리한다.
- `.env`와 API 키는 포함하지 않는다.

## 스냅샷

- 아카이브 카탈로그: 237,122건
- 검증 완료 공시 원문: 234,070건
- OpenDART ZIP: 224,455건
- DART 뷰어 대체 ZIP: 9,615건
- DB 전체 공시 메타데이터 벡터: 242,615건
- 지원 종목 공시 제목 번역 적용: 234,071 / 234,071건
- 공시 제목 번역 메모리: `READY` 2,834건
- 공시 제목 번역 작업: `READY` 2,834건, 오류 0건
- 원문 ZIP 저장소: 약 9.86GiB
- PostgreSQL 데이터베이스: 약 11.86GiB
- 원문 tar 분할 백업: 10.98GB
- PostgreSQL 분할 백업: 10.79GB
- Git LFS 백업 합계: 21.76GB
- PostgreSQL 백업 SHA-256: `c13b8ec9f957a8151f2db87ac196007c1ad27f4f843ff063a4588b7d2e636656`
- 개인정보 보호: 회원·채팅·세무 문서·관심종목·최근 조회·알림·보안 감사·용어 클릭 테이블 데이터 제외

PostgreSQL 18 격리 컨테이너에서 전체 백업을 복원한 뒤 공시 제목 번역 적용률, 번역 작업 상태, 제외 테이블 0건을 재검증했다.

## 복원

```shell
git lfs pull
mkdir -p data/opendart-archives
cat data/dataset/opendart-archives.tar.part-* | tar -xf -
cat data/dataset/kmarket.pg_dump.part-* > /tmp/kmarket.pg_dump
pg_restore --clean --if-exists --no-owner --dbname=kmarket /tmp/kmarket.pg_dump
```
