package com.example.member.global.security;

import com.example.member.global.common.ApiResponse;
import com.example.member.global.common.ErrorResponse;
import com.example.member.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 필터 체인에서 거부된 요청을 공통 응답 형식으로 바꾼다 (sp-docs/api-contract.md §7).
 *
 * <p>{@code GlobalExceptionHandler}({@code @RestControllerAdvice}) 는 DispatcherServlet 안에서만
 * 동작한다. 인가 거부는 그 앞의 필터에서 일어나므로 여기서 직접 본문을 쓴다.
 *
 * <table>
 * <caption>필터 단계가 내는 코드 (sp-docs/api-contract.md §8.1 §8.5)</caption>
 * <tr><th>상황</th><th>코드</th></tr>
 * <tr><td>토큰 없음 · 다른 스킴 · {@code b64token} 문법 위반</td><td>401 {@code A001}</td></tr>
 * <tr><td>서명·형식이 잘못된 토큰, Claim 계약 위반</td><td>401 {@code A002}</td></tr>
 * <tr><td>만료된 토큰</td><td>401 {@code A003}</td></tr>
 * <tr><td>인증했으나 권한 없음</td><td>403 {@code A004}</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(response, failureCode(authException));
	}

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, ErrorCode.ACCESS_DENIED);
	}

	/**
	 * 인증 실패를 {@code A001}·{@code A002}·{@code A003} 으로 가른다
	 * (sp-docs/api-contract.md §8.5). <b>세 서비스가 공유하는 경계다.</b>
	 *
	 * <p><b>예외 타입으로 가른다. 메시지 문자열을 보지 않는다</b> — 버전업에 깨진다(§8.5).
	 * 근거는 {@code NimbusJwtDecoder#decode} 의 순서다: 파싱·서명 검증이 실패하면
	 * {@code BadJwtException} 이고, 그것을 통과한 뒤의 검증기 실패만
	 * {@link JwtValidationException}({@code BadJwtException} 의 하위)이다.
	 *
	 * <p>기본 검증기는 {@code JwtTimestampValidator} 와
	 * {@code X509CertificateThumbprintValidator} 둘이며(측정: {@code JwtValidators#createDefault})
	 * 후자는 {@code cnf} claim 이 없는 토큰을 통과시키므로, 여기서
	 * {@code JwtValidationException} 은 <b>만료</b>다. Claim 계약 위반은 검증기 결과가 아니라
	 * {@code BadJwtException} 으로 오므로({@link ClaimContractValidator} 참조) 만료와 섞이지
	 * 않는다.
	 *
	 * <p>필터 단계의 예외는 {@code InvalidBearerTokenException} 으로 한 겹 감싸여 오고 원인이
	 * 보존되므로(측정: {@code JwtAuthenticationProvider#getJwt}) 원인 사슬을 따라간다.
	 * {@code JwtException} 이 사슬에 없으면 <b>해석할 자격증명이 아예 없는 것</b>이고
	 * {@code A001} 이다 — 헤더가 없을 때와 {@code b64token} 문법을 위반했을 때가 여기다. 후자는
	 * {@code DefaultBearerTokenResolver} 가 디코더 앞에서 끊기 때문이며 그 동작이 RFC 6750
	 * §2.1 과 일치한다(§8.5).
	 */
	private static ErrorCode failureCode(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			// 하위 타입을 먼저 본다. JwtValidationException 은 JwtException 이기도 하다
			if (cause instanceof JwtValidationException) {
				return ErrorCode.EXPIRED_TOKEN;
			}
			if (cause instanceof JwtException) {
				return ErrorCode.INVALID_TOKEN;
			}
		}
		return ErrorCode.UNAUTHORIZED;
	}

	private void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		// 예외 메시지를 내보내지 않는다. ErrorCode 의 고정 메시지만 쓴다 (sp-docs/api-contract.md §8.5)
		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ErrorResponse error = new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), List.of());
		this.objectMapper.writeValue(response.getWriter(), ApiResponse.failure(error));
	}

}
