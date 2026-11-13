package com.example.member.global.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 규칙 하나의 요청 수 제한기. 방식은 sp-docs/security.md §10 의 「항목 / 정한 것」 표다.
 *
 * <ul>
 *   <li><b>고정 창</b> — 창은 시계의 경계에 맞춘다(창이 1분이면 분, 1시간이면 시). 시각은 주입한
 *       {@link Clock} 이고, 경계는 그 시계의 시간대로 센다</li>
 *   <li><b>버킷 카운터</b> — 키마다 칸을 두지 않고 고정 크기 배열의 한 칸을 센다. 칸은
 *       <b>기동마다 새로 만든 무작위 비밀</b>을 키로 한 HMAC-SHA256 으로 고른다. 비밀은 이 객체의
 *       메모리에만 있고 설정·로그에 남기지 않는다. 키가 몇 개든 메모리가 같다</li>
 *   <li><b>창 경계에서 배열을 비운다.</b> 저장하지 않으므로 재기동하면 카운터가 사라진다</li>
 * </ul>
 *
 * <p><b>대가</b> — 같은 버킷에 떨어진 서로 다른 키는 한도를 나눈다. 드문 거짓 양성이고 버킷 수가
 * 클수록 드물다 (§10, §10.1).
 *
 * <p>제한기는 auth·member 가 각자 쓴다. 공유 모듈을 두지 않는다 — 같아야 하는 것은 동작이다
 * (sp-docs/conventions.md §7.2).
 */
public final class FixedWindowRateLimiter {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	/** HMAC-SHA256 의 출력 길이와 같게 둔다. */
	private static final int SECRET_BYTES = 32;

	private final String rule;

	private final int limit;

	private final long windowMillis;

	private final Clock clock;

	/** 아래 필드는 이 객체의 모니터로 지킨다. {@link Mac} 은 스레드 안전하지 않다. */
	private final Mac mac;

	private final int[] counters;

	private long currentWindow = Long.MIN_VALUE;

	/**
	 * 값이 양수가 아니면 만들지 않는다 — 빈 생성이 실패해 기동이 멈춘다. 잘못된 값이 기동 뒤 요청마다 429·500 이나 틀린 Retry-After 로 드러나지 않게 기동 시점에 막는다
	 * (sp-docs/plan/phase2-auth-member.md M-15·AU-19 의 기동 가드, AGENTS.md 불변식 9).
	 */
	public FixedWindowRateLimiter(String rule, int limit, Duration window, int buckets, Clock clock) {
		if (limit <= 0) {
			throw new IllegalStateException("rate-limit.rules." + rule + ".limit 은 양수여야 한다: " + limit);
		}
		if (window == null || window.toMillis() <= 0) {
			throw new IllegalStateException("rate-limit.rules." + rule + ".window 는 양수여야 한다: " + window);
		}
		if (buckets <= 0) {
			throw new IllegalStateException("rate-limit.buckets-per-rule 은 양수여야 한다: " + buckets);
		}
		this.rule = rule;
		this.limit = limit;
		this.windowMillis = window.toMillis();
		this.clock = clock;
		this.mac = newMacWithFreshSecret();
		this.counters = new int[buckets];
	}

	/**
	 * 이 키로 요청 하나를 센다.
	 *
	 * @throws RateLimitExceededException 이 키의 버킷이 이번 창의 한도를 채웠다. 넘은 요청은
	 *         카운터를 더 올리지 않는다 — 이미 한도라 판정이 같다
	 */
	public void acquire(String key) {
		synchronized (this) {
			// 시각도 잠금 안에서 읽는다. 밖에서 읽으면 경계에서 늦게 읽은 스레드가 먼저 들어와
			// 새 창으로 비운 뒤, 일찍 읽은 스레드가 옛 창 번호로 다시 비울 수 있다
			long now = localEpochMillis();
			long window = Math.floorDiv(now, this.windowMillis);
			if (window != this.currentWindow) {
				Arrays.fill(this.counters, 0);
				this.currentWindow = window;
			}
			int bucket = bucketOf(key);
			if (this.counters[bucket] >= this.limit) {
				throw new RateLimitExceededException(this.rule, retryAfterSeconds(now, window));
			}
			this.counters[bucket]++;
		}
	}

	/**
	 * 창이 끝날 때까지 남은 초. 올림이고 최소 1 이다 (sp-docs/security.md §10 「넘었을 때」).
	 * 창의 시작 시각이면 창 전체가 남으므로 0 이 되지 않지만 최소값을 선언으로 둔다.
	 */
	private long retryAfterSeconds(long now, long window) {
		long remainingMillis = (window + 1) * this.windowMillis - now;
		long seconds = (remainingMillis + 999) / 1000;
		return Math.max(1, seconds);
	}

	/**
	 * 시계의 시간대로 본 epoch 밀리초. 창의 번호를 이 값으로 매겨야 창이 그 시간대의 분·시
	 * 경계에 맞는다 — UTC 의 epoch 로 매기면 시간대의 오프셋이 시 단위가 아닌 곳에서 시간 창이
	 * 정시에 맞지 않는다.
	 */
	private long localEpochMillis() {
		Instant instant = this.clock.instant();
		long offsetMillis = this.clock.getZone().getRules().getOffset(instant).getTotalSeconds() * 1000L;
		return instant.toEpochMilli() + offsetMillis;
	}

	private int bucketOf(String key) {
		byte[] digest = this.mac.doFinal(key.getBytes(StandardCharsets.UTF_8));
		int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
				| ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
		return Math.floorMod(value, this.counters.length);
	}

	private static Mac newMacWithFreshSecret() {
		byte[] secret = new byte[SECRET_BYTES];
		new SecureRandom().nextBytes(secret);
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
			return mac;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("요청 수 제한기의 HMAC 을 만들 수 없다", e);
		}
		finally {
			Arrays.fill(secret, (byte) 0);
		}
	}

}
