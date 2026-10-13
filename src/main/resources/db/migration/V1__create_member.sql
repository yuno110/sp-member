-- 정본: sp-docs/domain-model.md §3.1 (컬럼), §3.2 (인덱스)
-- 버전 번호는 계획이 배정했다 (sp-docs/plan/phase1.md §2.4)
--
-- member 는 프로필(닉네임)만 소유한다. email·password·role 은 sp_auth.account 에 있다.
-- account_id 는 그 account.id 의 논리 참조다. 스키마 경계를 넘는 참조에는 FK 를 두지 않는다.
CREATE TABLE member
(
    -- nickname: NULL 허용 (탈퇴 시 비운다). 폭 30 은 입력 규칙(2~10자)보다 넓은 의도된 여유다
    -- deleted: 탈퇴 여부 (Soft Delete)
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL,
    nickname   VARCHAR(30) NULL,
    deleted    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    -- 탈퇴 계정의 프로필 재생성을 이 UNIQUE 가 막는다. 별도 묘비 테이블을 두지 않는다
    CONSTRAINT uk_member_account_id UNIQUE (account_id),
    -- 탈퇴 시 nickname 을 NULL 로 비우는 이유다. 값을 남기면 그 닉네임이 영구 소각되고,
    -- 마스킹 문자열을 저장하면 두 번째 탈퇴가 UNIQUE 위반이 된다 (sp-docs/domain-model.md §3.1)
    CONSTRAINT uk_member_nickname UNIQUE (nickname)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
