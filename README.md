# member-service

회원 **프로필**을 담당하는 마이크로서비스.

> 진행 상태의 단일 원본은 [`docs/checklist.md`](docs/checklist.md)다. 여기에 상태를 적지 않는다.

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

## 로컬 실행

**1차는 Docker를 사용하지 않는다.** MySQL은 로컬에 직접 설치한다.

### 1. MySQL — 최초 1회

```sql
CREATE DATABASE sp_member DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET PERSIST time_zone = '+09:00';
```

스키마는 Flyway가 만든다 (`src/main/resources/db/migration`). 손으로 테이블을 만들지 않는다.

### 2. 공개키 배치 — 최초 1회

아래 **"공개키 배치"** 절차를 따른다. 키는 이미 `src/main/resources/jwt-public.pem`에 커밋되어 있으므로, 새로 클론한 뒤에는 할 일이 없다. **키를 다시 만들었을 때만** 다시 배치한다.

### 3. `application-local.yml` — 최초 1회

로컬에서는 비밀 값을 환경변수가 아니라 파일로 준다. 이 파일은 `.gitignore`에 있어 **평문으로 적어도 커밋되지 않는다** (`sp-docs/tech-stack.md` §4.3.1).

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

**템플릿을 복사한 뒤 값을 모두 채운다.** 템플릿에 자리가 있는 것은 넷이다.

| 키 | 채울 것 |
| --- | --- |
| `spring.datasource.password` | MySQL 비밀번호 |
| `member-service.internal-api-key` | 내부 API 키. **board와 같은 값** |
| `cors.allowed-origins` | 허용 오리진. 로컬은 `http://localhost:3000` |
| `spring.jpa.show-sql` · `logging.level.org.hibernate.orm.jdbc.bind` | 그대로 둔다 (로컬만 SQL 로그 ON) |

> **`cors.allowed-origins`가 없으면 `local` 프로파일로 기동할 수 없다.** 값이 없으면 `SecurityConfig.corsConfigurationSource`의 `@Value` 해석이 실패해 `BeanCreationException`으로 **기동이 멈춘다.** 허용 오리진은 환경이 정하는 정책값이므로 기본값을 두지 않는다 (`sp-docs/security.md` §1.1, `sp-docs/tech-stack.md` §4.3). **템플릿보다 오래된 `application-local.yml`을 쓰고 있으면 이 키가 빠져 있다** — 템플릿과 대조해 채운다.
>
> `member-service.internal-api-key`도 같다. 빠지면 `/internal/**` 필터 체인 빈 생성이 실패한다. **다만 빈 문자열은 해석에 성공한다** — 없을 때만 기동이 멈추므로, 비워 두지 않는다 (`sp-docs/tech-stack.md` §4.3).

### 4. 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test    # 프로파일은 build.gradle 이 test 로 고정한다. 로컬 설정·실제 키를 쓰지 않는다
```

- API 문서: http://localhost:8081/swagger-ui.html (**`prod` 프로파일에서는 404다**)
- 헬스체크: http://localhost:8081/actuator/health — `/actuator`에서 노출되는 것은 이것뿐이다. 나머지 경로는 무토큰 **401 `A001`**, 유효 토큰 **403 `A004`**다 (실측)

### 환경변수

`local` 프로파일은 아래를 `application-local.yml`에서 읽는다. 배포 환경에서는 그 파일을 두지 않고 환경변수로 주입한다 (`sp-docs/tech-stack.md` §4.3).

**목록은 `application.yml`의 `${...}` 전부다.** 다른 데서 읽는 비밀 값은 없다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_PASSWORD` | **없음** | MySQL 비밀번호. 없으면 `Access denied ... (using password: YES)`로 기동 실패 — 비밀번호가 틀린 것이 아니라 값이 없는 것이다 |
| `INTERNAL_API_KEY` | **없음** | 내부 API(`/internal/v1/members/**`) 인증 키. **board와 같은 값이어야 한다** (`sp-docs/security.md` §6) |
| `CORS_ALLOWED_ORIGINS` | **없음** | 허용 오리진 목록(쉼표 구분). 비었거나 `*`가 섞이면 기동 실패 |
| `DB_URL` | `jdbc:mysql://localhost:3306/sp_member?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` | |
| `DB_USERNAME` | `root` | |

**비밀값과 정책값에는 기본값을 두지 않는다.** 임의 기본값을 두면 그 값이 정해진 정책인지 임시값인지 구분할 수 없고 운영에 그대로 나간다. 없으면 기동이 실패하는 것이 의도된 동작이다.

**개인키 관련 환경변수가 없다.** 이 서비스는 서명하지 않으므로 `JWT_PRIVATE_KEY_LOCATION`을 쓰지 않는다. 공개키는 커밋된 `classpath:jwt-public.pem`이다.

시간대는 실행 환경이 정한다. 환경변수로 넘길 것은 없고, JVM이 KST가 아니어도 DB 경계는 고정된다 (`sp-docs/tech-stack.md` §6.2).

### `prod` 프로파일

`application-prod.yml`은 운영에서 꺼야 하는 것만 적는다. **비밀 값을 적지 않는다** — 위 환경변수로 주입한다.

| 끄는 것 | 왜 |
| --- | --- |
| `springdoc.api-docs.enabled` · `springdoc.swagger-ui.enabled` | API 문서를 운영에서 비활성화한다 (`sp-docs/nfr.md` §8). **둘 다 끈다** — UI만 끄면 `/v3/api-docs`가 전체 엔드포인트 목록을 그대로 낸다 |
| `spring.jpa.show-sql` · `logging.level.org.hibernate.*` | SQL·바인드 파라미터 로그는 로컬만 ON이다 (`sp-docs/nfr.md` §5) |

```bash
java -jar build/libs/member-service.jar --spring.profiles.active=prod
```

## 공개키 배치

**이 저장소에는 공개키만 둔다.** 개인키는 auth-service 하나만 갖는다. 키 관리 규칙은 `sp-docs/security.md` §3이 정본이다.

1. 키 페어는 **auth-service 쪽에서** 만든다 (`sp-docs/tech-stack.md` §4.2). member는 만들지 않는다

   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
   openssl rsa -in private.pem -pubout -out public.pem
   ```

2. **공개키만** 이 저장소로 가져온다

   ```bash
   cp public.pem sp-member/src/main/resources/jwt-public.pem
   ```

3. 커밋한다. **공개키는 커밋해도 된다** — 유출돼도 토큰을 위조할 수 없다

4. 기동해서 확인한다. 파일이 없거나 PEM이 아니면 자동구성이 `JwtDecoder`를 만들지 못해 **기동이 실패한다** (실측). 조용히 넘어가지 않는 것이 의도다

| 키 | 어디에 | 커밋 |
| --- | --- | --- |
| `private.pem` | **저장소 밖.** auth-service만 갖는다 | **절대 금지** |
| `jwt-public.pem` | `src/main/resources/jwt-public.pem` | 가능 |

- 설정 키는 `spring.security.oauth2.resourceserver.jwt.public-key-location`이고 값은 `classpath:jwt-public.pem`이다 (`sp-docs/tech-stack.md` §2.1). 스타터가 이 값으로 `JwtDecoder`를 자동구성한다 — 필터를 직접 만들지 않는다
- **공개키 배포 지점이 member·board 두 곳이다.** 두 사본이 auth의 키와 같은지는 통합 검증 I-01에서 확인한다
- 키를 다시 만들면 기존 토큰이 전부 무효가 되어 재로그인이 필요하다
- 테스트는 이 키로 서명할 수 없다(짝이 되는 개인키가 없다). 서명이 필요한 테스트는 실행 시점에 키 페어를 만들어 공개키 경로를 덮는다 (`src/test/java/com/example/member/support/TestTokenFactory.java`)
