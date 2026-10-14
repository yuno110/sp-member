package com.example.member.global.security;

import com.example.member.global.error.BusinessException;
import com.example.member.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentMember} 파라미터를 검증된 JWT 에서 채운다.
 *
 * <p>등록은 {@code global/config/WebConfig} 가 한다.
 *
 * <p>인증이 없거나 principal 이 JWT 가 아니면 조용히 {@code null} 을 넣지 않고 예외를 던진다.
 * 기본값으로 메우면 인증 실패가 "익명 사용자"로 위장된다 (sp-docs/security.md §7).
 *
 * <p>{@code sub} 가 숫자이고 {@code role} 이 있다는 것은 {@link ClaimContractValidator} 가
 * 인증 단계에서 보장한다 (sp-docs/api-contract.md §6).
 */
@Component
public class CurrentMemberArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentMember.class)
				&& LoginMember.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return new LoginMember(
				Long.valueOf(jwt.getSubject()),
				jwt.getClaimAsString(RoleClaimConverter.CLAIM_ROLE));
	}

}
