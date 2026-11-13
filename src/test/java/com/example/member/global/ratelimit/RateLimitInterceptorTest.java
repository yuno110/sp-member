package com.example.member.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.member.support.MutableClock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * IP 키를 세는 인터셉터 — sp-docs/security.md §10 의 「키의 주소」·「세는 요청」 과
 * sp-docs/plan/phase2-auth-member.md M-15(AU-19 와 같은 기준)의 검증 표다.
 *
 * <p>요청 하나를 직접 만들어 넘긴다. 서블릿 컨테이너가 {@code remoteAddr} 를 정하는 방식(Tomcat 의
 * 주소 처리)은 여기서 재현되지 않는다 — 실서버의 동작은 통합 측정이 본다(계획 §5.1).
 */
class RateLimitInterceptorTest {

	private static final String CHECK_PATH = "/api/v1/members/check-nickname";

	private final MutableClock clock = new MutableClock();

	@BeforeEach
	void 시계를_멈춘다() {
		this.clock.set(LocalDateTime.of(2030, 1, 1, 10, 0, 15));
	}

	private RateLimitInterceptor 인터셉터(int limit) {
		return new RateLimitInterceptor(HttpMethod.GET,
				new FixedWindowRateLimiter("check-nickname", limit, Duration.ofMinutes(1), 16384, this.clock));
	}

	private static MockHttpServletRequest 요청(String method, String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, CHECK_PATH);
		request.setRemoteAddr(remoteAddr);
		return request;
	}

	private static boolean 보낸다(RateLimitInterceptor interceptor, MockHttpServletRequest request) {
		return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
	}

	/**
	 * {@code X-Forwarded-For}·{@code X-Real-IP} 는 클라이언트가 임의로 쓰는 헤더다. 그것을 키로 믿으면
	 * 요청마다 값을 바꿔 제한을 비켜 간다 (§10 「키의 주소」).
	 */
	@Test
	@DisplayName("요청마다 X-Forwarded-For·X-Real-IP 를 바꿔도 같은 키로 센다")
	void 전달_헤더를_읽지_않는다() {
		RateLimitInterceptor interceptor = 인터셉터(3);

		for (int i = 1; i <= 3; i++) {
			MockHttpServletRequest request = 요청("GET", "203.0.113.7");
			request.addHeader("X-Forwarded-For", "198.51.100." + i);
			request.addHeader("X-Real-IP", "198.51.100." + i);
			assertThat(보낸다(interceptor, request)).isTrue();
		}
		MockHttpServletRequest fourth = 요청("GET", "203.0.113.7");
		fourth.addHeader("X-Forwarded-For", "198.51.100.4");
		fourth.addHeader("X-Real-IP", "198.51.100.4");

		assertThatThrownBy(() -> 보낸다(interceptor, fourth)).isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * preflight 를 세면 브라우저가 실제 요청 앞에 보내는 {@code OPTIONS} 가 한도를 먹는다 (§10
	 * 「세는 요청」). 규칙은 메서드로도 고른다.
	 */
	@Test
	@DisplayName("preflight(OPTIONS)는 세지 않는다")
	void preflight_는_세지_않는다() {
		RateLimitInterceptor interceptor = 인터셉터(1);

		for (int i = 0; i < 5; i++) {
			assertThat(보낸다(interceptor, 요청("OPTIONS", "203.0.113.7"))).isTrue();
		}
		assertThat(보낸다(interceptor, 요청("GET", "203.0.113.7"))).isTrue();

		assertThatThrownBy(() -> 보낸다(interceptor, 요청("GET", "203.0.113.7")))
				.as("세는 요청은 여전히 센다")
				.isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * Spring MVC 는 HEAD 를 GET 핸들러로 보낸다 — HEAD 도 그 경로에 닿은 요청이라 GET 규칙의 카운터로
	 * 센다 (§10 「세는 요청」). 한도 1 이므로 첫 요청이 채운 칸에 둘째 요청이 막혀야 한다 — HEAD 끼리
	 * 막히면 HEAD 가 세어진 것이고, HEAD 와 GET 이 서로를 막으면 한 카운터를 나눈 것이다.
	 */
	@ParameterizedTest(name = "{0} 다음 {1}")
	@CsvSource({
			"HEAD, HEAD",
			"HEAD, GET",
			"GET, HEAD"
	})
	@DisplayName("GET 규칙은 HEAD 도 같은 카운터로 센다")
	void HEAD_는_GET_과_같은_카운터로_센다(String first, String second) {
		RateLimitInterceptor interceptor = 인터셉터(1);
		assertThat(보낸다(interceptor, 요청(first, "203.0.113.7"))).isTrue();

		assertThatThrownBy(() -> 보낸다(interceptor, 요청(second, "203.0.113.7")))
				.isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * 두 주소가 한 키면 한도를 나눈다. IPv6 는 앞 64비트로 묶고 IPv4 사상 주소는 IPv4 로 본다 (§10
	 * 「키의 주소」). 한도 1 이므로 첫 주소가 채운 칸에 둘째 주소가 막혀야 한다.
	 */
	@ParameterizedTest(name = "{0} 다음 {1}")
	@CsvSource({
			"2001:db8:1:2:aaaa::1, 2001:db8:1:2:bbbb:cccc:dddd:2",
			"2001:db8:1:2::1, 2001:0db8:0001:0002:ffff:ffff:ffff:ffff",
			"203.0.113.7, ::ffff:203.0.113.7",
			"::ffff:203.0.113.7, 203.0.113.7"
	})
	@DisplayName("같은 IPv6 /64 의 두 주소, IPv4 사상 주소와 그 IPv4 는 한 키로 센다")
	void 한_키로_세는_주소(String first, String second) {
		RateLimitInterceptor interceptor = 인터셉터(1);
		assertThat(보낸다(interceptor, 요청("GET", first))).isTrue();

		assertThatThrownBy(() -> 보낸다(interceptor, 요청("GET", second)))
				.isInstanceOf(RateLimitExceededException.class);
	}

	@Test
	@DisplayName("IPv4 는 주소 그대로, IPv6 는 /64 가 키다")
	void IP_키의_모양() {
		assertThat(RateLimitInterceptor.ipKey("203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(RateLimitInterceptor.ipKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(RateLimitInterceptor.ipKey("2001:db8:1:2:aaaa::1"))
				.isEqualTo(RateLimitInterceptor.ipKey("2001:db8:1:2::ffff"))
				.isNotEqualTo(RateLimitInterceptor.ipKey("2001:db8:1:3::1"));
		assertThat(RateLimitInterceptor.ipKey("203.0.113.7"))
				.as("다른 IPv4 는 다른 키다")
				.isNotEqualTo(RateLimitInterceptor.ipKey("203.0.113.8"));
	}

	/**
	 * 서블릿 컨테이너는 {@code remoteAddr} 에 IP 를 준다. 그렇지 않은 값이 오면 키를 지어내지 않고
	 * 드러낸다 — 이름을 그대로 넘기면 DNS 조회가 일어난다.
	 */
	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {"localhost", "example.com", ""})
	@DisplayName("IP 리터럴이 아닌 주소는 키로 쓰지 않고 예외다")
	void IP_가_아닌_주소(String remoteAddr) {
		assertThatThrownBy(() -> RateLimitInterceptor.ipKey(remoteAddr))
				.isInstanceOf(IllegalStateException.class);
	}

}
