package com.example.member.global.ratelimit;

/**
 * 요청 수 제한을 넘었다 — 429 {@code C009} 와 {@code Retry-After} 가 된다 (sp-docs/security.md
 * §10, sp-docs/api-contract.md §8.1 의 {@code C009} 문단).
 *
 * <p>{@code BusinessException} 이 아니다. 응답에 헤더가 하나 더 붙으므로 일반 경로로 보내지 않고
 * {@code GlobalExceptionHandler} 가 따로 다룬다 (sp-docs/conventions.md §6.1).
 */
public class RateLimitExceededException extends RuntimeException {

	private final String rule;

	private final long retryAfterSeconds;

	public RateLimitExceededException(String rule, long retryAfterSeconds) {
		super("rate limit exceeded: " + rule);
		this.rule = rule;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public String getRule() {
		return this.rule;
	}

	/** 창이 끝날 때까지 남은 초. 올림, 최소 1 (§10). */
	public long getRetryAfterSeconds() {
		return this.retryAfterSeconds;
	}

}
