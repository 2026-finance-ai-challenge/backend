# 수집 데이터 백업

2026년 8월 23일 기준 지원 종목 공시 원문과 PostgreSQL RAG 데이터의 복원용 백업이다.

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
- 원문 ZIP 저장소: 약 9.86GiB
- PostgreSQL 데이터베이스: 약 11.86GiB
- 원문 tar 분할 백업: 10.98GB
- PostgreSQL 분할 백업: 9.69GB
- Git LFS 백업 합계: 20.66GB

## 복원

```shell
git lfs pull
mkdir -p data/opendart-archives
cat data/dataset/opendart-archives.tar.part-* | tar -xf -
cat data/dataset/kmarket.pg_dump.part-* > /tmp/kmarket.pg_dump
pg_restore --clean --if-exists --no-owner --dbname=kmarket /tmp/kmarket.pg_dump
```
