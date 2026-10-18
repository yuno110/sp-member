package com.example.member.global.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * {@code role} claim 을 {@link GrantedAuthority} 로 바꾼다 (sp-docs/security.md §5.1).
 *
 * <p><b>검증 스타터가 하는 것은 서명 검증·만료 확인·{@code SecurityContext} 주입이다</b>
 * (sp-docs/tech-stack.md §2.1). 이 변환과 Claim 계약 검증은 그 목록에 없어 서비스가 쓴다 —
 * 등록은 {@code global/config/SecurityConfig} 가 {@code JwtAuthenticationConverter} 를 통해
 * 하고, member 의 계약 검증은 디코더의 {@link ClaimContractValidator} 가 한다 (같은 절의 집행
 * 지점 표). 손으로 쓴 보안 코드가 이 패키지에 더 있다 — {@link SecurityErrorResponder},
 * {@link InternalApiKeyFilter}, {@link CurrentMemberArgumentResolver} 가 그렇다.
 *
 * <p>접두어 {@code ROLE_} 은 Spring Security 의 {@code hasRole} 이 보는 것이고,
 * sp-docs/plan/phase1.md §6 B-04 의 검증표가 {@code role = ADMIN} 토큰에
 * {@code ROLE_ADMIN} 을 요구한다 — <b>board 도 같은 이름을 쓰고 auth 는 그 이름을 만들지
 * 않는다</b>. auth 는 변환을 두지 않고 권한을 {@code LoginAccount.role} 로만 전달한다
 * (sp-docs/security.md §5.1).
 *
 * <p><b>{@code role} claim 이 있다는 것과 그 값이 Claim 계약의 허용값이라는 것을
 * {@link ClaimContractValidator} 가 보장한다</b> (sp-docs/api-contract.md §6). 없는 토큰도 열거
 * 밖인 토큰도 인증 단계에서 이미 401 {@code A002} 로 거부되므로 여기서 다시 보지 않는다 — 두
 * 군데서 보면 어느 쪽이 진짜 관문인지 알 수 없게 된다. <b>M-12 가 값 검증을 더해 이 컨버터가
 * {@code ROLE_BOGUS} 를 조립할 경로가 member 에서 닫혔다</b> (sp-docs/plan/phase1.md §5 M-12).
 */
@Component
public class RoleClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	/** sp-docs/api-contract.md §6 의 권한 claim 이름. */
	public static final String CLAIM_ROLE = "role";

	private static final String AUTHORITY_PREFIX = "ROLE_";

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		return List.of(new SimpleGrantedAuthority(AUTHORITY_PREFIX + jwt.getClaimAsString(CLAIM_ROLE)));
	}

}
