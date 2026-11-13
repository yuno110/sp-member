package com.example.member.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.member.support.MutableClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 제한기 하나의 동작 — sp-docs/security.md §10 의 「항목 / 정한 것」 표와
 * sp-docs/plan/phase2-auth-member.md M-15(AU-19 와 같은 기준)의 검증 표다. 시계는
 * {@link MutableClock} 을 직접 넘기고 창 경계는 시계를 움직여 넘는다.
 */
class FixedWindowRateLimiterTest {

	private static final String KEY = "203.0.113.7";

	/** 운영 설정과 같은 버킷 수다 (sp-docs/tech-stack.md §4.6). */
	private static final int BUCKETS = 16384;

	private static final LocalDateTime DAY = LocalDateTime.of(2030, 1, 1, 0, 0);

	private final MutableClock clock = new MutableClock();

	private FixedWindowRateLimiter 제한기(int limit, Duration window, int buckets) {
		return new FixedWindowRateLimiter("check-nickname", limit, window, buckets, this.clock);
	}

	private void 시각(String time) {
		this.clock.set(DAY.with(LocalTime.parse(time)));
	}

	@Test
	@DisplayName("한도까지 통과하고 다음 요청은 규칙 이름과 Retry-After 를 실은 예외다")
	void 한도까지_통과하고_다음은_거절한다() {
		FixedWindowRateLimiter limiter = 제한기(3, Duration.ofMinutes(1), BUCKETS);
		시각("10:00:15");

		for (int i = 0; i < 3; i++) {
			limiter.acquire(KEY);
		}
		RateLimitExceededException exceeded =
				catchThrowableOfType(RateLimitExceededException.class, () -> limiter.acquire(KEY));

		assertThat(exceeded).as("한도를 넘은 요청은 거절한다").isNotNull();
		assertThat(exceeded.getRule()).isEqualTo("check-nickname");
		assertThat(exceeded.getRetryAfterSeconds()).isEqualTo(45);
	}

	/**
	 * {@code Retry-After} 는 창이 끝날 때까지 남은 초의 <b>올림</b>이고 최소 1 이다 (§10). 내림으로
	 * 세면 {@code 29.8} 초 전이 30 이 아니라 29 가 되고, 끝나기 직전에는 0 이 된다 — 0 은 "바로 다시
	 * 하라" 가 되어 아직 닫힌 창에 다시 부딪힌다.
	 */
	@ParameterizedTest(name = "{0} 이면 {1}초")
	@CsvSource({
			"10:00:00, 60",
			"10:00:00.001, 60",
			"10:00:29.800, 31",
			"10:00:30.200, 30",
			"10:00:59, 1",
			"10:00:59.999, 1"
	})
	@DisplayName("Retry-After 는 창 끝까지 남은 초의 올림이고 최소 1 이다")
	void Retry_After_는_남은_초의_올림이다(String time, long expected) {
		FixedWindowRateLimiter limiter = 제한기(1, Duration.ofMinutes(1), BUCKETS);
		시각(time);
		limiter.acquire(KEY);

		RateLimitExceededException exceeded =
				catchThrowableOfType(RateLimitExceededException.class, () -> limiter.acquire(KEY));

		assertThat(exceeded.getRetryAfterSeconds()).isEqualTo(expected);
	}

	/**
	 * 창은 <b>시계의 경계</b>에 맞춘다 (§10). 처음 요청한 때부터 창을 재는 구현은 10:00:59 에 채운
	 * 한도가 10:01:59 까지 이어지므로 10:01:00 의 통과가 그것을 가른다.
	 */
	@Test
	@DisplayName("분 창은 시계의 분 경계를 넘으면 다시 통과한다")
	void 분_경계를_넘으면_다시_통과한다() {
		FixedWindowRateLimiter limiter = 제한기(2, Duration.ofMinutes(1), BUCKETS);
		시각("10:00:59");
		limiter.acquire(KEY);
		limiter.acquire(KEY);
		assertThatThrownBy(() -> limiter.acquire(KEY)).isInstanceOf(RateLimitExceededException.class);

		시각("10:01:00");
		limiter.acquire(KEY);
		limiter.acquire(KEY);

		assertThatThrownBy(() -> limiter.acquire(KEY))
				.as("새 창에서도 한도는 같다")
				.isInstanceOf(RateLimitExceededException.class);
	}

	@Test
	@DisplayName("시간 창은 시계의 정시를 넘으면 다시 통과한다")
	void 정시를_넘으면_다시_통과한다() {
		FixedWindowRateLimiter limiter = 제한기(1, Duration.ofHours(1), BUCKETS);
		시각("10:59:59");
		limiter.acquire(KEY);
		RateLimitExceededException exceeded =
				catchThrowableOfType(RateLimitExceededException.class, () -> limiter.acquire(KEY));
		assertThat(exceeded.getRetryAfterSeconds()).as("창은 정시에 끝난다").isEqualTo(1);

		시각("11:00:00");

		limiter.acquire(KEY);
	}

	/**
	 * 버킷 하나면 모든 키가 한 칸을 나눈다 — 키마다 칸을 두지 않고 버킷으로 센다는 것이 이것으로
	 * 드러난다 (§10 「카운터」).
	 */
	@Test
	@DisplayName("버킷 수 1 의 제한기에서는 한 키가 한도를 채우면 다른 키도 거절된다")
	void 버킷이_하나면_다른_키도_막힌다() {
		FixedWindowRateLimiter limiter = 제한기(2, Duration.ofMinutes(1), 1);
		시각("10:00:15");
		limiter.acquire(KEY);
		limiter.acquire(KEY);

		assertThatThrownBy(() -> limiter.acquire("198.51.100.9"))
				.isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * 버킷이 많으면 다른 키는 따로 센다. 버킷은 기동마다 새로 만든 비밀의 HMAC 으로 골라 어느 두 키가
	 * 한 칸에 떨어질지 알 수 없다 — 그래서 한 키만 보지 않고 다른 키 100 개 중 90 개 이상이
	 * 통과하는지 본다. 키 101 개가 16,384 칸에 떨어질 때 겹침의 기댓값은 약 0.3 이라 열 개 넘게
	 * 막히는 일은 사실상 없다. 키와 무관하게 칸을 고르거나 칸이 수십 개뿐인 구현은 열 개 넘게 막는다.
	 */
	@Test
	@DisplayName("버킷이 많으면 한 키가 한도를 채워도 다른 키는 통과한다")
	void 다른_키는_따로_센다() {
		FixedWindowRateLimiter limiter = 제한기(1, Duration.ofMinutes(1), BUCKETS);
		시각("10:00:15");
		limiter.acquire(KEY);

		long passed = IntStream.rangeClosed(1, 100)
				.filter(i -> 통과한다(limiter, "198.51.100." + i))
				.count();

		assertThat(passed).isGreaterThanOrEqualTo(90);
	}

	private static boolean 통과한다(FixedWindowRateLimiter limiter, String key) {
		try {
			limiter.acquire(key);
			return true;
		}
		catch (RateLimitExceededException e) {
			return false;
		}
	}

	/**
	 * <b>기동 가드</b> — 한도·창·버킷 수가 양수가 아니면 제한기를 만들지 않는다. 제한기는 빈이라 빈
	 * 생성이 실패해 기동이 멈춘다 (sp-docs/plan/phase2-auth-member.md AU-19 의 기동 가드, AGENTS.md
	 * 불변식 9). 창은 밀리초로 세므로 1밀리초보다 짧으면 0 과 같다.
	 */
	@ParameterizedTest(name = "limit={0}, window={1}, buckets={2}")
	@CsvSource({
			"0, PT1M, 16384",
			"-1, PT1M, 16384",
			"20, PT0S, 16384",
			"20, -PT1M, 16384",
			"20, PT0.0000001S, 16384",
			"20, PT1M, 0",
			"20, PT1M, -1"
	})
	@DisplayName("한도·창·버킷 수가 양수가 아니면 만들지 않는다")
	void 양수가_아니면_만들지_않는다(int limit, Duration window, int buckets) {
		assertThatThrownBy(() -> 제한기(limit, window, buckets))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("양수");
	}

}
