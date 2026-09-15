# member-service

회원 **프로필**을 담당하는 마이크로서비스.

> 상태: **스캐폴딩 완료(M-01)** — 다음은 M-01R이다. `D-01`(정본 개정)이 선행 조건이다.

| 항목 | 값 |
| --- | --- |
| 포트 | 8081 |
| 데이터베이스 | `sp_member` (MySQL 8.0) |
| 소유 테이블 | `member` (프로필만) |
| 기본 패키지 | `com.example.member` |
| JWT 역할 | **검증만** — RS256 RSA **공개키**만 보유 (서명 불가) |
| 작업 항목 접두어 | `M-xx` |

**계정은 auth-service(`yuno110/sp-auth`)가 소유한다.** 이메일·비밀번호·권한·RefreshToken이 전부 그쪽에 있다 (`sp-docs/adr/0012`).

## 문서

정본 문서는 별도 저장소에 있다.

```bash
git clone https://github.com/yuno110/sp-docs.git ../sp-docs
```

| 무엇을 찾는가 | 문서 |
| --- | --- |
| 무슨 문서를 읽어야 하나 | `sp-docs/README.md` |
| 지금 할 일 | [`docs/checklist.md`](docs/checklist.md) |
| 작업 항목의 상세 | `sp-docs/plan/phase1.md` |
| 구현·테스트 절차 | `sp-docs/process/dev-workflow.md` |
| 엔드포인트·에러 코드 | `sp-docs/api-contract.md` |
| 엔티티·컬럼 | `sp-docs/domain-model.md` |
| 기능 요구사항 | `sp-docs/requirements/member.md` (담당 열이 `member`인 것) |
| **왜 계정과 프로필이 갈라졌나** | `sp-docs/adr/0012-auth-as-separate-service.md` |

AI 워커는 [`CLAUDE.md`](CLAUDE.md)를 먼저 읽는다.

## 책임 범위

| 패키지 | 관심사 | API 경로 |
| --- | --- | --- |
| `member` | 프로필 리소스 — 등록, 닉네임 중복 확인, 조회·수정, 탈퇴 | `/api/v1/members/**` |
| `internal` | 서비스 간 전용 (외부 미노출). **board가 1차부터 호출한다** | `/internal/v1/members/**` |

**`auth` 패키지는 없다.** auth-service로 옮겨갔다.

### 가입·탈퇴가 2단계다

```
[가입]  1. POST   :8083/api/v1/accounts     계정 생성   (auth)
        2. POST   :8083/api/v1/auth/login   로그인      (auth)
        3. POST   :8081/api/v1/members      프로필 등록  <- 여기

[탈퇴]  1. DELETE :8083/api/v1/accounts/me  계정 탈퇴   (auth, 비밀번호 재확인)
        2. DELETE :8081/api/v1/members/me   프로필 탈퇴  <- 여기
```

- **`POST /api/v1/members`는 인증이 필요하다.** `account_id`는 검증된 JWT의 `sub`에서만 가져온다
- `accountId` 기준으로 **멱등**이다
- **탈퇴 시 비밀번호를 받지 않는다.** 재확인은 1단계에서 끝났고, member는 비밀번호를 갖지 않는다

### 프로필의 세 상태

| 상태 | 표현 |
| --- | --- |
| 미등록 | 행이 없다 |
| 활성 | 행이 있고 `deleted = false` |
| 탈퇴 | 행이 있고 `deleted = true`, **`nickname`은 NULL** |

`account_id` UNIQUE가 탈퇴 계정의 프로필 재생성을 막는다. **`"탈퇴한 회원"`은 응답 시 변환이지 저장이 아니다.**

## 주요 제약

- **`sp_board`·`sp_auth`를 조회하지 않는다.** board·auth를 호출하지 않는다. 호출 방향은 `board -> member` 단방향 하나뿐이다
- **이메일·비밀번호·권한을 다루지 않는다.** auth 소유다
- **개인키를 두지 않는다.** 공개키만 갖는다. 서명 의존성(`oauth2-jose`)도 넣지 않는다
- JWT 필터를 직접 만들지 않는다. Spring Security `oauth2-resource-server`를 쓴다
- **내부 API는 벌크 하나만 둔다.** 존재하지 않는 `accountId`는 결과에서 제외하고 **404를 반환하지 않는다**
- `.env`와 `application-local.yml`을 커밋하지 않는다
- 시간대는 실행 환경이 정한다 (`sp-docs/adr/0011`)

## 실행 (스캐폴딩 이후)

**1차는 Docker를 사용하지 않는다.** MySQL은 로컬에 직접 설치한다.

```sql
CREATE DATABASE sp_member DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

| 환경변수 | 필수 | 설명 |
| --- | --- | --- |
| `DB_URL` | | 기본값 `jdbc:mysql://localhost:3306/sp_member` |
| `DB_USERNAME` / `DB_PASSWORD` | O (비밀번호) | |
| `INTERNAL_API_KEY` | O | 내부 API 인증 키. **board와 같은 값** |

공개키는 `src/main/resources/jwt-public.pem`에 둔다 (커밋 가능). **개인키는 auth-service만 갖는다.**

- API 문서: http://localhost:8081/swagger-ui.html
- 헬스체크: http://localhost:8081/actuator/health
