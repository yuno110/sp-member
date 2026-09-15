# member-service 구현 체크리스트

`M-xx` 항목 **상태의 단일 원본**이다. 항목의 범위·완료 기준·검증은 `sp-docs/plan/phase1.md` **§5**의 같은 ID를 본다.

줄 형식: `- [ ] <ID> <이름> · 상태 <todo|doing|review|blocked|done> · 커밋 <해시 또는 ->`
`blocked`는 줄 끝에 `· 사유: ...`를 붙인다. 사유는 다음 작업자가 이어받을 수 있을 만큼 구체적으로 적는다.

절차는 `sp-docs/process/dev-workflow.md`를 따른다.

## 상태

| 상태 | 의미 |
| --- | --- |
| `todo` | 시작 전 |
| `doing` | 구현·테스트 중 |
| `review` | **테스트 통과, 리뷰 사이클 안** (리뷰 중이거나 수정 중) |
| `done` | 리뷰 APPROVED + 커밋·푸시 완료 |
| `blocked` | 진행 불가. 사유를 구체적으로 적는다 |

**`doing`에서 곧바로 `done`으로 가지 않는다.** 리뷰를 거치지 않은 항목은 완료가 아니다.
2라운드 이상이면 줄 끝에 `· 리뷰 2라운드`를 붙인다.

**여러 워커가 이 파일을 고칠 수 있다.** 자기 항목의 줄만 수정하고, 파일을 재정렬하거나 다른 줄을 건드리지 않는다 (`sp-docs/process/orchestration.md` §6).

## 기반 단계 (순차)

뒤의 모든 항목이 의존한다. 순서대로 진행한다.

- [x] M-01 프로젝트 스캐폴딩 · 상태 done
- [ ] M-01R 의존성·설정 정정 · 상태 todo
- [ ] M-02 공통 기반 · 상태 todo
- [ ] M-03 도메인 기반 · 상태 todo
- [ ] M-04 보안 기반 · 상태 todo

> **M-01R이 왜 있나** — M-01은 `done`이지만 auth 분리로 완료 기준이 깨졌다. `tech-stack.md` §3.2의 의존성 배정이 바뀌어 member는 서명(`oauth2-jose`)이 아니라 검증(`oauth2-resource-server`)이 필요하다. `phase1.md` §2.6 #2에 해당하므로 M-01을 고치지 않고 후속 항목으로 처리한다.

## 기능 단계

기반 산출물을 읽기만 하고 자기 파일을 만든다. 기반 경로의 파일을 고쳐야 하면 BLOCKED로 보고한다.

- [ ] M-05 프로필 등록과 닉네임 중복 확인 · 상태 todo
- [ ] M-08 내 프로필 조회·수정 · 상태 todo
- [ ] M-09 프로필 탈퇴 · 상태 todo
- [ ] M-10 프로필 조회와 내부 API · 상태 todo
- [ ] M-11 마무리 · 상태 todo

> **M-06·M-07은 없다.** 로그인·재발급·로그아웃이 auth-service로 옮겨가 `AU-06`·`AU-07`이 됐다 (`sp-docs/adr/0012`). **번호는 재사용하지 않는다.**
>
> **M-09에서 비밀번호 변경이 빠졌다.** `AU-08`로 옮겨갔다. M-09는 탈퇴 2단계 중 **2단계(프로필 삭제)**만 담당하며 비밀번호를 받지 않는다.

## 선행 조건

M-01R을 시작하기 전에 아래가 준비되어야 한다. 준비되지 않았으면 `blocked`로 두고 보고한다.

- [ ] **`D-01`(정본 개정)이 `done`이다** (`sp-docs/docs/checklist.md`)
- [x] MySQL 8.0 로컬 설치, `sp_member` 스키마 생성, `SET PERSIST time_zone='+09:00'` (`sp-docs/tech-stack.md` §4.1)
- [x] `application-local.yml` 생성하고 MySQL 비밀번호 기입 (`sp-docs/tech-stack.md` §4.3.1)
- [ ] `application-local.yml`에 `INTERNAL_API_KEY` 추가 (board와 같은 값)
- [ ] **공개키** `jwt-public.pem`을 `src/main/resources/`에 배치 — M-04에서 한다. **개인키는 두지 않는다**
- [x] 문서 저장소 클론 (`../sp-docs`)

> **개인키는 auth-service만 갖는다.** 이 저장소에는 공개키만 둔다 (`sp-docs/security.md` §2·§3).
