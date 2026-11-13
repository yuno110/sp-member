package com.example.member.global.ratelimit;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 규칙마다 제한기를 만든다. 대상은 sp-docs/security.md §10 표의 check-nickname 줄이다 —
 * {@code GET /api/v1/members/check-nickname}, 키는 IP. 요청에 거는 것은 {@code WebConfig} 다.
 *
 * <p>제한기를 빈으로 두므로 규칙의 설정이 빠졌거나 값이 양수가 아니면 <b>기동이 실패한다</b>
 * ({@link RateLimitProperties}).
 *
 * <p><b>이 제한은 부하 방어다. 개인정보 보호가 아니다</b> (§10.1) — 닉네임은
 * {@code GET /api/v1/members/{accountId}} 가 무인증으로 주고 {@code accountId} 는 순차 정수라, 닉네임
 * 전체를 그 경로로 차례로 읽을 수 있다. 중복 확인의 제한이 그 사실을 바꾸지 않는다.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

	/** 닉네임 중복 확인의 규칙 이름 ({@code rate-limit.rules.check-nickname}). */
	static final String CHECK_NICKNAME = "check-nickname";

	@Bean
	public FixedWindowRateLimiter checkNicknameRateLimiter(RateLimitProperties properties, Clock clock) {
		return properties.limiter(CHECK_NICKNAME, clock);
	}

}
