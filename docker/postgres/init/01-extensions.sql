-- ========================================================
-- IFMS PostgreSQL 초기화 스크립트 (로컬 개발 전용)
-- ========================================================
-- 실행 시점: `/docker-entrypoint-initdb.d` 규약
--            → 볼륨이 **비어있을 때만** 1회 실행 (PostgreSQL 공식 동작)
--            → 기존 볼륨에서는 재실행 안 됨. 재적용하려면:
--                 docker compose down -v && docker volume rm ifms_postgres_data
--
-- 운영 DB 경고:
--   이 스크립트는 운영 PostgreSQL 부트스트랩에 사용하지 마세요.
--   운영 전환 시 Flyway 또는 Liquibase로 마이그레이션 이관 필요
--   (erd.md §12.2 참조).
--
-- 권한 분리 (향후 ADR 후보):
--   현재 POSTGRES_USER=ifms 가 superuser 권한 보유 (로컬 편의).
--   운영은 아래 3계정 분리 권장:
--     - ifms_app       : 애플리케이션 CRUD 전용
--     - ifms_ddl       : 마이그레이션 전용 (DDL 권한)
--     - ifms_readonly  : 대시보드·감사 조회 전용
-- ========================================================

-- 운영 전환 시 name 부분 일치 검색 성능 확보 (api-spec.md §4.1)
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- 타임존 확인용 (진단 편의)
SELECT current_setting('TIMEZONE') AS timezone;
