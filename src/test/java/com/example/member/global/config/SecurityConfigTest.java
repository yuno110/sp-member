package com.example.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.member.global.security.ClaimContractValidator;
import com.example.member.global.security.LoginMember;
import com.example.member.global.security.RoleClaimConverter;
import com.example.member.global.security.SecurityErrorResponder;
import com.example.member.support.TestTokenFactory;
import com.example.securityprobe.CurrentMemberProbeController;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * sp-docs/plan/phase1.md §5 M-04 의 "검증" 표를 테스트로 옮긴 것이다.
 *
 * <p>업무 경로의 정본은 sp-docs/api-contract.md §3, 인프라 경로는 sp-docs/security.md §5.1.1,
 * 인증 실패 코드의 경계는 sp-docs/api-contract.md §8.5 다.
 *
 * <p><b>업무 컨트롤러는 아직 없다</b>(M-05~M-10). 그래서 무인증 경로의 기대값을 "200"이 아니라
 * <b>"401 이 아님"</b>으로 둔다 — 인가를 통과하면 그 뒤는 매핑 없음(404)이 된다. 컨트롤러가
 * 생겨도 이 단언은 그대로 유효하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

	/** application-test.yml 이 주는 테스트 전용 오리진. */
	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	private static final long ACCOUNT_ID = 7L;

	@DynamicPropertySource
	static void jwtPublicKey(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CorsConfigurationSource corsConfigurationSource;

	@Autowired
	private SecurityErrorResponder securityErrorResponder;

	// --- 업무 경로 (sp-docs/api-contract.md §3) ---

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({
			"POST,/api/v1/members",
			"GET,/api/v1/members/me",
			"PATCH,/api/v1/members/me",
			"DELETE,/api/v1/members/me"
	})
	@DisplayName("인증이 필요한 경로는 토큰 없이 401 A001 이다")
	void 인증이_필요한_경로(String method, String path) throws Exception {
		this.mockMvc.perform(request(HttpMethod.valueOf(method), path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("A001"))
				.andExpect(jsonPath("$.error.message").value("인증이 필요합니다."));
	}

	@ParameterizedTest(name = "GET {0}")
	@ValueSource(strings = {"/api/v1/members/check-nickname", "/api/v1/members/1"})
	@DisplayName("무인증 경로는 토큰 없이도 401 이 아니다")
	void 무인증_경로(String path) throws Exception {
		this.mockMvc.perform(get(path)).andExpect(notUnauthorized());
	}

	/**
	 * 선언 순서 결함은 두 경로를 <b>함께</b> 봐야 드러난다. {@code /members/{accountId}}(무인증)를
	 * 먼저 선언하면 {@code me} 가 경로 변수로 먹혀 <b>내 프로필이 무인증으로 열린다.</b>
	 */
	@Test
	@DisplayName("/members/me 가 /members/{accountId} 보다 먼저 선언되어 있다")
	void 경로_선언_순서() throws Exception {
		this.mockMvc.perform(get("/api/v1/members/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));

		this.mockMvc.perform(get("/api/v1/members/1"))
				.andExpect(notUnauthorized());
	}

	@Test
	@DisplayName("선언되지 않은 경로는 인증을 요구한다")
	void 선언되지_않은_경로는_막힌다() throws Exception {
		this.mockMvc.perform(get("/api/v1/somewhere-new"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"));
	}

	/**
	 * 내부 API 는 외부 인증 체인에서 분리되어 있다 (sp-docs/security.md §5.1.1).
	 *
	 * <p>분리됐다는 것은 <b>토큰을 보지 않는다</b>는 뜻이다. 같은 체인에 {@code permitAll} 로
	 * 두면 요청에 실린 토큰이 그대로 검증되어 잘못된 토큰이 {@code A002} 를 만든다 — 그래서
	 * 무토큰만 보지 않고 쓰레기 토큰도 함께 본다.
	 */
	@Test
	@DisplayName("/internal/** 은 외부 인증 체인에서 분리되어 있다")
	void 내부_API_는_분리되어_있다() throws Exception {
		this.mockMvc.perform(get("/internal/v1/members/bulk"))
				.andExpect(notUnauthorized());

		// 이 값은 b64token 문법을 지키므로 디코더까지 간다. 토큰을 보는 체인이라면 여기서
		// 401 A002 가 나온다 — 401 이 아니라는 것이 토큰을 보지 않았다는 뜻이다
		this.mockMvc.perform(get("/internal/v1/members/bulk")
						.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
				.andExpect(notUnauthorized());
	}

	// --- 인프라 경로 (sp-docs/security.md §5.1.1) ---

	@Test
	@DisplayName("토큰 없이 /swagger-ui.html 은 302 로 /swagger-ui/index.html 로 간다")
	void swagger_진입점은_인증_없이_열린다() throws Exception {
		this.mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection())
				// /login 으로 가면 폼 로그인이 살아 있거나 permitAll 이 빠진 것이다
				.andExpect(redirectedUrl("/swagger-ui/index.html"));
	}

	@Test
	@DisplayName("토큰 없이 /swagger-ui/index.html 이 200 이다")
	void swagger_ui_는_인증_없이_열린다() throws Exception {
		this.mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("토큰 없이 /v3/api-docs 가 200 과 OpenAPI 문서를 돌려준다")
	void api_docs_는_인증_없이_열린다() throws Exception {
		this.mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").exists())
				.andExpect(jsonPath("$.info.title").value("member-service API"));
	}

	@Test
	@DisplayName("/actuator/health 는 인증 없이 열린다")
	void actuator_health_는_열린다() throws Exception {
		this.mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"/actuator", "/actuator/beans", "/actuator/env", "/actuator/metrics",
			"/actuator/configprops", "/actuator/mappings"})
	@DisplayName("health 외의 actuator 경로는 노출되지 않는다")
	void actuator_는_health_만_노출한다(String path) throws Exception {
		MvcResult result = this.mockMvc.perform(get(path)).andReturn();

		assertThat(result.getResponse().getStatus())
				.as("%s 가 열려 있으면 빈 목록·환경변수·매핑이 그대로 새어 나간다", path)
				.isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value(),
						HttpStatus.NOT_FOUND.value());
	}

	@Test
	@DisplayName("유효한 토큰으로도 health 외의 actuator 경로는 403 A004 다")
	void actuator_는_인증해도_막힌다() throws Exception {
		this.mockMvc.perform(get("/actuator/beans").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("A004"))
				.andExpect(jsonPath("$.error.message").value("권한이 없습니다."));
	}

	// --- 토큰 검증 (sp-docs/api-contract.md §6 §8.5) ---

	/**
	 * <b>기대값을 리터럴로 적는다.</b> 구현의 상수를 기대값 자리에 쓰면 그 상수를 자기 자신과
	 * 견주는 것이 되어 어떤 값이어도 통과한다. 발급은 auth 하나이고 검증은 member·board 둘이므로
	 * (sp-docs/api-contract.md §6) 이 두 값이 어긋나면 auth 가 발급한 토큰이 전부 거부된다.
	 */
	@Test
	@DisplayName("iss 와 role claim 이름이 Claim 계약의 값이다")
	void claim_계약값() {
		assertThat(ClaimContractValidator.ISSUER)
				.as("발급자 식별자다 (sp-docs/api-contract.md §6)")
				.isEqualTo("auth-service");
		assertThat(RoleClaimConverter.CLAIM_ROLE)
				.as("권한 claim 의 이름이다 (sp-docs/api-contract.md §6)")
				.isEqualTo("role");
	}

	/**
	 * {@code role} 열거 값의 출처를 고정한다. <b>기대값은 위와 같은 이유로 리터럴이다.</b>
	 *
	 * <p>이 집합이 member 안에서 열거를 적는 자리이고 {@code ClaimContractValidator} 가 그것만
	 * 쓴다 (sp-docs/plan/phase1.md §5 M-12). 값이 §6 에서 어긋나면 auth 가 발급한 토큰이
	 * 거부되거나 계약 밖 토큰이 통과한다.
	 */
	@Test
	@DisplayName("role 의 허용값 출처가 Claim 계약의 열거다")
	void role_허용값_출처() {
		assertThat(ClaimContractValidator.CONTRACT_ROLES)
				.as("§6 이 role 을 USER | ADMIN 으로 고정한다")
				.containsExactlyInAnyOrder("USER", "ADMIN");
	}

	@Test
	@DisplayName("유효한 토큰이 통과하고 LoginMember 가 sub·role 로 채워진다")
	void 유효한_토큰으로_인증된다() throws Exception {
		this.mockMvc.perform(get("/test/current-member").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.nickname").doesNotExist());
	}

	@Test
	@DisplayName("LoginMember 의 필드는 accountId·role 이고 nickname 이 없다")
	void login_member_필드_목록() {
		List<String> fields = Arrays.stream(LoginMember.class.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();

		assertThat(fields)
				.as("nickname 은 JWT Claim 에 없다 (sp-docs/api-contract.md §6)")
				.containsExactly("accountId", "role");
	}

	@Test
	@DisplayName("role claim 이 GrantedAuthority 로 변환된다")
	void role_claim_이_권한이_된다() throws Exception {
		this.mockMvc.perform(get("/test/authorities").header(HttpHeaders.AUTHORIZATION, bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0]").value("ROLE_ADMIN"));
	}

	@Test
	@DisplayName("Authorization 헤더가 없으면 401 A001 이다")
	void 헤더가_없으면_A001() throws Exception {
		assertAuthFailure(null, "A001", "인증이 필요합니다.");
	}

	/**
	 * RFC 6750 §2.1 의 {@code b64token} 문법을 위반한 값은 애초에 bearer 자격증명이 아니다
	 * (sp-docs/api-contract.md §8.5). Spring 의 {@code DefaultBearerTokenResolver} 가 디코더
	 * 앞에서 끊으므로 {@code A002} 가 아니라 {@code A001} 이다.
	 */
	@Test
	@DisplayName("b64token 문법을 위반한 값은 401 A001 이다")
	void 문법_위반은_A001() throws Exception {
		assertAuthFailure("Bearer not!a!token", "A001", "인증이 필요합니다.");
	}

	@Test
	@DisplayName("문법은 맞지만 JWT 가 아닌 값은 401 A002 다")
	void JWT_가_아니면_A002() throws Exception {
		assertAuthFailure("Bearer notajwt", "A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("payload 가 변조된 토큰은 401 A002 다")
	void 변조된_토큰은_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tamperedToken(ACCOUNT_ID, "USER"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("만료된 토큰은 401 A003 이다")
	void 만료된_토큰은_A003() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.expiredToken(ACCOUNT_ID, "USER"),
				"A003", "만료된 토큰입니다.");
	}

	/** {@code iss} 가 달라도 사유를 가르지 않는다 — {@code A002} 다 (§8.5). */
	@Test
	@DisplayName("iss 가 계약값과 다른 토큰은 401 A002 다")
	void iss_불일치는_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tokenIssuedBy("other-issuer", ACCOUNT_ID, "USER"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	/**
	 * {@code sub}·{@code role} 이 계약을 어긴 토큰도 {@code A002} 다 (§8.5 "그 밖의 claim 검증
	 * 실패"). 이 둘이 막혀 있다는 것이 {@code LoginMember} 와 권한 변환이 {@code null} 을 만나지
	 * 않는 근거다 — 두 곳이 그 전제에 의존한다.
	 */
	@Test
	@DisplayName("sub 가 account.id 가 아닌 토큰은 401 A002 다")
	void sub_가_숫자가_아니면_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tokenWithSubject("not-a-number", "USER"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("role claim 이 없는 토큰은 401 A002 다")
	void role_이_없으면_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tokenWithoutRole(ACCOUNT_ID),
				"A002", "유효하지 않은 토큰입니다.");
	}

	@Test
	@DisplayName("role claim 이 공백인 토큰은 401 A002 다")
	void role_이_공백이면_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.tokenWithBlankRole(ACCOUNT_ID),
				"A002", "유효하지 않은 토큰입니다.");
	}

	/**
	 * 열거 밖 {@code role} 은 서명이 유효해도 <b>계약 위반 토큰</b>이다 — §6 이 소비자에게 값
	 * 검증을 요구한다 (sp-docs/plan/phase1.md §5 M-12).
	 */
	@Test
	@DisplayName("role 이 열거 밖인 토큰은 401 A002 다")
	void role_이_열거_밖이면_A002() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, "BOGUS"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	/**
	 * <b>거부 사유를 응답으로 구별하지 않는다</b> (sp-docs/api-contract.md §6, §8.5 의 "그 밖의
	 * claim 검증 실패"). 상태·코드·메시지가 같은 것으로는 모자라므로 본문을 <b>바이트로</b>
	 * 견준다 — 그 본문에는 시각이 없다 (§7 의 공통 형식).
	 */
	@Test
	@DisplayName("누락·공백·열거 밖 role 의 응답이 바이트 동일이다")
	void role_거부_사유를_응답으로_구별하지_않는다() throws Exception {
		MockHttpServletResponse missing =
				authFailureResponse("Bearer " + TestTokenFactory.tokenWithoutRole(ACCOUNT_ID));
		MockHttpServletResponse blank =
				authFailureResponse("Bearer " + TestTokenFactory.tokenWithBlankRole(ACCOUNT_ID));
		MockHttpServletResponse outOfEnum =
				authFailureResponse("Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, "BOGUS"));

		assertThat(missing.getContentAsByteArray())
				.as("빈 본문끼리는 어떤 바이트 비교도 통과한다 — 기준 응답이 비면 아래 세 단언이 공허해지므로 "
						+ "본문이 있는지 먼저 본다")
				.isNotEmpty();
		assertThat(outOfEnum.getContentAsByteArray())
				.as("열거 밖 role 이 누락과 다른 응답을 내면 토큰의 어느 claim 이 왜 틀렸는지가 드러난다 "
						+ "(sp-docs/security.md §4.1)")
				.isEqualTo(missing.getContentAsByteArray());
		assertThat(blank.getContentAsByteArray())
				.as("공백도 같다 (sp-docs/api-contract.md §6)")
				.isEqualTo(missing.getContentAsByteArray());
		assertThat(List.of(outOfEnum.getContentType(), blank.getContentType()))
				.as("Content-Type 도 갈리지 않는다")
				.containsOnly(missing.getContentType());
	}

	/**
	 * 대소문자를 관용하면 검증 측이 발급 측 계약보다 넓은 값을 받아들여, auth 가 거부하는 토큰을
	 * member 가 통과시킨다 (sp-docs/tech-stack.md §2.1 이 그 갈림을 기록한다).
	 */
	@Test
	@DisplayName("role 이 소문자인 토큰은 401 A002 다 — 대소문자를 관용하지 않는다")
	void role_의_대소문자를_관용하지_않는다() throws Exception {
		assertAuthFailure("Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, "user"),
				"A002", "유효하지 않은 토큰입니다.");
	}

	@ParameterizedTest(name = "role = {0}")
	@ValueSource(strings = {"USER", "ADMIN"})
	@DisplayName("계약의 열거 값인 role 은 그대로 통과한다")
	void 계약의_열거_값은_통과한다(String role) throws Exception {
		this.mockMvc.perform(get("/test/current-member")
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, role)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
				.andExpect(jsonPath("$.role").value(role));
	}

	// --- STATELESS (sp-docs/security.md §1) ---

	@Test
	@DisplayName("세션이 생성되지 않고 세션 쿠키도 내려가지 않는다")
	void 세션이_생성되지_않는다() throws Exception {
		assertNoSession(this.mockMvc.perform(get("/api/v1/members/me")).andReturn());
		assertNoSession(this.mockMvc.perform(get("/test/current-member")
				.header(HttpHeaders.AUTHORIZATION, bearer())).andReturn());
	}

	// --- CORS (sp-docs/security.md §1.1) ---

	@Test
	@DisplayName("CORS 의 메서드·헤더·allowCredentials 가 정본 값이고 * 가 없다")
	void cors_설정값이_정본과_일치한다() {
		assertThat(this.corsConfigurationSource).isInstanceOf(UrlBasedCorsConfigurationSource.class);
		CorsConfiguration configuration =
				((UrlBasedCorsConfigurationSource) this.corsConfigurationSource)
						.getCorsConfigurations().get("/**");

		assertThat(configuration).isNotNull();
		assertThat(configuration.getAllowedOrigins()).isNotEmpty().doesNotContain("*");
		assertThat(configuration.getAllowedOriginPatterns()).isNullOrEmpty();
		assertThat(configuration.getAllowedMethods())
				.containsExactlyInAnyOrder("GET", "POST", "PATCH", "DELETE");
		assertThat(configuration.getAllowedHeaders())
				.containsExactlyInAnyOrder("Authorization", "Content-Type");
		assertThat(configuration.getAllowCredentials())
				.as("토큰을 Authorization 헤더로 보내므로 쿠키를 허용할 이유가 없다")
				.isFalse();
	}

	@Test
	@DisplayName("허용 오리진이 비었거나 * 가 섞이면 기동이 실패한다")
	void cors_가드가_기동을_실패시킨다() {
		SecurityConfig securityConfig = new SecurityConfig(this.securityErrorResponder);

		assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of(ALLOWED_ORIGIN, "*")))
				.as("값이 환경에서 오므로 * 금지를 지키는 지점은 여기뿐이다")
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of()))
				.as("둘을 함께 본다 — 하나만 보면 다른 쪽이 열린다")
				.isInstanceOf(IllegalStateException.class);
	}

	/** CORS 가 MVC 가 아니라 Security 체인에 있어야 preflight 가 인가 필터에 막히지 않는다. */
	@Test
	@DisplayName("화이트리스트의 오리진은 preflight 를 통과하고 그 밖의 오리진은 막힌다")
	void cors_preflight() throws Exception {
		this.mockMvc.perform(options("/api/v1/members")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

		this.mockMvc.perform(options("/api/v1/members")
						.header(HttpHeaders.ORIGIN, "http://not-allowed.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden());
	}

	private String bearer() {
		return "Bearer " + TestTokenFactory.accessToken(ACCOUNT_ID, "ADMIN");
	}

	/** 거부된 응답 자체를 돌려준다. 본문을 바이트로 견주려면 ResultMatcher 로는 모자란다. */
	private MockHttpServletResponse authFailureResponse(String authorization) throws Exception {
		return this.mockMvc.perform(get("/test/current-member").header(HttpHeaders.AUTHORIZATION, authorization))
				.andExpect(status().isUnauthorized())
				.andReturn()
				.getResponse();
	}

	private void assertAuthFailure(String authorization, String code, String message) throws Exception {
		var requestBuilder = get("/test/current-member");
		if (authorization != null) {
			requestBuilder = requestBuilder.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		this.mockMvc.perform(requestBuilder)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value(code))
				.andExpect(jsonPath("$.error.message").value(message));
	}

	private void assertNoSession(MvcResult result) {
		assertThat(result.getRequest().getSession(false))
				.as("SessionCreationPolicy.STATELESS 인데 세션이 생겼다")
				.isNull();
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
				.noneSatisfy(value -> assertThat(value).contains("JSESSIONID"));
	}

	/** 컨트롤러가 없는 경로는 404 가 되므로 "200"이 아니라 "401 이 아님"으로 단언한다. */
	private static ResultMatcher notUnauthorized() {
		return result -> assertThat(result.getResponse().getStatus())
				.as("인가 규칙이 빠지면 Boot 기본 동작으로 401 이 된다 (sp-docs/security.md §5.1.1)")
				.isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
	}

	/** 프로브 컨트롤러를 이 컨텍스트에만 등록한다 ({@link CurrentMemberProbeController} 주석 참조). */
	@TestConfiguration
	static class ProbeControllerConfig {

		@Bean
		CurrentMemberProbeController currentMemberProbeController() {
			return new CurrentMemberProbeController();
		}

	}

}
