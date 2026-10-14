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
 * 값이 {@code USER}·{@code ADMIN} 중 하나인지는 발급 측 계약이며(§6), 권한 판정은
 * {@link RoleClaimConverter} 가 만든 {@code GrantedAuthority} 로 한다.
 *
 * @param accountId {@code sub}. 전역 식별자(= {@code account.id})
 * @param role {@code role} claim
 */
public record LoginMember(Long accountId, String role) { }
