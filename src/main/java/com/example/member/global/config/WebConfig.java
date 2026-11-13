package com.example.member.global.config;

import com.example.member.global.ratelimit.FixedWindowRateLimiter;
import com.example.member.global.ratelimit.RateLimitInterceptor;
import com.example.member.global.security.CurrentMemberArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 확장 지점 — {@code @CurrentMember} 리졸버와 요청 수 제한 인터셉터를 등록한다
 * (sp-docs/conventions.md §1.2).
 *
 * <p><b>CORS 는 여기가 아니라 {@link SecurityConfig} 에 있다.</b> preflight(OPTIONS)는 인가
 * 필터보다 앞에서 처리돼야 하므로 Security 필터 체인의 CORS 설정을 쓴다. MVC 쪽에만 두면
 * preflight 가 401 로 막힌다 (sp-docs/security.md §1.1).
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final CurrentMemberArgumentResolver currentMemberArgumentResolver;

	private final FixedWindowRateLimiter checkNicknameRateLimiter;

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this.currentMemberArgumentResolver);
	}

	/**
	 * 요청 수 제한 — sp-docs/security.md §10 표의 check-nickname 줄,
	 * {@code GET /api/v1/members/check-nickname} 의 IP 키.
	 *
	 * <p><b>인터셉터로 거는 이유</b> — Security 필터 체인(CORS 처리 포함)을 지난 뒤, 핸들러 앞이다.
	 * 제한이 CORS 처리보다 앞에서 응답을 끝내면 브라우저는 429 를 보지 못하고 CORS 실패를 본다 (§10).
	 * 그 밖의 경로는 제한하지 않는다.
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(HttpMethod.GET, this.checkNicknameRateLimiter))
				.addPathPatterns("/api/v1/members/check-nickname");
	}

}
