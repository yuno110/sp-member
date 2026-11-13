package com.example.member.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.member.global.config.ClockConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 규칙 설정과 기동 가드 (sp-docs/plan/phase2-auth-member.md M-15 — 「기동 가드가 AU-19 의 것과
 * 같다」, 검증 표의 「규칙 설정이 빠졌다 → 기동 실패」).
 *
 * <p>설정 파일을 읽지 않는 컨텍스트에 규칙만 넣어 띄운다. 운영 기동과 같은 빈({@link RateLimitConfig})
 * 이 실패하는지를 본다.
 */
class RateLimitConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(RateLimitConfig.class, ClockConfig.class);

	@Test
	@DisplayName("규칙이 다 있으면 제한기가 뜬다")
	void 규칙이_있으면_뜬다() {
		this.runner.withPropertyValues(
						"rate-limit.buckets-per-rule=16384",
						"rate-limit.rules.check-nickname.limit=20",
						"rate-limit.rules.check-nickname.window=PT1M")
				.run(context -> assertThat(context).hasNotFailed()
						.hasSingleBean(FixedWindowRateLimiter.class));
	}

	/**
	 * 규칙이 빠졌거나 값이 양수가 아니면 <b>기동이 실패한다</b> — 잘못된 값이 기동 뒤 요청마다 429·500 이나 틀린 Retry-After 로 드러나지 않게 기동 시점에 막는다
	 * (AGENTS.md 불변식 9). 실패 메시지가 빠진 키를 가리킨다.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("잘못된_설정")
	@DisplayName("규칙 설정이 빠졌거나 양수가 아니면 기동이 실패하고 그 키를 가리킨다")
	void 규칙_설정이_잘못되면_기동이_실패한다(String 경우, String 가리킬_키, String[] properties) {
		this.runner.withPropertyValues(properties)
				.run(context -> assertThat(context).hasFailed()
						.getFailure().rootCause().hasMessageContaining(가리킬_키));
	}

	static Stream<Arguments> 잘못된_설정() {
		return Stream.of(
				Arguments.of("설정이 하나도 없다", "rate-limit.buckets-per-rule", new String[0]),
				Arguments.of("버킷 수가 없다", "rate-limit.buckets-per-rule", new String[] {
						"rate-limit.rules.check-nickname.limit=20",
						"rate-limit.rules.check-nickname.window=PT1M"}),
				Arguments.of("check-nickname 규칙이 없다", "rate-limit.rules.check-nickname", new String[] {
						"rate-limit.buckets-per-rule=16384",
						"rate-limit.rules.other.limit=20",
						"rate-limit.rules.other.window=PT1M"}),
				Arguments.of("한도가 없다", "rate-limit.rules.check-nickname", new String[] {
						"rate-limit.buckets-per-rule=16384",
						"rate-limit.rules.check-nickname.window=PT1M"}),
				Arguments.of("창이 없다", "rate-limit.rules.check-nickname", new String[] {
						"rate-limit.buckets-per-rule=16384",
						"rate-limit.rules.check-nickname.limit=20"}),
				Arguments.of("한도가 0", "rate-limit.rules.check-nickname.limit", new String[] {
						"rate-limit.buckets-per-rule=16384",
						"rate-limit.rules.check-nickname.limit=0",
						"rate-limit.rules.check-nickname.window=PT1M"}),
				Arguments.of("창이 0", "rate-limit.rules.check-nickname.window", new String[] {
						"rate-limit.buckets-per-rule=16384",
						"rate-limit.rules.check-nickname.limit=20",
						"rate-limit.rules.check-nickname.window=PT0S"}),
				Arguments.of("버킷 수가 0", "rate-limit.buckets-per-rule", new String[] {
						"rate-limit.buckets-per-rule=0",
						"rate-limit.rules.check-nickname.limit=20",
						"rate-limit.rules.check-nickname.window=PT1M"}));
	}

	/**
	 * {@code application.yml} 의 규칙이 sp-docs/security.md §10 표의 check-nickname 줄이다 — 분당
	 * 20 이고 버킷 수는 16,384 다 (sp-docs/tech-stack.md §4.6). 테스트 프로파일이 한도를 덮으므로
	 * 컨텍스트에서 읽지 않고 {@code application.yml} 을 직접 읽는다.
	 * {@code server.forward-headers-strategy} 는 {@code none} 이다 — 컨테이너가
	 * {@code X-Forwarded-For} 로 {@code remoteAddr} 를 바꾸지 않는다.
	 */
	@Test
	@DisplayName("application.yml 의 규칙이 정본 표의 check-nickname 줄이고 전달 헤더를 쓰지 않는다")
	void 운영_설정이_정본_값이다() throws IOException {
		List<PropertySource<?>> sources =
				new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
		Binder binder = new Binder(ConfigurationPropertySources.from(sources));

		RateLimitProperties properties = binder.bind("rate-limit", RateLimitProperties.class).get();

		assertThat(properties.bucketsPerRule()).isEqualTo(16384);
		assertThat(properties.rules()).containsOnlyKeys("check-nickname");
		assertThat(properties.rules().get("check-nickname"))
				.isEqualTo(new RateLimitProperties.Rule(20, Duration.ofMinutes(1)));
		assertThat(binder.bind("server.forward-headers-strategy", String.class).get()).isEqualTo("none");
	}

}
