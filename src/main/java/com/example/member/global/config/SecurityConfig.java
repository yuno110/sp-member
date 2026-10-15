package com.example.member.global.config;

import com.example.member.global.security.InternalApiKeyFilter;
import com.example.member.global.security.RoleClaimConverter;
import com.example.member.global.security.SecurityErrorResponder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 경로별 인가. 업무 API 는 sp-docs/api-contract.md §3, 인프라 경로는 sp-docs/security.md §5.1.1
 * 이 정본이다.
 *
 * <p><b>이 서비스는 토큰을 검증만 한다.</b> 개인키도 서명 기능도 없다 — 발급 주체는 auth 하나다
 * (sp-docs/security.md §2, sp-docs/adr/0012). 공개키 검증기({@code JwtDecoder})는
 * {@code spring.security.oauth2.resourceserver.jwt.public-key-location} 으로 스타터가
 * 자동구성하므로 여기서 만들지 않는다 (sp-docs/tech-stack.md §2.1).
 *
 * <p><b>선언하지 않은 경로는 401 이 된다.</b> {@code spring-boot-starter-security} 가 있으면 전
 * 경로에 인증이 요구되므로, 공개 경로를 빠뜨리면 조용히 막힌다 (sp-docs/security.md §5.1.1).
 * 그래서 {@code anyRequest()} 를 {@code authenticated()} 로 둔다 — 새 경로가 생겼을 때 열린
 * 상태가 아니라 막힌 상태가 기본값이 되도록.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final SecurityErrorResponder securityErrorResponder;

	/**
	 * 내부 API 는 <b>외부 인증 체인에서 분리한다</b> (sp-docs/security.md §5.1.1).
	 *
	 * <p>별도 체인이므로 {@code /internal/**} 요청은 Bearer 토큰 필터를 지나지 않는다. 같은
	 * 체인에 두고 {@code permitAll} 만 주면 요청에 실린 토큰이 그대로 검증되어, 키 거부가
	 * {@code A002} 로 나가거나 호출 측의 낡은 토큰이 내부 호출을 실패시킨다 — 정본이 금지한
	 * "키 거부를 업무 오류로 보이게 하는" 상황이다 (§6).
	 *
	 * <p><b>{@code X-Internal-Api-Key} 검증 필터를 M-10 이 이 체인에 등록했다</b>
	 * ({@link InternalApiKeyFilter}, sp-docs/security.md §6). 체인 구조는 그대로다 — 인가는
	 * {@code permitAll} 이고 접근 통제는 그 필터가 한다.
	 *
	 * <p><b>필터를 빈으로 두지 않고 여기서 만든다.</b> {@code Filter} 타입 빈은 Boot 가 서블릿
	 * 컨테이너에 자동 등록하므로 {@code /api/v1/**} 까지 지나게 된다.
	 *
	 * <p>{@code AuthorizationFilter} 앞에 끼운다 — 인가 단계 전에 끊어 컨트롤러에 닿지 않게
	 * 한다.
	 *
	 * @param internalApiKey 비밀 값이므로 <b>기본값이 없다</b> (sp-docs/tech-stack.md §4.3).
	 *         환경변수가 없으면 {@code @Value} 가 placeholder 를 해석하지 못해 이 빈 생성이
	 *         실패하고 기동이 멈춘다. 빈 값으로 설정된 경우는 {@link InternalApiKeyFilter} 의
	 *         생성자 가드가 막는다
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain internalApiFilterChain(
			HttpSecurity http,
			@Value("${member-service.internal-api-key}") String internalApiKey) throws Exception {

		http
				.securityMatcher("/internal/**")
				// Stateless REST API 이므로 CSRF 토큰을 쓰지 않는다 (sp-docs/security.md §1)
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(RequestCacheConfigurer::disable)
				// CORS 를 붙이지 않는다. 내부 API 는 브라우저가 호출하지 않는다 (§6)
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.addFilterBefore(
						new InternalApiKeyFilter(internalApiKey, this.securityErrorResponder),
						AuthorizationFilter.class);

		return http.build();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			CorsConfigurationSource corsConfigurationSource,
			JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

		http
				// Stateless REST API 이므로 CSRF 토큰을 쓰지 않는다 (sp-docs/security.md §1)
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				// 토큰 인증만 쓴다. 폼 로그인을 남겨두면 인가 실패가 /login 리다이렉트로 보인다
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				// 로그인·로그아웃은 auth-service 의 경로다 (sp-docs/api-contract.md §2.1).
				// Security 기본 /logout 엔드포인트를 열지 않는다
				.logout(AbstractHttpConfigurer::disable)
				// 정본이 이 값을 이름으로 지정한다 (sp-docs/security.md §1). 이 선언을 빼도 Boot
				// 기본값(IF_REQUIRED)에서 bearer 인증은 세션을 만들지 않으므로 테스트는 통과한다
				// — 그 동작에 기대지 않고 정책을 선언으로 고정한다
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// 인가 실패 시 원 요청을 세션에 저장하지 않는다. 저장하면 STATELESS 인데도
				// 세션이 생긴다
				.requestCache(RequestCacheConfigurer::disable)

				.authorizeHttpRequests(auth -> auth
						// --- 인프라 경로 (sp-docs/security.md §5.1.1) ---
						// prod 프로파일에서는 springdoc 자체가 꺼져 경로가 사라진다. 그 확인은
						// M-11 이 한다 (sp-docs/security.md §5.1.1)
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						// health 외의 actuator 는 노출 자체를 막는다
						.requestMatchers("/actuator/**").denyAll()

						// --- 프로필 (sp-docs/api-contract.md §3) ---
						// ★ /members/me 를 /members/{accountId} 보다 먼저 선언한다.
						// {accountId} 는 한 세그먼트를 받으므로 me 도 함께 먹는다 — 먼저 선언된
						// 규칙이 이기므로 순서를 바꾸면 내 프로필이 무인증으로 열린다
						.requestMatchers("/api/v1/members/me").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/v1/members").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/v1/members/check-nickname").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/members/{accountId}").permitAll()

						.anyRequest().authenticated())

				// auth 가 발급한 토큰을 공개키로 검증한다. JwtDecoder 는 스타터가 자동구성하고
				// (sp-docs/tech-stack.md §2.1) Claim 계약 검증기는 컨텍스트의
				// OAuth2TokenValidator<Jwt> 빈으로 붙는다 (ClaimContractValidator)
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(this.securityErrorResponder)
						.accessDeniedHandler(this.securityErrorResponder));

		return http.build();
	}

	/**
	 * {@code role} claim 을 권한으로 바꿔 {@code Authentication} 에 싣는다
	 * (sp-docs/security.md §5.1). 변환 규칙은 {@link RoleClaimConverter} 에 있다.
	 */
	@Bean
	public JwtAuthenticationConverter jwtAuthenticationConverter(RoleClaimConverter roleClaimConverter) {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(roleClaimConverter);
		return converter;
	}

	/**
	 * CORS 화이트리스트. <b>{@code *} 를 쓰지 않는다</b> (sp-docs/security.md §1 §1.1).
	 *
	 * <p><b>허용 오리진은 환경이 정한다</b> (§1.1). 그래서 값을 코드나 커밋되는 설정에 박지 않고
	 * {@code cors.allowed-origins} 로 주입받는다. <b>기본값을 두지 않으므로 값이 없으면 기동이
	 * 실패한다</b> — 정책값에 임의 기본값을 두면 그 값이 정해진 정책인지 임시값인지 구분할 수
	 * 없고 운영에 그대로 나간다 (sp-docs/tech-stack.md §4.3).
	 *
	 * <p>비었거나 {@code *} 가 섞여 들어오면 기동을 실패시킨다. <b>값이 환경에서 오므로 §1.1 의
	 * 금지를 지키는 지점은 이 가드뿐이다</b> — "설정에 {@code *} 가 없다"는 그 절반이다.
	 *
	 * <p>허용 메서드·헤더·{@code allowCredentials} 는 §1.1 이 정본으로 고정한 값이다.
	 *
	 * <p><b>CORS 는 {@code WebConfig} 가 아니라 여기에 둔다.</b> preflight(OPTIONS)는 인가
	 * 필터보다 앞에서 처리돼야 한다 — MVC 쪽에만 두면 preflight 가 401 로 막힌다 (§1.1).
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource(
			@Value("${cors.allowed-origins}") List<String> allowedOrigins) {

		if (allowedOrigins.isEmpty()) {
			throw new IllegalStateException(
					"cors.allowed-origins 가 비어 있다. 허용 오리진을 외부에서 주입한다 "
							+ "(sp-docs/security.md §1.1).");
		}
		allowedOrigins.stream()
				.filter(origin -> origin.contains("*"))
				.findFirst()
				.ifPresent(origin -> {
					throw new IllegalStateException(
							"CORS 허용 오리진에 와일드카드를 쓸 수 없다: " + origin
									+ " (sp-docs/security.md §1.1)");
				});

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		// api-contract.md §3 이 쓰는 메서드. OPTIONS 는 preflight 처리에 쓰여 목록에 넣지 않는다
		configuration.setAllowedMethods(List.of(
				HttpMethod.GET.name(), HttpMethod.POST.name(),
				HttpMethod.PATCH.name(), HttpMethod.DELETE.name()));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		// 토큰을 Authorization 헤더로 보내므로 쿠키가 필요 없다 (sp-docs/security.md §1.1)
		configuration.setAllowCredentials(false);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
