# member-service

회원 관리와 인증(JWT 발급)을 담당하는 마이크로서비스.

> 상태: **스캐폴딩 전** — 작업 항목 M-01부터 시작한다.

| 항목 | 값 |
| --- | --- |
| 포트 | 8081 |
| 데이터베이스 | `member_db` (MySQL 8.0) |
| 소유 테이블 | `member`, `refresh_token` |
| 기본 패키지 | `com.example.member` |
| JWT 역할 | **발급(서명)** — RS256 RSA **개인키** 보유 |

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
| 기능 요구사항 | `sp-docs/requirements/member.md` |

AI 워커는 [`CLAUDE.md`](CLAUDE.md)를 먼저 읽는다.

## 책임 범위

두 패키지로 나뉜다. 성격이 다르지만 `member` 테이블을 공유하므로 별도 서비스로 분리하지 않는다 (`sp-docs/adr/0006-auth-inside-member-service.md`).

| 패키지 | 관심사 | API 경로 |
| --- | --- | --- |
| `auth` | 인증 — 로그인, 로그아웃, 토큰 재발급 | `/api/v1/auth/**` |
| `member` | 회원 리소스 — 가입, 중복 확인, 내 정보, 탈퇴 | `/api/v1/members/**` |
| `internal` | 서비스 간 전용 (외부 미노출) | `/internal/v1/**` |

**의존 방향은 `auth -> member` 단방향이다.**

## 주요 제약

- `board_db`를 조회하지 않는다. board-service를 호출하지 않는다
- JWT 필터를 직접 만들지 않는다. Spring Security 표준(`NimbusJwtEncoder`)을 쓴다
- 개인키(`private.pem`)와 `.env`를 커밋하지 않는다
- 비밀 값은 환경변수로만 주입하고 기본값을 두지 않는다
- 시간대는 `Asia/Seoul`로 명시 설정한다

## 실행 (스캐폴딩 이후)

**1차는 Docker를 사용하지 않는다.** MySQL은 로컬에 직접 설치한다.

```sql
CREATE DATABASE member_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

| 환경변수 | 필수 | 설명 |
| --- | --- | --- |
| `JWT_PRIVATE_KEY` | O | RSA 개인키 (PKCS#8 PEM). 기본값 없음 |
| `DB_URL` | | 기본값 `jdbc:mysql://localhost:3306/member_db` |
| `DB_USERNAME` / `DB_PASSWORD` | | |
| `INTERNAL_API_KEY` | O | 내부 API 인증 키 |

- API 문서: http://localhost:8081/swagger-ui.html
- 헬스체크: http://localhost:8081/actuator/health
