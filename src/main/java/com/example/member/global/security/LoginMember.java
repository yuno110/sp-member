package com.example.member.global.security;

/**
 * 인증된 요청의 주체. <b>검증된 JWT Claim 에서만 만든다</b> (sp-docs/api-contract.md §6).
 *
 * <p>요청 본문·경로 변수의 식별자를 신뢰하지 않는다 (sp-docs/security.md §4).
 *
 * <p><b>{@code nickname} 필드가 없다.</b> Claim 에 없기 때문이다 — auth 는 닉네임을 소유하지
 * 않으므로 서명하지 않는다 (sp-docs/conventions.md §1.2, sp-docs/adr/0012 §3). 닉네임은 이
 * 서비스가 DB 에서 읽는다.
 *
 * <p>{@code role} 을 열거 타입으로 두지 않는다. 권한 컬럼은 auth 소유이고 member 는 Claim 의
 * 값을 그대로 옮기기만 한다 (sp-docs/conventions.md §1.2 의 패키지 구조에 권한 타입이 없다).
 * 값이 {@code USER}·{@code ADMIN} 중 하나인지는 §6 이 고정하고, <b>M-12 부터 member 도 그
 * 값을 집행한다</b> — {@link ClaimContractValidator} 가 열거 밖 {@code role} 을 401
 * {@code A002} 로 거부한다. 발급 측 계약이기만 한 것이 아니므로, 그 검사를 지우면 계약 밖
 * 토큰이 통과한다 (sp-docs/plan/phase1.md §5 M-12).
 *
 * <p><b>이 값으로 권한을 판정하는 자리가 member 에 없다.</b> {@link RoleClaimConverter} 가
 * {@code ROLE_} 권한을 만들어 {@code Authentication} 에 싣지만, 그 권한을 읽는 인가 규칙
 * ({@code hasRole}·{@code @PreAuthorize})이 없고 운영 코드가 {@link #role()} 을 읽지도
 * 않는다. member 의 업무 경로에는 역할로 갈리는 동작이 없고 — ADMIN 으로 갈리는 것은 board 가
 * 갖는다 (sp-docs/security.md §5.2) — 이 서비스가 쓰는 것은 경로별 인증 여부와, 토큰의
 * {@code accountId} 를 자기 자원의 키로 쓰는 것이다 (sp-docs/security.md §5.1 의 첫 행,
 * §4 의 {@code account_id = Claim.sub}). <b>소유자 비교는 member 에 없다</b> — §5.1 의
 * "소유자 검증" 행이 가리키는 것은 board 의 {@code PostService.validateOwner} 다. 그래서
 * {@code role} 이 실제로 쓰이는 자리는 위의 계약 검증이다.
 *
 * @param accountId {@code sub}. 전역 식별자(= {@code account.id})
 * @param role {@code role} claim
 */
public record LoginMember(Long accountId, String role) { }
