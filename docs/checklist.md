# member-service 구현 체크리스트

`M-xx` 항목 **상태의 단일 원본**이다. 항목의 범위·완료 기준·검증은 `simple-docs/plan/phase1.md`의 같은 ID를 본다.

줄 형식: `- [ ] <ID> <이름> · 상태 <todo|doing|review|blocked|done> · 커밋 <해시 또는 ->`
`blocked`는 줄 끝에 `· 사유: ...`를 붙인다. 사유는 다음 작업자가 이어받을 수 있을 만큼 구체적으로 적는다.

절차는 `simple-docs/process/dev-workflow.md`를 따른다.

**여러 워커가 이 파일을 고칠 수 있다.** 자기 항목의 줄만 수정하고, 파일을 재정렬하거나 다른 줄을 건드리지 않는다 (`simple-docs/process/orchestration.md` §6).

## 기반 단계 (순차)

뒤의 모든 항목이 의존한다. 순서대로 진행한다.

- [ ] M-01 프로젝트 스캐폴딩 · 상태 todo · 커밋 -
- [ ] M-02 공통 기반 · 상태 todo · 커밋 -
- [ ] M-03 도메인 기반 · 상태 todo · 커밋 -
- [ ] M-04 보안 기반 · 상태 todo · 커밋 -

## 기능 단계

기반 산출물을 읽기만 하고 자기 파일을 만든다. 기반 경로의 파일을 고쳐야 하면 BLOCKED로 보고한다.

- [ ] M-05 회원가입과 중복 확인 · 상태 todo · 커밋 -
- [ ] M-06 로그인 · 상태 todo · 커밋 -
- [ ] M-07 토큰 재발급과 로그아웃 · 상태 todo · 커밋 -
- [ ] M-08 내 정보 조회·수정 · 상태 todo · 커밋 -
- [ ] M-09 비밀번호 변경과 탈퇴 · 상태 todo · 커밋 -
- [ ] M-10 회원 프로필 조회와 내부 API · 상태 todo · 커밋 -
- [ ] M-11 마무리 · 상태 todo · 커밋 -

## 선행 조건

M-01을 시작하기 전에 아래가 준비되어야 한다. 준비되지 않았으면 `blocked`로 두고 보고한다.

- [ ] MySQL 8.0 로컬 설치, `member_db` 스키마 생성 (`simple-docs/tech-stack.md` §4.1)
- [ ] RSA 키 페어 생성 (`simple-docs/tech-stack.md` §4.2)
- [ ] 문서 저장소 클론 (`../simple-docs`)
