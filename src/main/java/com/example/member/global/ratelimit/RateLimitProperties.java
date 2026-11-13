package com.example.member.global.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 요청 수 제한의 규칙 설정 (sp-docs/tech-stack.md §4.6). 대상·키·한도의 정본은
 * sp-docs/security.md §10 의 표이고, 그 값을 {@code application.yml} 에 적는다.
 *
 * <p>한도·창·버킷 수는 정본이 숫자를 정한 <b>정본 값</b>이라 정책값의 「기본값 없음」 을 요구받지
 * 않는다 (§4.6). 그래도 <b>빠졌거나 양수가 아니면 기동이 실패한다</b> — 잘못된 값이 기동 뒤 요청마다 429·500 이나 틀린 Retry-After 로 드러나지 않게 기동 시점에 막는다
 * 가드이고, 근거는 계획의 AGENTS.md 불변식 9 다 (sp-docs/plan/phase2-auth-member.md 의
 * M-15·AU-19). 빠짐은 {@link #limiter} 가, 값은 {@link FixedWindowRateLimiter} 의 생성자가 막는다.
 *
 * @param bucketsPerRule 규칙마다 둘 카운터 배열의 크기
 * @param rules 규칙 이름 → 한도·창
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(Integer bucketsPerRule, Map<String, Rule> rules) {

	/**
	 * @param limit 한 창에 한 키가 통과할 수 있는 요청 수
	 * @param window 창의 길이. 창은 시계의 경계에 맞춘다
	 */
	public record Rule(Integer limit, Duration window) {
	}

	/**
	 * 이름의 규칙으로 제한기를 만든다. 규칙이나 그 값이 설정에 없으면 예외를 던져 빈 생성을
	 * 실패시킨다.
	 */
	public FixedWindowRateLimiter limiter(String name, Clock clock) {
		if (this.bucketsPerRule == null) {
			throw new IllegalStateException("rate-limit.buckets-per-rule 이 설정에 없다 (sp-docs/tech-stack.md §4.6)");
		}
		Rule rule = (this.rules != null) ? this.rules.get(name) : null;
		if (rule == null) {
			throw new IllegalStateException("rate-limit.rules." + name + " 이 설정에 없다 (sp-docs/tech-stack.md §4.6)");
		}
		if (rule.limit() == null || rule.window() == null) {
			throw new IllegalStateException(
					"rate-limit.rules." + name + " 에 limit 과 window 가 모두 있어야 한다 (sp-docs/tech-stack.md §4.6)");
		}
		return new FixedWindowRateLimiter(name, rule.limit(), rule.window(), this.bucketsPerRule, clock);
	}

}
