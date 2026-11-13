package com.example.member.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 앱 시계 (sp-docs/conventions.md §1.2).
 *
 * <p>닉네임 중복 확인의 요청 수 제한이 창의 경계와 {@code Retry-After} 를 이 시계로 정한다
 * (sp-docs/security.md §10 — "시각은 주입한 {@code Clock} 이다"). 빈으로 두는 이유는 테스트가
 * 벽시계를 기다리지 않고 시계를 바꿔 끼워 창 경계를 넘기기 위해서다.
 *
 * <p>시간대는 실행 환경이 정한다 (sp-docs/adr/0011). 그래서 시스템 기본 시간대의 시계를 쓴다.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}

}
