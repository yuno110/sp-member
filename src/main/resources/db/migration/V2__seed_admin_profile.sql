-- 기본 관리자 프로필. 정본: sp-docs/requirements/member.md §10 §10.1
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase1.md §2.4)
--
-- account_id 는 1 이다. sp_auth 의 V3__seed_admin_account.sql 이 account.id 를 1 로 명시
-- 고정했고, 두 스키마는 서로를 조회할 수 없으므로 이 값이 결정적이어야 한다 (§10.1).
-- 두 seed 의 일치는 통합 검증 I-01 에서 확인한다 (sp-docs/plan/integration.md).
--
-- member.id 는 AUTO_INCREMENT 에 맡긴다. 외부에 노출하지 않으므로 다른 스키마가 참조할 일이
-- 없다 (sp-docs/domain-model.md §3.1). 고정해야 하는 것은 account_id 쪽이다.
--
-- 비밀번호 placeholder 가 없다. member 는 비밀번호를 갖지 않으므로 주입할 비밀 값이 없다
-- (§10.2). auth 의 V3 와 달리 이 스크립트는 환경변수를 요구하지 않는다.
--
-- created_at·updated_at 은 NOT NULL 이고 seed 는 JPA 감사를 거치지 않으므로(SQL 이다) NOW()
-- 를 쓴다. 고정 상수를 박으면 "언제 만들어진 프로필인가" 가 거짓이 된다 (§10.1).
INSERT INTO member (account_id, nickname, deleted, created_at, updated_at)
VALUES (1, '관리자', FALSE, NOW(), NOW());
