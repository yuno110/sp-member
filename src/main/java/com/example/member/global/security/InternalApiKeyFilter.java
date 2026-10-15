package com.example.member.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 내부 API 의 {@code X-Internal-Api-Key} 헤더 검증 (sp-docs/security.md §6).
 *
 * <p>{@code SecurityConfig} 의 {@code /internal/**} 전용 체인에만 등록한다. <b>빈으로 두지
 * 않는다</b> — {@code Filter} 타입 빈은 Boot 가 서블릿 컨테이너에 자동 등록하므로 업무 경로까지
 * 이 필터를 지나게 된다.
 *
 * <p><b>거부는 403 {@code A004} 다.</b> 401·403 둘 다 정본이 허용하며
 * (sp-docs/plan/phase1.md §5 M-10, sp-docs/security.md §6) 403 을 고른 이유가 둘이다. 첫째,
 * 이 키는 WWW-Authenticate 로 재시도를 유도할 인증 스킴이 아니다. 둘째, M-04 의 완료 기준이
 * "{@code /internal/**} 은 외부 인증 체인에서 분리되어 있다" 를 <b>401 이 아님</b>으로 관측하므로
 * (그 체인은 토큰을 보지 않는다), 키 거부를 401 로 내면 그 관측이 깨진다.
 *
 * <p><b>키 거부를 "프로필 없음" 으로 해석하지 않는 것은 호출 측의 몫이다.</b> board 는 이
 * 응답을 503 {@code S001} 인프라 경보로 분류한다 (sp-docs/api-contract.md §5.1) — 키를 회전하고
 * board 만 갱신을 놓치면 전 사용자의 쓰기가 멈추므로 경보가 떠야 한다 (sp-docs/security.md §6).
 *
 * <p><b>키 값을 로그·응답에 남기지 않는다</b> (sp-docs/conventions.md §8). 응답 본문은
 * {@link SecurityErrorResponder} 가 쓰는 고정 메시지뿐이다.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

	/** 정본: sp-docs/security.md §6, sp-docs/api-contract.md §5. */
	public static final String HEADER = "X-Internal-Api-Key";

	private final String apiKey;

	private final SecurityErrorResponder securityErrorResponder;

	/**
	 * @param apiKey 설정으로 주입된 내부 API 키. <b>기본값이 없다</b>
	 *         (sp-docs/tech-stack.md §4.3). 비었거나 해석되지 않은 placeholder 리터럴이면
	 *         기동을 실패시킨다 — 그 상태로 기동하면 <b>커밋된 {@code application.yml} 의
	 *         문자열이 내부 API 의 키가 되고</b> 같은 리터럴을 쓰는 호출자가 통과한다.
	 *         {@code @Value} 는 환경변수가 아예 없을 때 placeholder 를 해석하지 못해 예외를
	 *         던지지만(실측), <b>빈 값으로 설정된 경우는 통과시킨다</b>(실측) — 이 가드가 그
	 *         구멍을 막는다. auth 가 {@code ADMIN_PASSWORD_HASH} 에서 같은 이유로
	 *         {@code FlywayConfigurationCustomizer} 가드를 두었다
	 *         (sp-docs/requirements/member.md §10.2)
	 */
	public InternalApiKeyFilter(String apiKey, SecurityErrorResponder securityErrorResponder) {
		if (!StringUtils.hasText(apiKey) || apiKey.startsWith("${")) {
			// 메시지에 키 값을 넣지 않는다 (sp-docs/conventions.md §8)
			throw new IllegalStateException(
					"INTERNAL_API_KEY 가 없다. 내부 API 키는 환경변수로 주입한다 "
							+ "(sp-docs/security.md §6, sp-docs/tech-stack.md §4.3).");
		}
		this.apiKey = apiKey;
		this.securityErrorResponder = securityErrorResponder;
	}

	/**
	 * 헤더가 없거나 값이 다르면 403 이고, 컨트롤러에 도달하지 않는다.
	 *
	 * <p>비교는 {@link MessageDigest#isEqual} 로 한다 — 길이가 같은 입력에서 조기 반환하지 않아
	 * 비교 시간이 일치 지점에 따라 갈리지 않는다.
	 */
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String provided = request.getHeader(HEADER);
		if (provided == null || !MessageDigest.isEqual(
				provided.getBytes(StandardCharsets.UTF_8),
				this.apiKey.getBytes(StandardCharsets.UTF_8))) {

			this.securityErrorResponder.handle(request, response,
					new AccessDeniedException("내부 API 키가 유효하지 않다."));
			return;
		}

		filterChain.doFilter(request, response);
	}

}
