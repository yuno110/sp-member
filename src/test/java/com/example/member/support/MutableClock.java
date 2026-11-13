package com.example.member.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 테스트가 바꿔 끼우는 앱 시계. 고정하지 않으면 시스템 시계를 따른다 — auth 의 같은 이름의 클래스와
 * 같은 쓰임이다.
 *
 * <p>요청 수 제한의 창 경계를 벽시계를 기다리지 않고 넘기기 위해 쓴다
 * (sp-docs/plan/phase2-auth-member.md M-15 의 검증 — "움직이는 시계"). {@code Thread.sleep} 으로
 * 우회하지 않는다.
 */
public final class MutableClock extends Clock {

	private final ZoneId zone = ZoneId.systemDefault();

	private volatile Instant fixed;

	/** 이 시각에 멈춘다. {@link #reset()} 전까지 그대로다. */
	public void set(LocalDateTime now) {
		this.fixed = now.atZone(this.zone).toInstant();
	}

	/** 시스템 시계로 돌아간다. */
	public void reset() {
		this.fixed = null;
	}

	@Override
	public ZoneId getZone() {
		return this.zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("테스트 시계는 시간대를 바꾸지 않는다");
	}

	@Override
	public Instant instant() {
		Instant now = this.fixed;
		return (now != null) ? now : Instant.now();
	}

}
