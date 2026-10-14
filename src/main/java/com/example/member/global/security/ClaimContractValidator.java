package com.example.member.global.security;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 서명을 통과한 토큰이 sp-docs/api-contract.md §6 의 Claim 계약을 지키는지 본다.
 *
 * <p>서명만으로는 부족하다. 공개키가 맞아도 발급자가 다른 토큰이나 {@code sub}·{@code role} 이
 * 빠진 토큰은 이 서비스가 해석할 수 없다. 그런 토큰을 통과시키면 {@link LoginMember} 가
 * {@code null} 필드로 만들어져 인증 실패가 "익명이지만 인증된 사용자"로 위장된다.
 *
 * <p>등록은 하지 않는다 — 스타터가 컨텍스트의 {@code OAuth2TokenValidator<Jwt>} 빈을 모아
 * 기본 검증기 뒤에 붙인다 (측정: {@code OAuth2ResourceServerJwtConfiguration.JwtDecoderConfiguration}
 * 의 생성자가 {@code ObjectProvider<OAuth2TokenValidator<Jwt>>} 를 받고 {@code getValidators} 가
 * 그것을 {@code DelegatingOAuth2TokenValidator} 에 더한다). 그래서 {@code JwtDecoder} 를 직접
 * 만들지 않아도 된다 (sp-docs/tech-stack.md §2.1).
 *
 * <h2>왜 결과가 아니라 예외로 실패하는가</h2>
 *
 * <p>{@code A001}·{@code A002}·{@code A003} 의 경계는 <b>예외 타입으로만</b> 가른다 —
 * 메시지 문자열을 파싱하면 버전업에 깨진다 (sp-docs/api-contract.md §8.5). 그 판정은
 * {@link SecurityErrorResponder} 에 있고, 만료({@code A003})를 알아보는 신호가
 * {@link JwtValidationException} 하나다.
 *
 * <p><b>검증 결과로 실패를 돌려주면 그 신호가 망가진다.</b> {@code NimbusJwtDecoder} 는 검증기
 * 결과에 오류가 있으면 이유와 무관하게 {@code JwtValidationException} 을 던지므로(측정:
 * {@code NimbusJwtDecoder#validateJwt}), 발급자 불일치가 <b>만료로 읽힌다</b> — §8.5 는 그것을
 * {@code A002} 로 정했고 "사유를 가르지 않는다"고 적었다.
 *
 * <p>그래서 여기서는 {@link BadJwtException} 을 던진다. {@code JwtDecoder#decode} 가 던지기로
 * 선언한 타입이고, 검증기 결과를 거치지 않으므로 {@code JwtValidationException} 이 되지 않는다.
 * 필터 단계는 그것을 {@code InvalidBearerTokenException} 으로 한 겹 감싸되 원인을 보존하므로
 * (측정: {@code JwtAuthenticationProvider#getJwt}) 판정이 원인 사슬에서 찾아낸다.
 *
 * <p>기본 검증기는 {@code JwtTimestampValidator} 와 {@code X509CertificateThumbprintValidator}
 * 둘이고(측정: {@code JwtValidators#createDefault}) 후자는 {@code cnf} claim 이 없는 토큰을
 * 통과시킨다. 따라서 이 서비스에서 {@code JwtValidationException} 은 시각 검증 실패다.
 */
@Component
public class ClaimContractValidator implements OAuth2TokenValidator<Jwt> {

	/** sp-docs/api-contract.md §6 이 고정한 발급자 식별자. member 는 auth 가 발급한 토큰만 받는다. */
	public static final String ISSUER = "auth-service";

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		requireIssuer(jwt);
		requireNumericSubject(jwt);
		requireRole(jwt);
		return OAuth2TokenValidatorResult.success();
	}

	/**
	 * {@code iss} 가 계약값인지 본다.
	 *
	 * <p>claim 을 {@code toString()} 으로 비교한다. {@code Jwt} 를 만들 때 {@code iss} 는 URL 로
	 * 변환될 수 있어 타입이 고정되지 않으므로, {@code JwtIssuerValidator} 가 쓰는 것과 같은
	 * 비교를 한다.
	 */
	private void requireIssuer(Jwt jwt) {
		Object issuer = jwt.getClaims().get(JwtClaimNames.ISS);
		if (issuer == null || !ISSUER.equals(issuer.toString())) {
			// 받은 값을 메시지에 넣지 않는다. 응답에는 ErrorCode 의 고정 메시지만 나간다
			throw new BadJwtException("발급자가 계약값이 아니다 (sp-docs/api-contract.md §6)");
		}
	}

	/** {@code sub} 는 {@code account.id} 의 문자열이다 (§6). 숫자가 아니면 해석할 수 없다. */
	private void requireNumericSubject(Jwt jwt) {
		String subject = jwt.getSubject();
		if (subject == null) {
			throw new BadJwtException("sub claim 이 없다 (sp-docs/api-contract.md §6)");
		}
		try {
			Long.valueOf(subject);
		} catch (NumberFormatException e) {
			throw new BadJwtException("sub claim 이 account.id 가 아니다 (sp-docs/api-contract.md §6)");
		}
	}

	/** {@code role} 이 없으면 권한을 만들 수 없다 (§6, sp-docs/security.md §5.1). */
	private void requireRole(Jwt jwt) {
		if (!StringUtils.hasText(jwt.getClaimAsString(RoleClaimConverter.CLAIM_ROLE))) {
			throw new BadJwtException("role claim 이 없다 (sp-docs/api-contract.md §6)");
		}
	}

}
