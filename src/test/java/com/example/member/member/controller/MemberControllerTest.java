package com.example.member.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.member.global.ratelimit.RateLimitProperties;
import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import com.example.member.support.MutableClock;
import com.example.member.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * sp-docs/plan/phase1.md §5 M-05 · M-08 의 "검증" 표 중 HTTP 쪽을 테스트로 옮긴 것이다. 정본은
 * sp-docs/api-contract.md §2.3 §3 §3.1 §8.2 §9.1, 검증 규칙은
 * sp-docs/requirements/member.md §2 §5 §7 이다.
 *
 * <p>인가 필터를 포함한 전 구간을 지난다 — {@code POST /api/v1/members} 와
 * {@code /api/v1/members/me} 는 인증이고 {@code GET /members/check-nickname} 은 무인증이다
 * (§3 의 "인증" 열).
 *
 * <p><b>경로별 인가 행렬은 {@code SecurityConfigTest} 가 본다.</b> 여기서 토큰 없는 접근을 보는
 * 것은 응답 본문과 "저장되지 않았다"·"바뀌지 않았다" 까지 함께 단언하는 경우뿐이다.
 *
 * <p>픽스처의 {@code accountId} 는 M-05 가 211~, M-08 이 251~, <b>M-09 가 281~288</b>, M-16 이
 * 311~ 을 쓰고, 닉네임은
 * {@code 관리자} 가 아닌 값을 쓴다. ADMIN seed 가 {@code (1, '관리자')} 를 넣으므로 겹치면 UNIQUE
 * 위반으로 깨진다 (sp-docs/requirements/member.md §10.1). M-08 의 {@code MemberServiceTest}
 * 픽스처(241~)와도 범위를 겹치지 않게 둔다.
 *
 * <p><b>앱 시계를 {@link MutableClock} 으로 바꾼다</b> (M-15). 요청 수 제한의 테스트는 시계를
 * 멈추고 요청 주소를 {@code 203.0.113.0/24}(문서용 대역)에서 테스트마다 따로 쓴다. 시계를 멈추지
 * 않은 테스트에서는 시스템 시계다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberControllerTest {

	private static final String PATH = "/api/v1/members";

	private static final String CHECK_PATH = PATH + "/check-nickname";

	private static final String ME_PATH = PATH + "/me";

	/** {@code V2__seed_admin_profile.sql} 의 프로필 (sp-docs/requirements/member.md §10.1). */
	private static final Long SEED_ACCOUNT_ID = 1L;

	private static final String SEED_NICKNAME = "관리자";

	/**
	 * 프로필 일괄 조회의 호출자로 쓰는 <b>seed 가 아닌 계정</b>이다({@link #일괄_조회}). 이 계정의
	 * 프로필은 등록하지 않는다.
	 *
	 * <p><b>ADMIN 판정은 이 계정과 seed 계정에 각각 {@code ADMIN}·{@code USER} 토큰을 실은 네 조합으로
	 * 본다</b> — 두 계정 모두 {@code ADMIN} 이면 200 이고 {@code USER} 면 403 {@code A004} 다. 같은
	 * {@code role} 인데 계정에 따라 결과가 갈리는 구현은 네 조합 중 하나에서 빨개진다. 조합마다
	 * 빨개지는 모양은 이렇다(M-16 이 변이로 쟀다).
	 *
	 * <ul>
	 * <li>이 계정의 {@code ADMIN}(200 을 기대하는 일괄 조회 테스트들) — 「{@code role} 을 보지 않고
	 * seed 만 허용」, 「{@code ADMIN} 이고 seed 일 때만 허용」
	 * <li>seed 계정의 {@code USER}({@link #USER_토큰_일괄_조회}) — 「{@code role} 을 보지 않고 seed 만
	 * 허용」, 「{@code ADMIN} 이거나 seed 면 허용」
	 * <li>이 계정의 {@code USER}({@link #seed_가_아닌_계정의_USER_토큰_일괄_조회}) — 「{@code role} 을
	 * 보지 않고 seed 만 거부」
	 * <li>seed 계정의 {@code ADMIN}({@link #seed_계정의_ADMIN_토큰_일괄_조회}) — 「{@code role} 을 보지
	 * 않고 seed 만 거부」, 「{@code ADMIN} 이고 seed 가 아닐 때만 허용」
	 * </ul>
	 */
	private static final long NON_SEED_CALLER_ID = 399L;

	/** sp-docs/requirements/member.md §2 가 정한 닉네임 메시지다. */
	private static final String NICKNAME_MESSAGE = "닉네임은 2~10자여야 합니다.";

	/** application-test.yml 이 주는 테스트 전용 오리진. */
	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	/** 앱 시계를 바꿔 끼운다. 고정하지 않은 테스트에서는 시스템 시계다 ({@link MutableClock}). */
	private static final MutableClock CLOCK = new MutableClock();

	@DynamicPropertySource
	static void jwtPublicKey(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
	}

	/** 애플리케이션의 {@code Clock} 빈을 {@link #CLOCK} 로 바꾼다 ({@code ClockConfig}). */
	@TestBean
	private Clock clock;

	static Clock clock() {
		return CLOCK;
	}

	@Autowired
	private RateLimitProperties rateLimitProperties;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * <b>seed 행은 남긴다.</b> H2 는 테스트 클래스 사이에 공유되므로
	 * ({@code DB_CLOSE_DELAY=-1}, sp-docs/tech-stack.md §5.3) 여기서 지우면
	 * {@link com.example.member.member.AdminProfileSeedTest} 가 자기와 무관한 이유로 깨진다.
	 */
	@AfterEach
	void 정리() {
		CLOCK.reset();
		this.memberRepository.deleteAll(this.memberRepository.findAll().stream()
				.filter(profile -> !SEED_ACCOUNT_ID.equals(profile.getAccountId()))
				.toList());
	}

	// --- 프로필 등록 (sp-docs/api-contract.md §3 §9.1) ---

	@Test
	@DisplayName("정상 등록은 201 과 {accountId, nickname} 이고 DB 에 저장된다")
	void 정상_등록() throws Exception {
		this.mockMvc.perform(등록(211L, """
						{"nickname": "홍길동"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accountId").value(211))
				.andExpect(jsonPath("$.data.nickname").value("홍길동"))
				.andExpect(jsonPath("$.error").doesNotExist());

		Member saved = this.memberRepository.findByAccountId(211L).orElseThrow();
		assertThat(saved.getNickname()).isEqualTo("홍길동");
		assertThat(saved.isDeleted()).isFalse();
	}

	/**
	 * 완료 기준: <b>{@code POST /api/v1/members} 가 인증을 요구한다</b>
	 * (sp-docs/api-contract.md §3). 본문은 유효하므로 거부한 것은 인증 단계다 —
	 * {@code A001} 의 경계는 §8.5 다.
	 */
	@Test
	@DisplayName("토큰 없이 등록하면 401 A001 이고 아무것도 저장되지 않는다")
	void 토큰_없이_등록() throws Exception {
		this.mockMvc.perform(post(PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"nickname": "토큰없음"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("A001"))
				.andExpect(jsonPath("$.error.message").value("인증이 필요합니다."));

		assertThat(this.memberRepository.existsByNickname("토큰없음"))
				.as("인증 전에 끊겼으므로 본문의 닉네임이 선점되지 않는다")
				.isFalse();
	}

	/**
	 * 완료 기준: <b>{@code account_id} 가 JWT {@code sub} 에서 온다. 본문에 다른 값을 넣어도
	 * 무시된다</b> (sp-docs/api-contract.md §3, sp-docs/security.md §4).
	 *
	 * <p>본문의 값으로 저장하는 구현이면 타인의 프로필을 대신 만들 수 있다.
	 */
	@Test
	@DisplayName("본문에 다른 accountId 를 보내면 무시되고 JWT sub 로 저장된다")
	void 본문의_accountId_는_무시된다() throws Exception {
		this.mockMvc.perform(등록(212L, """
						{"nickname": "본문무시", "accountId": 999999}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.accountId").value(212));

		assertThat(this.memberRepository.findByAccountId(212L).orElseThrow().getNickname())
				.isEqualTo("본문무시");
		assertThat(this.memberRepository.findByAccountId(999999L))
				.as("본문의 식별자를 신뢰하면 이 행이 생긴다")
				.isEmpty();
	}

	@Test
	@DisplayName("같은 계정·같은 닉네임으로 재호출하면 200 과 같은 본문이다")
	void 같은_계정_같은_닉네임_재호출() throws Exception {
		String body = """
				{"nickname": "멱등한사람"}""";
		this.mockMvc.perform(등록(213L, body)).andExpect(status().isCreated());

		this.mockMvc.perform(등록(213L, body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accountId").value(213))
				.andExpect(jsonPath("$.data.nickname").value("멱등한사람"));
	}

	@Test
	@DisplayName("같은 계정·다른 닉네임으로 재호출하면 409 M006 이다")
	void 같은_계정_다른_닉네임_재호출() throws Exception {
		this.mockMvc.perform(등록(214L, """
						{"nickname": "처음닉네임"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(등록(214L, """
						{"nickname": "바꾼닉네임"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M006"))
				.andExpect(jsonPath("$.error.message").value("이미 프로필이 등록된 계정입니다."));
	}

	@Test
	@DisplayName("탈퇴한 프로필의 계정으로 재호출하면 409 M007 이다")
	void 탈퇴한_프로필의_계정으로_재호출() throws Exception {
		this.mockMvc.perform(등록(215L, """
						{"nickname": "탈퇴한사람"}""")).andExpect(status().isCreated());
		탈퇴시킨다(215L);

		this.mockMvc.perform(등록(215L, """
						{"nickname": "탈퇴한사람"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("M007"))
				.andExpect(jsonPath("$.error.message").value("탈퇴한 프로필입니다."));
	}

	@Test
	@DisplayName("타인이 쓰는 닉네임으로 등록하면 409 M003 이다")
	void 타인이_쓰는_닉네임() throws Exception {
		this.mockMvc.perform(등록(216L, """
						{"nickname": "선점한닉네임"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(등록(217L, """
						{"nickname": "선점한닉네임"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("M003"))
				.andExpect(jsonPath("$.error.message").value("이미 사용 중인 닉네임입니다."));
	}

	/**
	 * 닉네임은 2~10자다 (sp-docs/requirements/member.md §2). 경계 밖 두 값을 함께 본다 — 한쪽만
	 * 보면 다른 쪽이 열린다.
	 *
	 * <p>{@code fieldErrors} 는 {@code nickname} 1건이다 (sp-docs/api-contract.md §2.3).
	 * 제약을 둘 이상 겹쳐 걸면 같은 필드가 두 번 담긴다.
	 */
	@ParameterizedTest(name = "nickname=\"{0}\"")
	@ValueSource(strings = {"가", "가나다라마바사아자차카"})
	@DisplayName("닉네임이 1자·11자면 400 C001 이고 fieldErrors 에 nickname 1건이다")
	void 닉네임_길이_경계(String nickname) throws Exception {
		this.mockMvc.perform(등록(218L, "{\"nickname\": \"" + nickname + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.message").value("잘못된 입력값입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("nickname"))
				.andExpect(jsonPath("$.error.fieldErrors[0].message").value(NICKNAME_MESSAGE));

		assertThat(this.memberRepository.findByAccountId(218L)).isEmpty();
	}

	/**
	 * 규칙은 <b>2~10자, 한글/영문/숫자</b>다 (sp-docs/requirements/member.md §2). 거부만 보면
	 * 더 좁은 규칙도 통과하므로 — 한글만 받는 패턴은 아래 거부 테스트를 모두 통과한다 —
	 * 허용해야 하는 값도 함께 본다: 하한 경계 2자, 세 문자 종류를 섞은 값, 상한 경계 10자,
	 * 그리고 <b>호환 자모 — 자음과 모음을 따로</b>.
	 *
	 * <p><b>{@code ㅋㅋ} 와 {@code ㅠㅠ} 가 둘 다 통과해야 한다.</b> "한글" 은 완성형 음절과
	 * 호환 자모를 모두 포함하고, sp-docs/requirements/member.md §2.2 의 표는 그것을
	 * <b>세 범위</b>로 적는다 — 완성형 U+AC00–U+D7A3, 호환 자모 자음 U+3131–U+314E,
	 * 호환 자모 모음 U+314F–U+3163. 한 범위에 값이 없으면 패턴에서 그 범위를 지우는 변경이
	 * 그대로 통과한다. {@code ㅋㅋ} 는 §2.2 가 자음 행의 예로, {@code ㅠㅠ} 는 모음 행의 예로
	 * 든 값이다.
	 *
	 * <p>완성형만 받는 패턴이면 2자 입력에 "2~10자여야 합니다" 가 나가 거부 메시지가 거짓말을
	 * 한다 — §2.2 가 자모를 허용한 이유 가운데 하나가 그것이다. 모음 범위만 빠져도
	 * {@code ㅠㅠ} 에서 같은 거짓말이 되살아난다.
	 *
	 * <p>값을 <b>리터럴로 적는다</b> — 운영 상수를 가져다 쓰면 "유효한 닉네임" 의 정의가 구현과
	 * 함께 움직여 단언이 아무것도 지키지 않는다.
	 */
	@ParameterizedTest(name = "nickname=\"{1}\"")
	@CsvSource({"221,ab", "222,a1한글Zz", "223,가나다라마바사아자차", "225,ㅋㅋ", "227,ㅠㅠ"})
	@DisplayName("2~10자의 한글(완성형·호환 자모)·영문·숫자 닉네임은 201 로 등록된다")
	void 닉네임_규칙에_맞는_값(long accountId, String nickname) throws Exception {
		this.mockMvc.perform(등록(accountId, "{\"nickname\": \"" + nickname + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.nickname").value(nickname));

		assertThat(this.memberRepository.findByAccountId(accountId).orElseThrow().getNickname())
				.isEqualTo(nickname);
	}

	/**
	 * 한글·영문·숫자 밖의 문자는 거부한다 (sp-docs/requirements/member.md §2). <b>공백과
	 * 구두점은 받지 않는다</b> — 호환 자모를 허용해도 이 둘은 집합 밖이다 (§2.2).
	 */
	@ParameterizedTest(name = "nickname=\"{0}\"")
	@ValueSource(strings = {"닉 네임", "nick!"})
	@DisplayName("공백·구두점이 섞인 닉네임은 400 C001 이다")
	void 닉네임_문자_집합(String nickname) throws Exception {
		this.mockMvc.perform(등록(224L, "{\"nickname\": \"" + nickname + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("nickname"))
				.andExpect(jsonPath("$.error.fieldErrors[0].message").value(NICKNAME_MESSAGE));

		assertThat(this.memberRepository.findByAccountId(224L)).isEmpty();
	}

	/**
	 * <b>{@code nickname} 이 없는 본문은 {@code @NotNull} 이 잡는다.</b> {@code @Pattern} 은
	 * {@code null} 을 유효로 취급하므로 그 애노테이션이 없으면 이 요청이 통과한다.
	 *
	 * <p>통과하면 생기는 행이 <b>{@code nickname = NULL, deleted = false}</b> 인데, 그 표현은
	 * sp-docs/domain-model.md §3.1 이 <b>탈퇴</b>에만 배정한 것이다. 게다가
	 * {@code uk_member_nickname} 은 NULL 을 여러 개 허용하므로 그런 행이 반복해서 만들어진다 —
	 * 세 상태(미등록/활성/탈퇴)의 구분이 깨진다
	 * (sp-docs/requirements/member.md §3 규칙 6 · 규칙 8).
	 *
	 * <p>그래서 상태 코드만 보지 않고 <b>행이 생기지 않았음</b>까지 단언한다.
	 */
	@ParameterizedTest(name = "body={0}")
	@ValueSource(strings = {"{}", "{\"nickname\": null}"})
	@DisplayName("nickname 이 없거나 null 이면 400 C001 이고 fieldErrors 1건이며 행이 생기지 않는다")
	void 닉네임_누락(String body) throws Exception {
		this.mockMvc.perform(등록(226L, body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("nickname"))
				.andExpect(jsonPath("$.error.fieldErrors[0].message").value(NICKNAME_MESSAGE));

		assertThat(this.memberRepository.findByAccountId(226L))
				.as("@NotNull 이 없으면 nickname = NULL, deleted = false 인 행이 생긴다 — "
						+ "그 표현은 탈퇴의 것이다 (sp-docs/domain-model.md §3.1)")
				.isEmpty();
	}

	/**
	 * 완료 기준: <b>이메일·비밀번호를 받지도 저장하지도 않는다</b>
	 * (sp-docs/adr/0012-auth-as-separate-service.md).
	 *
	 * <p>Boot 의 기본 Jackson 설정이 모르는 필드를 무시하므로 요청은 성공한다. 그 뒤 저장된
	 * 행의 <b>모든 컬럼 값</b>을 읽어 보낸 값이 어디에도 없음을 확인한다 — 응답만 보면 컬럼이
	 * 늘어나 저장되는 변경을 놓친다.
	 */
	@Test
	@DisplayName("본문의 email·password 는 무시되고 저장되지 않으며 응답에도 없다")
	void 이메일_비밀번호는_받지도_저장하지도_않는다() throws Exception {
		String email = "user@example.com";
		String password = "Passw0rd!";

		String responseBody = this.mockMvc.perform(등록(219L, """
						{"nickname": "무시된다", "email": "%s", "password": "%s"}"""
						.formatted(email, password)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		Map<String, Object> data = JsonPath.read(responseBody, "$.data");
		assertThat(data)
				.as("응답 형태는 {accountId, nickname} 이다 (sp-docs/api-contract.md §3.1)")
				.containsOnlyKeys("accountId", "nickname");

		Map<String, Object> row =
				this.jdbcTemplate.queryForMap("select * from member where account_id = ?", 219L);
		assertThat(row.values()).map(String::valueOf)
				.as("member 는 프로필만 소유한다 — 어느 컬럼에도 계정 값이 들어가지 않는다")
				.doesNotContain(email, password);
		assertThat(row.get("nickname")).isEqualTo("무시된다");
	}

	// --- 닉네임 중복 확인 (sp-docs/api-contract.md §2.3) ---

	@Test
	@DisplayName("쓰이지 않는 닉네임은 200 과 available true 다")
	void 미사용_닉네임_중복_확인() throws Exception {
		this.mockMvc.perform(get(CHECK_PATH).param("nickname", "아무도안쓰는닉"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.available").value(true))
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	/**
	 * seed 가 {@code 관리자} 를 쥐고 있으므로 쓸 수 없다 (sp-docs/requirements/member.md §10.1).
	 * 이 닉네임은 테스트가 만든 것이 아니라 {@code V2__seed_admin_profile.sql} 이 넣은 것이다.
	 */
	@Test
	@DisplayName("seed 가 쓰는 닉네임은 available 이 false 다")
	void 사용중인_닉네임_중복_확인() throws Exception {
		this.mockMvc.perform(get(CHECK_PATH).param("nickname", SEED_NICKNAME))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.available").value(false));
	}

	/**
	 * 완료 기준: <b>탈퇴로 해방된 닉네임은 {@code available: true} 다</b>
	 * (sp-docs/api-contract.md §2.3). 탈퇴 전 {@code false} 를 먼저 보지 않으면 닉네임을 애초에
	 * 저장하지 않는 구현에서도 통과한다.
	 */
	@Test
	@DisplayName("탈퇴로 해방된 닉네임은 available 이 true 로 바뀐다")
	void 탈퇴로_해방된_닉네임() throws Exception {
		this.mockMvc.perform(등록(220L, """
						{"nickname": "해방되는닉네임"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(get(CHECK_PATH).param("nickname", "해방되는닉네임"))
				.andExpect(jsonPath("$.data.available").value(false));

		탈퇴시킨다(220L);

		this.mockMvc.perform(get(CHECK_PATH).param("nickname", "해방되는닉네임"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.available").value(true));
	}

	/**
	 * 파라미터 누락은 <b>{@code fieldErrors} 가 빈 배열</b>이다 — 바인딩 자체가 일어나지 않아
	 * 담을 필드가 없다 (sp-docs/api-contract.md §2.3).
	 */
	@Test
	@DisplayName("nickname 파라미터가 없으면 400 C001 이고 fieldErrors 가 빈 배열이다")
	void 파라미터_누락() throws Exception {
		this.mockMvc.perform(get(CHECK_PATH))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	/**
	 * 빈 값·길이 위반은 바인딩된 필드의 오류이므로 {@code fieldErrors} 1건이다 (§2.3).
	 *
	 * <p><b>형식 오류에 {@code available: true} 를 돌려주지 않는다</b> — 쓸 수 없는 값인데 쓸 수
	 * 있다고 답하면 클라이언트가 등록에서 400 을 받아 같은 판정을 두 번 하게 된다 (§2.3).
	 *
	 * <p><b>길이는 맞고 문자 집합만 틀린 입력을 함께 본다</b> — {@code 닉 네임}·{@code nick!}
	 * 이다. 길이 위반만 보면 이 파라미터의 제약이 길이뿐인 구현도 전부 통과하는데, 그 상태에서
	 * {@code GET /check-nickname?nickname=닉 네임} 은 {@code available: true} 이고 같은 값의
	 * {@code POST /members} 는 400 {@code C001} 이다 — §2.3 이 명시적으로 금지한 상태다.
	 * <b>등록과 중복 확인이 같은 규칙을 써야 한다</b> (sp-docs/requirements/member.md §2.2).
	 */
	@ParameterizedTest(name = "nickname=\"{0}\"")
	@ValueSource(strings = {"", "가", "가나다라마바사아자차카", "닉 네임", "nick!"})
	@DisplayName("nickname 이 빈 값·길이 위반·문자 집합 위반이면 400 C001 이고 available 을 돌려주지 않는다")
	void 파라미터_형식_오류(String nickname) throws Exception {
		this.mockMvc.perform(get(CHECK_PATH).param("nickname", nickname))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("nickname"))
				.andExpect(jsonPath("$.error.fieldErrors[0].message").value(NICKNAME_MESSAGE));
	}

	// --- 요청 수 제한 (M-15, sp-docs/security.md §10 표의 check-nickname 줄) ---
	//
	// 한도는 테스트 프로파일의 값이다(application-test.yml. 운영 값은 RateLimitConfigTest 가 본다).
	// 테스트마다 시계를 다른 창에 멈춘다 — 창이 바뀌면 제한기가 카운터를 비우므로 다른 테스트가 센
	// 요청이 섞이지 않는다. 멈춘 시계는 정리()가 되돌린다

	/**
	 * 넘은 요청은 429 {@code C009} 와 {@code Retry-After} 다 — 창이 끝날 때까지 남은 초의 올림이다
	 * (sp-docs/security.md §10, sp-docs/api-contract.md §8.1·§9.13). 10:00:15 의 분 창은 45초 남았다.
	 */
	@Test
	@DisplayName("check-nickname 을 한도까지 보내면 통과하고 다음은 429 C009 와 Retry-After 다")
	void 중복_확인_한도_초과() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 10, 0, 15));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(중복_확인("203.0.113.10")).andExpect(status().isOk());
		}

		this.mockMvc.perform(중복_확인("203.0.113.10"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("C009"))
				.andExpect(jsonPath("$.error.message").value("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	@Test
	@DisplayName("시계가 창 경계를 넘으면 다시 통과한다")
	void 창_경계를_넘으면_다시_통과한다() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 11, 0, 59));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(중복_확인("203.0.113.11")).andExpect(status().isOk());
		}
		this.mockMvc.perform(중복_확인("203.0.113.11"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));

		CLOCK.set(LocalDateTime.of(2030, 1, 1, 11, 1, 0));

		this.mockMvc.perform(중복_확인("203.0.113.11")).andExpect(status().isOk());
	}

	/**
	 * {@code X-Forwarded-For} 는 클라이언트가 임의로 쓰는 헤더다 — 키로 믿으면 요청마다 값을 바꿔
	 * 제한을 비켜 간다 (§10 「키의 주소」). MockMvc 는 Tomcat 의 주소 처리를 거치지 않으므로 여기서
	 * 보는 것은 제한기가 그 헤더를 읽지 않는다는 것이다. 컨테이너 쪽은
	 * {@code server.forward-headers-strategy: none} 이 막는다.
	 */
	@Test
	@DisplayName("요청마다 X-Forwarded-For 를 바꿔도 같은 키로 센다")
	void X_Forwarded_For_를_바꿔도_같은_키로_센다() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 12, 0, 15));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(중복_확인("203.0.113.12").header("X-Forwarded-For", 전달_주소(i)))
					.andExpect(status().isOk());
		}

		this.mockMvc.perform(중복_확인("203.0.113.12").header("X-Forwarded-For", 전달_주소(한도())))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * 그 경로에 닿은 요청은 성공·실패를 가르지 않고 센다 (§10 「세는 요청」). 파라미터 검증은 핸들러를
	 * 부를 때 일어나므로 400 이 난 요청도 이미 세어졌다.
	 */
	@Test
	@DisplayName("형식이 틀려 400 이 난 요청도 센다")
	void 검증에_실패한_요청도_센다() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 13, 0, 15));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(get(CHECK_PATH).param("nickname", "nick!").with(주소("203.0.113.13")))
					.andExpect(status().isBadRequest());
		}

		this.mockMvc.perform(중복_확인("203.0.113.13")).andExpect(status().isTooManyRequests());
	}

	/**
	 * preflight 를 세면 브라우저가 실제 요청 앞에 보내는 {@code OPTIONS} 가 한도를 먹는다 (§10
	 * 「세는 요청」). 한도만큼 preflight 를 보낸 뒤에도 한도만큼 통과하고, 그다음이 429 다.
	 */
	@Test
	@DisplayName("preflight 는 세지 않는다")
	void preflight_는_세지_않는다() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 14, 0, 15));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(options(CHECK_PATH)
							.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
							.with(주소("203.0.113.14")))
					.andExpect(status().isOk());
		}
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(중복_확인("203.0.113.14")).andExpect(status().isOk());
		}

		this.mockMvc.perform(중복_확인("203.0.113.14")).andExpect(status().isTooManyRequests());
	}

	/**
	 * Spring MVC 는 HEAD 를 GET 핸들러로 보낸다 — HEAD 도 그 경로에 닿은 요청이라 GET 과 같은 카운터로
	 * 센다 (§10 「세는 요청」). 인가 규칙이 무인증으로 여는 것은 GET 이라 HEAD 는 토큰을 실어야 핸들러에
	 * 닿는다 — 토큰은 인가만 지나게 하고 프로필을 만들지 않는다. HEAD 를 세지 않으면 토큰을 가진
	 * 클라이언트가 HEAD 로 한도를 비켜 간다. 한도만큼 HEAD 를 보낸 뒤의 GET 이 429 다.
	 */
	@Test
	@DisplayName("토큰을 실은 HEAD 도 GET 과 같은 카운터로 센다")
	void HEAD_도_GET_과_같은_카운터로_센다() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 17, 0, 15));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(head(CHECK_PATH).param("nickname", "아무도안쓰는닉")
							.header(HttpHeaders.AUTHORIZATION, bearer(301L))
							.with(주소("203.0.113.17")))
					.andExpect(status().isOk());
		}

		this.mockMvc.perform(중복_확인("203.0.113.17")).andExpect(status().isTooManyRequests());
	}

	/**
	 * 429 에도 CORS 헤더가 실려야 브라우저가 그 응답을 읽는다 — 없으면 브라우저는 429 가 아니라 CORS
	 * 실패를 본다. {@code Retry-After} 가 노출돼야 화면이 언제 다시 할지 말할 수 있다 (§10, §1.1).
	 */
	@Test
	@DisplayName("허용 오리진에서 온 요청의 429 에도 CORS 헤더가 실리고 Retry-After 가 노출된다")
	void 제한_응답에도_CORS_헤더가_실린다() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 15, 0, 15));
		for (int i = 0; i < 한도(); i++) {
			this.mockMvc.perform(중복_확인("203.0.113.15").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
					.andExpect(status().isOk());
		}

		this.mockMvc.perform(중복_확인("203.0.113.15").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
						containsString(HttpHeaders.RETRY_AFTER)))
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"));
	}

	/**
	 * 제한은 정본 표의 경로에만 건다 — 그 밖의 경로는 제한하지 않는다 (§10). 그 경로의 요청이
	 * {@code check-nickname} 의 칸을 먹지도 않는다.
	 */
	@Test
	@DisplayName("제한하지 않는 경로는 몇 번이든 429 가 아니고 check-nickname 의 한도를 먹지 않는다")
	void 제한하지_않는_경로() throws Exception {
		CLOCK.set(LocalDateTime.of(2030, 1, 1, 16, 0, 15));
		for (int i = 0; i <= 한도(); i++) {
			this.mockMvc.perform(get(PATH + "/" + SEED_ACCOUNT_ID).with(주소("203.0.113.16")))
					.andExpect(status().isOk());
		}

		this.mockMvc.perform(중복_확인("203.0.113.16")).andExpect(status().isOk());
	}

	private int 한도() {
		return this.rateLimitProperties.rules().get("check-nickname").limit();
	}

	private static MockHttpServletRequestBuilder 중복_확인(String remoteAddr) {
		return get(CHECK_PATH).param("nickname", "아무도안쓰는닉").with(주소(remoteAddr));
	}

	private static RequestPostProcessor 주소(String remoteAddr) {
		return request -> {
			request.setRemoteAddr(remoteAddr);
			return request;
		};
	}

	/** 요청마다 바뀌는 {@code X-Forwarded-For} 값 (198.51.100.0/24, 문서용 대역). */
	private static String 전달_주소(int i) {
		return "198.51.100." + (i % 250 + 1);
	}

	// --- 내 프로필 조회·수정 (sp-docs/api-contract.md §3.1) ---

	@Test
	@DisplayName("내 프로필 조회는 200 과 그 계정의 닉네임을 돌려준다")
	void 내_프로필_조회() throws Exception {
		this.mockMvc.perform(등록(251L, """
						{"nickname": "조회되는사람"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(내_프로필(251L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accountId").value(251))
				.andExpect(jsonPath("$.data.nickname").value("조회되는사람"))
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	/**
	 * 검증표: <b>응답 키 집합은 {@code accountId}·{@code nickname} 정확히 둘</b>이고
	 * {@code email}·{@code accessToken}·{@code createdAt} 이 없다 (sp-docs/api-contract.md §3.1).
	 *
	 * <p>조회와 수정을 함께 본다 — <b>두 응답의 모양이 같아야 하고</b>(§3.1) 수정 쪽에는
	 * <b>토큰 키가 없어야 한다</b>. Claim 에 {@code nickname} 이 없어 토큰이 낡을 이유가 없고,
	 * member 는 개인키를 갖지 않으므로 발급할 수도 없다 (sp-docs/requirements/member.md §7).
	 *
	 * <p><b>키마다 {@code doesNotExist} 를 쓰지 않는다.</b> 그러면 여기 적어 둔 이름만 막히고 새로
	 * 늘어난 키는 통과한다 — 집합 자체를 단언한다. 수정 응답은 <b>봉투까지</b> 본다: 토큰이 들어갈
	 * 자리는 {@code data} 안이거나 봉투이고, 봉투의 키는 {@code ApiResponse} 의 셋이다
	 * (sp-docs/api-contract.md §7).
	 */
	@Test
	@DisplayName("조회·수정 응답의 data 키가 accountId·nickname 둘뿐이고 어디에도 토큰 키가 없다")
	void 응답_키_집합() throws Exception {
		this.mockMvc.perform(등록(252L, """
						{"nickname": "키집합"}""")).andExpect(status().isCreated());

		String 조회 = this.mockMvc.perform(내_프로필(252L))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		String 수정 = this.mockMvc.perform(닉네임_수정(252L, """
						{"nickname": "바꾼키집합"}"""))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(조회, "$.data"))
				.as("GET /me 는 accountId·nickname 둘만 돌려준다 — createdAt 은 "
						+ "GET /members/{accountId} 의 것이다 (sp-docs/api-contract.md §3.1)")
				.containsOnlyKeys("accountId", "nickname");
		assertThat(JsonPath.<Map<String, Object>>read(수정, "$.data"))
				.as("PATCH /me 의 응답 모양은 GET /me 와 같다 (§3.1)")
				.containsOnlyKeys("accountId", "nickname");
		assertThat(JsonPath.<Map<String, Object>>read(수정, "$"))
				.as("봉투에도 토큰 키가 없다 (sp-docs/requirements/member.md §7)")
				.containsOnlyKeys("success", "data", "error");
	}

	/**
	 * 검증표: <b>프로필 없는 계정으로 조회하면 404 {@code M001}</b> 이다. 오류가 아니라 가입 3단계
	 * 미완료를 뜻한다 (sp-docs/api-contract.md §3 §8.2).
	 */
	@Test
	@DisplayName("프로필 없는 계정으로 조회하면 404 M001 이다")
	void 프로필_없는_계정_조회() throws Exception {
		this.mockMvc.perform(내_프로필(253L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("M001"))
				.andExpect(jsonPath("$.error.message").value("프로필을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("닉네임 변경은 200 과 갱신된 프로필이고 DB 에 반영된다")
	void 닉네임_변경() throws Exception {
		this.mockMvc.perform(등록(254L, """
						{"nickname": "옛닉네임"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(닉네임_수정(254L, """
						{"nickname": "새닉네임"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accountId").value(254))
				.andExpect(jsonPath("$.data.nickname").value("새닉네임"))
				.andExpect(jsonPath("$.error").doesNotExist());

		assertThat(this.memberRepository.findByAccountId(254L).orElseThrow().getNickname())
				.as("응답만 바뀌고 DB 가 그대로면 다음 조회가 옛 닉네임을 돌려준다")
				.isEqualTo("새닉네임");
	}

	@Test
	@DisplayName("타인이 쓰는 닉네임으로 변경하면 409 M003 이고 닉네임이 바뀌지 않는다")
	void 타인_닉네임으로_변경() throws Exception {
		this.mockMvc.perform(등록(255L, """
						{"nickname": "선점한닉네임"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(등록(257L, """
						{"nickname": "바꾸려는사람"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(닉네임_수정(257L, """
						{"nickname": "선점한닉네임"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M003"))
				.andExpect(jsonPath("$.error.message").value("이미 사용 중인 닉네임입니다."));

		assertThat(this.memberRepository.findByAccountId(257L).orElseThrow().getNickname())
				.isEqualTo("바꾸려는사람");
	}

	/**
	 * 검증표: <b>자기 현재 닉네임으로 변경하면 200</b> 이다. 중복 검사가 자기 행을 제외하지 않으면
	 * 409 {@code M003} 이 나가고, 바꾸지 않은 필드를 함께 보내는 평범한 수정 화면이 막힌다
	 * (sp-docs/requirements/member.md §7).
	 */
	@Test
	@DisplayName("자기 현재 닉네임으로 변경하면 200 이고 닉네임이 그대로다")
	void 자기_현재_닉네임으로_변경() throws Exception {
		this.mockMvc.perform(등록(258L, """
						{"nickname": "그대로인닉"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(닉네임_수정(258L, """
						{"nickname": "그대로인닉"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.nickname").value("그대로인닉"))
				.andExpect(jsonPath("$.error").doesNotExist());

		assertThat(this.memberRepository.findByAccountId(258L).orElseThrow().getNickname())
				.isEqualTo("그대로인닉");
	}

	/**
	 * 검증표의 "닉네임 1자" 다. 규칙은 등록과 같은 <b>2~10자, 한글/영문/숫자</b>이므로
	 * (sp-docs/requirements/member.md §2) 경계 둘과 문자 집합을 함께 본다 — 1자만 보면 수정 쪽
	 * 규칙이 길이뿐인 구현도 통과하고, 그 상태에서 등록이 거부하는 값을 수정이 통과시킨다.
	 *
	 * <p><b>{@code nickname} 누락·{@code null} 도 함께 본다.</b> {@code @Pattern} 은 {@code null}
	 * 을 유효로 취급하므로 {@code @NotNull} 이 없으면 그 요청이 서비스까지 내려간다. <b>거기서
	 * 터지지는 않는다</b> — 중복 검사의 제외 기준이 {@code accountId} 라 요청값과 현재 닉네임을
	 * 비교하는 자리가 없다 (sp-docs/requirements/member.md §7).
	 *
	 * <p><b>{@code @NotNull} 을 떼고 직접 측정한 값이다.</b> 아래 두 본문({@code {}} 과
	 * {@code {"nickname": null}})이 모두 <b>200</b> 과
	 * {@code {"success":true,"data":{"accountId":...,"nickname":null},"error":null}} 로 나가고,
	 * <b>활성 행({@code deleted = false})의 {@code nickname} 이 NULL 로 써진다.</b> NULL 닉네임은
	 * sp-docs/domain-model.md §3.1 이 <b>탈퇴</b>에 배정한 표현이므로, 활성인데 닉네임이 없는 —
	 * 세 상태 어디에도 없는 행이 남는다. <b>500 보다 나쁜 결말이다</b>: 500 은 드러나지만 이쪽은
	 * {@code success: true} 로 조용히 지나간다. {@code @NotNull} 은 올바르고, 떼면 이 테스트의 두
	 * 케이스가 {@code Status expected:<400> but was:<200>} 으로 빨개져 그것을 잡는다.
	 *
	 * <p>닉네임이 바뀌지 않았음을 함께 단언한다 — 상태 코드만 보면 "거부했지만 이미 썼다" 를
	 * 놓친다.
	 */
	@ParameterizedTest(name = "body={0}")
	@ValueSource(strings = {
			"{\"nickname\": \"가\"}",
			"{\"nickname\": \"가나다라마바사아자차카\"}",
			"{\"nickname\": \"nick!\"}",
			"{}",
			"{\"nickname\": null}"})
	@DisplayName("닉네임 규칙을 어긴 수정은 400 C001 이고 fieldErrors 1건이며 닉네임이 바뀌지 않는다")
	void 닉네임_규칙_위반_수정(String body) throws Exception {
		this.mockMvc.perform(등록(259L, """
						{"nickname": "안바뀌는닉"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(닉네임_수정(259L, body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.message").value("잘못된 입력값입니다."))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("nickname"))
				.andExpect(jsonPath("$.error.fieldErrors[0].message").value(NICKNAME_MESSAGE));

		assertThat(this.memberRepository.findByAccountId(259L).orElseThrow().getNickname())
				.isEqualTo("안바뀌는닉");
	}

	/**
	 * 검증표: <b>탈퇴한 프로필로 조회하면 404 {@code M001}</b> 이다
	 * (sp-docs/api-contract.md §3.1 의 표). 같은 프로필을 {@code GET /{accountId}} 로 보면 200 이
	 * 되는데(M-10), 그 차이가 의도다 — {@code /me} 는 "내 프로필을 보러 왔다" 이고 탈퇴했으면 볼
	 * 것이 없다.
	 */
	@Test
	@DisplayName("탈퇴한 프로필로 조회하면 404 M001 이다")
	void 탈퇴한_프로필_조회() throws Exception {
		this.mockMvc.perform(등록(260L, """
						{"nickname": "곧탈퇴한다"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(내_프로필(260L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nickname").value("곧탈퇴한다"));

		탈퇴시킨다(260L);

		this.mockMvc.perform(내_프로필(260L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M001"))
				.andExpect(jsonPath("$.error.message").value("프로필을 찾을 수 없습니다."));
	}

	/**
	 * 검증표: <b>탈퇴한 프로필로 수정하면 404 {@code M001}</b> 이다
	 * (sp-docs/api-contract.md §3.1 의 표 — 닫힌 행에 쓰기를 허용할 이유가 없다).
	 *
	 * <p>통과하면 탈퇴한 행이 닉네임을 쥔 채로 남는다 — 세 상태 어디에도 없는 표현이고 그 값은
	 * {@code uk_member_nickname} 에 선점된다. 그래서 행 상태까지 단언한다
	 * (sp-docs/domain-model.md §3.1).
	 */
	@Test
	@DisplayName("탈퇴한 프로필로 수정하면 404 M001 이고 닉네임이 되살아나지 않는다")
	void 탈퇴한_프로필_수정() throws Exception {
		this.mockMvc.perform(등록(261L, """
						{"nickname": "탈퇴할사람"}""")).andExpect(status().isCreated());
		탈퇴시킨다(261L);

		this.mockMvc.perform(닉네임_수정(261L, """
						{"nickname": "되살리려는닉"}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M001"))
				.andExpect(jsonPath("$.error.message").value("프로필을 찾을 수 없습니다."));

		Member stale = this.memberRepository.findByAccountId(261L).orElseThrow();
		assertThat(stale.isDeleted()).isTrue();
		assertThat(stale.getNickname()).isNull();
	}

	/**
	 * 검증표: <b>토큰 없이 {@code /me} 에 접근하면 401 {@code A001}</b> 이다
	 * (sp-docs/api-contract.md §8.5).
	 *
	 * <p>경로별 인가 행렬은 {@code SecurityConfigTest} 가 본다. 여기서 함께 보는 것은 <b>본문이
	 * 아무 효과도 내지 않았다</b>는 것이다 — 인증 전에 끊겼으므로 닉네임이 적용될 자리가 없다.
	 */
	@Test
	@DisplayName("토큰 없이 /me 를 조회·수정하면 401 A001 이고 닉네임이 바뀌지 않는다")
	void 토큰_없이_me_접근() throws Exception {
		this.mockMvc.perform(등록(262L, """
						{"nickname": "토큰없이는못바꿈"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(get(ME_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("A001"))
				.andExpect(jsonPath("$.error.message").value("인증이 필요합니다."));

		this.mockMvc.perform(patch(ME_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"nickname": "바꿔본다"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("A001"))
				.andExpect(jsonPath("$.error.message").value("인증이 필요합니다."));

		assertThat(this.memberRepository.findByAccountId(262L).orElseThrow().getNickname())
				.isEqualTo("토큰없이는못바꿈");
	}

	private static MockHttpServletRequestBuilder 등록(long accountId, String body) {
		return post(PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(accountId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private static MockHttpServletRequestBuilder 내_프로필(long accountId) {
		return get(ME_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accountId));
	}

	private static MockHttpServletRequestBuilder 닉네임_수정(long accountId, String body) {
		return patch(ME_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(accountId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	// --- 프로필 탈퇴 (M-09, sp-docs/api-contract.md §3 §3.1) ---

	/**
	 * 검증표: <b>정상 탈퇴 → 204, {@code deleted = true}, {@code nickname = null}, 행 존재</b>
	 * (sp-docs/plan/phase1.md §5 M-09).
	 *
	 * <p><b>요청에 본문이 없다.</b> 완료 기준 "비밀번호를 받지 않는다" 가 여기서 관측된다 —
	 * 본문을 요구하는 구현이면 이 요청이 400 이 된다
	 * (sp-docs/requirements/member.md §8: 재확인은 1단계에서 끝났다).
	 *
	 * <p>204 는 본문이 없으므로 응답이 빈 문자열임을 함께 못박는다
	 * (sp-docs/api-contract.md §3 의 표).
	 */
	@Test
	@DisplayName("정상 탈퇴는 204 이고 본문이 없으며 행은 deleted = true, nickname = NULL 로 남는다")
	void 정상_탈퇴() throws Exception {
		this.mockMvc.perform(등록(281L, """
						{"nickname": "탈퇴하는이"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(탈퇴(281L))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		Member withdrawn = this.memberRepository.findByAccountId(281L)
				.orElseThrow(() -> new AssertionError(
						"행을 삭제하지 않는다 (sp-docs/domain-model.md §3.1)"));
		assertThat(withdrawn.isDeleted()).isTrue();
		assertThat(withdrawn.getNickname())
				.as("\"탈퇴한 회원\" 은 응답 시 변환이지 저장이 아니다 (sp-docs/domain-model.md §3.1)")
				.isNull();
	}

	/**
	 * 검증표: <b>이미 탈퇴한 프로필에 재호출 → 204 (멱등)</b>
	 * (sp-docs/api-contract.md §3.1 의 표 — {@code DELETE /me} 는 그 표에서 404 가 아닌 유일한
	 * {@code /me} 행이고, auth 의 {@code DELETE /accounts/me} 가 같은 예외다, §2.2).
	 *
	 * <p>탈퇴한 프로필을 {@code M001} 로 거부하는 구현은 여기서 404 를 내며 붉어진다. 그 결론은
	 * {@code GET /me}·{@code PATCH /me} 의 것이다.
	 */
	@Test
	@DisplayName("이미 탈퇴한 프로필에 다시 탈퇴하면 204 다")
	void 이미_탈퇴한_프로필_재호출() throws Exception {
		this.mockMvc.perform(등록(282L, """
						{"nickname": "두번탈퇴"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(탈퇴(282L)).andExpect(status().isNoContent());

		this.mockMvc.perform(탈퇴(282L))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		Member again = this.memberRepository.findByAccountId(282L).orElseThrow();
		assertThat(again.isDeleted()).isTrue();
		assertThat(again.getNickname()).isNull();
	}

	/**
	 * 검증표: <b>두 회원을 연달아 탈퇴 → 둘 다 204. UNIQUE 위반 없음.</b>
	 *
	 * <p><b>이 케이스가 무엇을 깨뜨릴 수 있는지</b>: 탈퇴 시 닉네임을 {@code "탈퇴한 회원"} 같은
	 * 고정 문자열로 <b>저장</b>하는 구현에서는 두 번째 탈퇴가 {@code uk_member_nickname} 을 밟아
	 * 500 {@code C005} 가 된다 (sp-docs/domain-model.md §3.1). 그래서 두 번째의 204 자체가
	 * 반증 가능한 단언이다.
	 *
	 * <p><b>그것만으로는 "닉네임을 그대로 남기는" 구현을 가르지 못한다</b> — 두 닉네임이 다르므로
	 * UNIQUE 가 깨지지 않는다. 그 구현은 해방이 일어나지 않는 것으로 드러나므로, 두 행의
	 * {@code nickname} 이 NULL 임을 함께 단언한다.
	 */
	@Test
	@DisplayName("두 회원을 연달아 탈퇴하면 둘 다 204 이고 UNIQUE 위반이 없다")
	void 두_회원_연달아_탈퇴() throws Exception {
		this.mockMvc.perform(등록(283L, """
						{"nickname": "먼저탈퇴"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(등록(284L, """
						{"nickname": "나중탈퇴"}""")).andExpect(status().isCreated());

		this.mockMvc.perform(탈퇴(283L)).andExpect(status().isNoContent());
		this.mockMvc.perform(탈퇴(284L))
				// 고정 문자열을 저장하는 구현은 여기서 uk_member_nickname 을 밟는다
				.andExpect(status().isNoContent());

		Member first = this.memberRepository.findByAccountId(283L).orElseThrow();
		Member second = this.memberRepository.findByAccountId(284L).orElseThrow();
		assertThat(first.isDeleted()).isTrue();
		assertThat(second.isDeleted()).isTrue();
		assertThat(first.getNickname()).isNull();
		assertThat(second.getNickname()).isNull();
	}

	/**
	 * 검증표: <b>탈퇴 후 {@code GET /members/me} → 404 {@code M001}</b>
	 * (sp-docs/api-contract.md §3.1 의 표).
	 *
	 * <p>M-08 의 같은 결론을 보는 테스트는 엔티티 메서드로 탈퇴 상태를 만든다. 여기서는
	 * <b>실제 {@code DELETE /me} 경로</b>로 만든 상태를 본다 — 탈퇴 경로가 만드는 상태와 조회가
	 * 거부하는 상태가 같은 것임을 잇는 자리다.
	 */
	@Test
	@DisplayName("DELETE /me 로 탈퇴한 뒤 GET /me 는 404 M001 이다")
	void 탈퇴_후_내_프로필_조회() throws Exception {
		this.mockMvc.perform(등록(285L, """
						{"nickname": "조회못한다"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(내_프로필(285L))
				// 탈퇴 전에는 조회된다 — 늘 404 인 구현과 가른다
				.andExpect(status().isOk());

		this.mockMvc.perform(탈퇴(285L)).andExpect(status().isNoContent());

		this.mockMvc.perform(내_프로필(285L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M001"))
				.andExpect(jsonPath("$.error.message").value("프로필을 찾을 수 없습니다."));
	}

	/**
	 * 검증표: <b>탈퇴 후 {@code POST /members} 재시도 → 409 {@code M007}</b>
	 * (sp-docs/api-contract.md §3 의 표). 행이 남아 있고 {@code uk_member_account_id} 가 재생성을
	 * 막는다는 것을 HTTP 경로로 확인하는 자리다 (sp-docs/domain-model.md §3.1).
	 *
	 * <p>탈퇴한 행은 {@code nickname} 이 NULL 이므로 <b>같은 닉네임으로 재시도해도</b> 결론은
	 * {@code M007} 이다 — 닉네임 비교가 먼저 오면 {@code M006} 이 나간다.
	 */
	@Test
	@DisplayName("DELETE /me 로 탈퇴한 뒤 POST /members 재시도는 409 M007 이다")
	void 탈퇴_후_재등록() throws Exception {
		this.mockMvc.perform(등록(286L, """
						{"nickname": "재등록불가"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(탈퇴(286L)).andExpect(status().isNoContent());

		this.mockMvc.perform(등록(286L, """
						{"nickname": "재등록불가"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M007"))
				.andExpect(jsonPath("$.error.message").value("탈퇴한 프로필입니다."));

		Member stale = this.memberRepository.findByAccountId(286L).orElseThrow();
		assertThat(stale.isDeleted())
				.as("행이 되살아나지 않는다")
				.isTrue();
		assertThat(stale.getNickname()).isNull();
	}

	/**
	 * 검증표: <b>탈퇴 후 해방된 닉네임으로 타인이 등록 → 201.</b>
	 *
	 * <p>M-08 의 같은 결론을 보는 테스트는 엔티티 메서드로 탈퇴 상태를 만들고 중복 확인까지만
	 * 본다. 여기서는 <b>실제 {@code DELETE /me} 경로</b>로 비운 뒤 다른 계정이 그 값으로
	 * <b>실제 등록까지</b> 한다 — 해방은 {@code uk_member_nickname} 이 걸린 INSERT 가 통과해야
	 * 참이다.
	 *
	 * <p>탈퇴 전에 그 닉네임이 409 {@code M003} 으로 막힘을 먼저 본다. 그러지 않으면 닉네임을
	 * 애초에 저장하지 않는 구현도 통과한다 (sp-docs/api-contract.md §2.3).
	 */
	@Test
	@DisplayName("DELETE /me 로 해방된 닉네임을 다른 계정이 등록하면 201 이다")
	void 탈퇴로_해방된_닉네임_타인_등록() throws Exception {
		this.mockMvc.perform(등록(287L, """
						{"nickname": "물려주는닉"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(등록(288L, """
						{"nickname": "물려주는닉"}"""))
				// 탈퇴 전에는 선점되어 있다 — 닉네임을 저장하지 않는 구현과 가른다
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("M003"));

		this.mockMvc.perform(탈퇴(287L)).andExpect(status().isNoContent());

		this.mockMvc.perform(등록(288L, """
						{"nickname": "물려주는닉"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.accountId").value(288))
				.andExpect(jsonPath("$.data.nickname").value("물려주는닉"));

		assertThat(this.memberRepository.findByAccountId(287L).orElseThrow().getNickname())
				.as("물려준 쪽은 NULL 로 남는다")
				.isNull();
	}

	private static MockHttpServletRequestBuilder 탈퇴(long accountId) {
		// 본문을 실지 않는다 — 비밀번호를 포함해 아무것도 받지 않는다
		// (sp-docs/requirements/member.md §8)
		return delete(ME_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accountId));
	}

	private static String bearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, "USER");
	}

	// --- 공개 프로필 조회 (M-10, sp-docs/api-contract.md §3 §3.1) ---

	/**
	 * 검증표: <b>프로필 조회 → 200, 키가 {@code accountId}·{@code nickname}·{@code createdAt}·
	 * {@code deleted}. {@code email} 없음</b> (sp-docs/api-contract.md §3.1).
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: {@code MemberResponse} 를 그대로 재사용한 구현은
	 * {@code createdAt}·{@code deleted} 가 없어 키 집합 단언이 빨개진다. 반대로
	 * {@code MemberResponse} 를 늘려 쓴 구현은 {@code GET /me} 쪽 키 집합 단언(위의
	 * {@code 응답_키_집합})이 빨개진다 — 두 조회가 다른 모양인 것이 계약이다.
	 *
	 * <p><b>키마다 {@code doesNotExist} 를 쓰지 않는다.</b> 집합 자체를 단언하면 새로 늘어난 키도
	 * 잡힌다.
	 *
	 * <p><b>{@code createdAt} 을 고정 패턴으로 못박지 않는다.</b> 소수부는 저장값에 따른다 —
	 * 감사가 만든 값에는 없지만({@code D-11} 이 초로 잘랐다, sp-docs/api-contract.md §4.3)
	 * 마이그레이션·SQL 픽스처가 직접 넣은 행에는 남을 수 있다. 그래서 <b>소수부를 선택으로 둔 정규식</b>으로 계약(오프셋 없는
	 * ISO-8601 지역시각)을 보고, 값 자체는 <b>DB 에 저장된 {@code member.created_at} 과 같은지</b>로
	 * 본다 — 그것이 완료 기준 "그 {@code createdAt} 은 {@code member.created_at} 이다" 가 요구하는
	 * 것이고, 응답에 {@code updated_at} 이나 현재 시각을 실은 구현을 가른다.
	 */
	@Test
	@DisplayName("공개 프로필 조회는 200 이고 키가 accountId·nickname·createdAt·deleted 정확히 넷이다")
	void 공개_프로필_조회() throws Exception {
		this.mockMvc.perform(등록(291L, """
						{"nickname": "공개조회대상"}""")).andExpect(status().isCreated());

		String 본문 = this.mockMvc.perform(get(PATH + "/291"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accountId").value(291))
				.andExpect(jsonPath("$.data.nickname").value("공개조회대상"))
				.andExpect(jsonPath("$.data.deleted").value(false))
				.andExpect(jsonPath("$.error").doesNotExist())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(본문, "$.data"))
				.as("GET /members/{accountId} 는 네 키다 — email 도 member.id 도 없다 "
						+ "(sp-docs/api-contract.md §3.1)")
				.containsOnlyKeys("accountId", "nickname", "createdAt", "deleted");

		String createdAt = JsonPath.read(본문, "$.data.createdAt");
		assertThat(createdAt)
				.as("오프셋을 표기하지 않고 소수부는 저장값에 따른다 (sp-docs/tech-stack.md §6)")
				.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
		assertThat(LocalDateTime.parse(createdAt))
				.as("member.created_at(프로필 등록 시각)이다. account.created_at 도 updated_at 도 "
						+ "아니다 (sp-docs/api-contract.md §3.1)")
				.isEqualTo(this.memberRepository.findByAccountId(291L).orElseThrow().getCreatedAt());
	}

	/**
	 * 검증표: <b>탈퇴한 프로필 공개 조회 → 200, {@code nickname = "탈퇴한 회원"},
	 * {@code deleted: true}</b> (sp-docs/api-contract.md §3.1 의 탈퇴 상태 표). 그리고 <b>탈퇴
	 * 프로필의 DB 값은 {@code nickname IS NULL}</b> 이다 (sp-docs/domain-model.md §3.1).
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 탈퇴를 "없음" 으로 보는 구현(예: 탈퇴 검사를
	 * {@code MemberReader} 로 올리거나 이 경로에서 {@code M001} 을 내는 구현)은 404 로 빨개진다 —
	 * 글이 남아 있으므로 표시할 것이 있고, 404 를 주면 클라이언트가 정상적으로 존재하는 글에
	 * 오류를 띄운다. 마스킹을 <b>저장</b>으로 바꾼 구현은 DB 단언에서 빨개진다.
	 *
	 * <p>탈퇴 전에 실제 닉네임이 나오는 것을 먼저 본다 — 늘 {@code "탈퇴한 회원"} 을 돌려주는
	 * 구현과 가른다.
	 */
	@Test
	@DisplayName("탈퇴한 프로필의 공개 조회는 200 에 \"탈퇴한 회원\"·deleted true 이고 DB 는 NULL 이다")
	void 탈퇴한_프로필_공개_조회() throws Exception {
		this.mockMvc.perform(등록(292L, """
						{"nickname": "탈퇴전닉네임"}""")).andExpect(status().isCreated());
		this.mockMvc.perform(get(PATH + "/292"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nickname").value("탈퇴전닉네임"))
				.andExpect(jsonPath("$.data.deleted").value(false));

		this.mockMvc.perform(탈퇴(292L)).andExpect(status().isNoContent());

		this.mockMvc.perform(get(PATH + "/292"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accountId").value(292))
				.andExpect(jsonPath("$.data.nickname").value("탈퇴한 회원"))
				.andExpect(jsonPath("$.data.deleted").value(true))
				.andExpect(jsonPath("$.error").doesNotExist());

		Map<String, Object> row =
				this.jdbcTemplate.queryForMap("select * from member where account_id = ?", 292L);
		assertThat(row.get("nickname"))
				.as("\"탈퇴한 회원\" 은 응답 시 변환이지 저장이 아니다 (sp-docs/domain-model.md §3.1)")
				.isNull();
	}

	/**
	 * 검증표: <b>프로필이 없는 {@code accountId} → 404 {@code M001}</b>
	 * (sp-docs/api-contract.md §3.1 의 표).
	 *
	 * <p><b>이 404 는 공개 조회까지다.</b> §5.1 이 금지한 것은 내부 API 에서 404 를 업무 의미로
	 * 쓰는 것이고, 이 경로는 클라이언트를 상대하므로 404 가 맞다 — 두 경로를 같게 맞추지 않는다.
	 * 내부 API 쪽 결론은 {@code InternalMemberControllerTest} 가 본다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 없는 {@code accountId} 에 200 과 빈 본문·빈 닉네임을
	 * 돌려주는 구현(내부 API 의 "제외" 규칙을 이쪽에도 적용한 구현)은 여기서 빨개진다.
	 */
	@Test
	@DisplayName("프로필이 없는 accountId 의 공개 조회는 404 M001 이다")
	void 프로필이_없는_accountId_공개_조회() throws Exception {
		assertThat(this.memberRepository.findByAccountId(293L))
				.as("이 accountId 로는 등록하지 않았다")
				.isEmpty();

		this.mockMvc.perform(get(PATH + "/293"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("M001"))
				.andExpect(jsonPath("$.error.message").value("프로필을 찾을 수 없습니다."))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	/**
	 * 완료 기준·검증표: <b>{@code GET /members/me} 가 {@code GET /members/{accountId}} 보다 먼저
	 * 매칭된다</b> (sp-docs/plan/phase1.md §5 M-10).
	 *
	 * <p>{@code PathPattern} 은 리터럴 세그먼트를 변수보다 구체적으로 보지만 <b>그 동작에 기대고
	 * 끝내지 않는다</b> — {@code me} 가 {@code accountId} 로 해석되면 숫자 파싱이 실패해 엉뚱한
	 * 오류가 난다.
	 *
	 * <p><b>무엇으로 가르나.</b> 두 핸들러의 <b>응답 모양이 다르다</b>
	 * (sp-docs/api-contract.md §3.1) — {@code /me} 는 두 키고 {@code /{accountId}} 는 네 키다.
	 * 그래서 키 집합이 "어느 핸들러가 받았는가" 를 말해 준다. 상태 코드만 보면 두 경로가 모두
	 * 200 일 수 있어 가르지 못한다.
	 *
	 * <p>같은 계정으로 두 경로를 함께 호출한다 — 두 매핑이 동시에 살아 있고 서로를 가리지 않음을
	 * 한 테스트에서 본다.
	 */
	@Test
	@DisplayName("GET /members/me 는 {accountId} 매핑에 먹히지 않고 내 프로필을 돌려준다")
	void me_가_accountId_보다_먼저_매칭된다() throws Exception {
		this.mockMvc.perform(등록(294L, """
						{"nickname": "라우팅확인"}""")).andExpect(status().isCreated());

		String me = this.mockMvc.perform(내_프로필(294L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accountId").value(294))
				.andExpect(jsonPath("$.data.nickname").value("라우팅확인"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(me, "$.data"))
				.as("/me 핸들러가 받았다 — {accountId} 핸들러가 받았다면 네 키다 "
						+ "(sp-docs/api-contract.md §3.1)")
				.containsOnlyKeys("accountId", "nickname");

		String 공개 = this.mockMvc.perform(get(PATH + "/294"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(공개, "$.data"))
				.as("같은 계정의 공개 조회는 네 키다 — 두 매핑이 함께 살아 있다")
				.containsOnlyKeys("accountId", "nickname", "createdAt", "deleted");
	}

	// --- 프로필 일괄 조회 (M-16, sp-docs/api-contract.md §3.2) ---
	//
	// 호출자는 따로 적지 않으면 seed 가 아닌 계정(NON_SEED_CALLER_ID)이다. ADMIN 판정은 그 계정과
	// seed 계정에 각각 ADMIN·USER 토큰을 실은 네 조합으로 본다(NON_SEED_CALLER_ID 의 javadoc).
	// 조회 대상과 호출자의 프로필은 무관하다.
	// 픽스처를 accountId 오름차순이 아닌 순서로 등록한다 — 삽입 순서대로 돌려주는 구현이 순서
	// 단언을 우연히 통과하지 않게 한다

	/**
	 * 검증표: <b>ADMIN, {@code accountIds=3,1} → 200, {@code accountId} 1·3 의 순서</b>. 완료
	 * 기준: <b>원소가 §3.1 의 공개 조회와 같은 모양이다</b> (sp-docs/api-contract.md §3.2).
	 *
	 * <p>id 셋을 오름차순도 내림차순도 아닌 순서로 보낸다 — 둘이면 요청 순서를 뒤집는 구현도
	 * 통과한다. 조회에서 {@code ORDER BY} 가 빠진 것은 이 단언이 H2 에서 잡지 못한다 —
	 * {@code MemberServiceTest.일괄_조회의_정렬} 이 일괄 조회가 낸 SQL 을 보고 잡는다.
	 *
	 * <p><b>원소를 같은 id 의 {@code GET /{accountId}} 응답과 통째로 비교한다.</b> 키 집합과 값이
	 * 함께 같아야 하므로 원소에 키를 더하거나 빼거나 값을 다르게 변환한 구현이 여기서 빨개진다.
	 */
	@Test
	@DisplayName("ADMIN 의 일괄 조회는 200 이고 accountId 오름차순이며 원소가 공개 조회의 응답과 같다")
	void 일괄_조회_오름차순과_원소_모양() throws Exception {
		등록한다(312L, "일괄둘째");
		등록한다(313L, "일괄셋째");
		등록한다(311L, "일괄첫째");

		String 본문 = this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=313,311,312"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").doesNotExist())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(본문, "$.data"))
				.as("data 의 키는 members 다 (sp-docs/api-contract.md §3.2)")
				.containsOnlyKeys("members");
		List<Map<String, Object>> members = JsonPath.read(본문, "$.data.members");
		assertThat(members).extracting(member -> member.get("accountId"))
				.as("accountId 오름차순이다 (§3.2) — 요청 순서가 아니다")
				.containsExactly(311, 312, 313);
		for (Map<String, Object> member : members) {
			assertThat(member)
					.as("원소는 GET /{accountId} 의 data 와 같다 (§3.2)")
					.isEqualTo(공개_조회_data(((Number) member.get("accountId")).longValue()));
		}
	}

	/** 검증표: <b>{@code accountIds=1&accountIds=2} → 둘 다</b> (sp-docs/api-contract.md §3.2). */
	@Test
	@DisplayName("같은 이름을 되풀이한 accountIds 도 받는다")
	void 같은_이름의_반복() throws Exception {
		등록한다(312L, "반복둘째");
		등록한다(311L, "반복첫째");

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=312&accountIds=311"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(2)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(311))
				.andExpect(jsonPath("$.data.members[0].nickname").value("반복첫째"))
				.andExpect(jsonPath("$.data.members[1].accountId").value(312))
				.andExpect(jsonPath("$.data.members[1].nickname").value("반복둘째"));
	}

	/**
	 * 검증표: <b>없는 id 가 섞였다 → 빠진다</b>. 404 가 아니다 — 프로필이 없는 계정(가입 3단계
	 * 미완료)이 정상이라 화면이 「프로필 없음」 으로 그린다 (sp-docs/api-contract.md §3.2). 전부
	 * 없으면 빈 배열이다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 공개 조회의 결론(없으면 {@code M001})을 옮겨 온 구현은
	 * 404 로 빨개진다.
	 */
	@Test
	@DisplayName("프로필이 없는 accountId 는 빠지고 전부 없으면 빈 배열이다 — 404 가 아니다")
	void 없는_id_는_빠진다() throws Exception {
		등록한다(311L, "있는프로필");
		assertThat(this.memberRepository.findByAccountId(319L))
				.as("이 accountId 로는 등록하지 않았다")
				.isEmpty();

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=319,311"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(311));

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=319"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.members", hasSize(0)));
	}

	/** 검증표: <b>같은 id 두 번 → 한 번</b> (sp-docs/api-contract.md §3.2). */
	@Test
	@DisplayName("같은 accountId 를 두 번 주면 한 번만 싣는다")
	void 같은_id_는_한_번() throws Exception {
		등록한다(311L, "한번만");

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=311,311"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(311));
	}

	/**
	 * 검증표: <b>탈퇴한 프로필 → {@code "탈퇴한 회원"}, {@code deleted: true}</b>
	 * (sp-docs/api-contract.md §3.2). 마스킹 규칙은 하나다 — 원소가 같은 id 의 공개 조회와 같다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 조회에 {@code deleted = false} 조건을 넣은 구현은 원소가
	 * 없어 빨개지고, 저장값(NULL)을 그대로 실은 구현은 닉네임 단언에서 빨개진다.
	 */
	@Test
	@DisplayName("탈퇴한 프로필은 \"탈퇴한 회원\"·deleted true 로 실리고 공개 조회와 같은 원소다")
	void 탈퇴한_프로필_일괄_조회() throws Exception {
		등록한다(314L, "일괄탈퇴전");
		this.mockMvc.perform(탈퇴(314L)).andExpect(status().isNoContent());

		String 본문 = this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=314"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(314))
				.andExpect(jsonPath("$.data.members[0].nickname").value("탈퇴한 회원"))
				.andExpect(jsonPath("$.data.members[0].deleted").value(true))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(본문, "$.data.members[0]"))
				.as("마스킹 규칙은 하나다 — 공개 조회와 같은 원소다 (§3.2)")
				.isEqualTo(공개_조회_data(314L));
	}

	/**
	 * 개수가 §3.2 의 범위 안인 양 끝(1개·50개)은 200 이다. 거부하는 쪽만 보면 하한을 올리거나
	 * 상한을 내리는 어긋남이 조용하다 (sp-docs/conventions.md §9.2).
	 *
	 * <p>등록한 프로필을 목록에 넣어 결과가 실제로 실리는 것까지 본다. <b>등록하는 것은 목록의 마지막
	 * id 다.</b> 첫 id 를 등록하면 두 개수 모두에서 등록한 id 가 목록의 맨 앞이라, 목록의 앞부분만
	 * 읽는 구현(서비스가 앞의 49개만 조회하는 변이)이 50개에서도 통과했다(M-16 이 변이로 쟀다).
	 */
	@ParameterizedTest(name = "{0}개")
	@ValueSource(ints = {1, 50})
	@DisplayName("accountIds 가 1개·50개면 200 이다")
	void 개수_범위_안(int count) throws Exception {
		int 마지막_id = 311 + count - 1;
		등록한다(마지막_id, "범위안");

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=" + 연속_id(311L, count)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(마지막_id));
	}

	/**
	 * 검증표: <b>최대보다 많은 id → 400 {@code C001}, {@code fieldErrors} 에 {@code accountIds}</b>.
	 * <b>절삭하지 않는다</b> — 앞의 50개로 200 을 주면 잘린 id 의 프로필이 조용히 빠진다
	 * (sp-docs/api-contract.md §3.2). 경계는 양쪽이므로 (sp-docs/conventions.md §9.2) 바로 바깥의 다른
	 * 쪽인 0개({@code accountIds=})도 본다 — 범위 밖이면 같은 응답이다 (sp-docs/plan/phase2-auth-member.md
	 * 의 M-16 완료 기준).
	 */
	@ParameterizedTest(name = "{0}개")
	@ValueSource(ints = {0, 51})
	@DisplayName("accountIds 가 0개·51개면 400 C001 이고 fieldErrors 에 accountIds 다 — 절삭하지 않는다")
	void 개수_범위_밖(int count) throws Exception {
		등록한다(311L, "범위밖");

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=" + 연속_id(311L, count)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("accountIds"));
	}

	/**
	 * 개수는 보낸 값의 수다 — 되풀이한 id 도 센다 ({@code MemberController#getProfiles}). 같은 id
	 * 51번은 한 원소로 실릴 목록이지만 50개를 넘는다.
	 */
	@Test
	@DisplayName("되풀이한 id 도 개수에 세어 51개면 400 C001 이다")
	void 되풀이한_id_도_센다() throws Exception {
		String 같은_id_51번 = String.join(",", Collections.nCopies(51, "311"));

		this.mockMvc.perform(일괄_조회("ADMIN", "accountIds=" + 같은_id_51번))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("accountIds"));
	}

	/**
	 * 검증표: <b>{@code accountIds} 누락 → 400 {@code C001}, {@code fieldErrors} 빈 배열</b> — 바인딩
	 * 자체가 일어나지 않아 담을 필드가 없다 (sp-docs/api-contract.md §3.2 §2.3).
	 */
	@Test
	@DisplayName("accountIds 가 없으면 400 C001 이고 fieldErrors 가 빈 배열이다")
	void accountIds_누락() throws Exception {
		this.mockMvc.perform(일괄_조회("ADMIN", ""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(0)));
	}

	/**
	 * 검증표: <b>{@code accountIds=abc} → 400 {@code C002}</b> (sp-docs/api-contract.md §3.2).
	 * 숫자가 아닌 원소가 하나만 섞여도, 되풀이한 이름 쪽에 있어도 같다 — 나머지 id 로 200 을 주지
	 * 않는다.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"accountIds=abc", "accountIds=311,abc", "accountIds=311&accountIds=abc"})
	@DisplayName("숫자가 아닌 accountIds 는 400 C002 다")
	void 숫자가_아닌_값(String query) throws Exception {
		등록한다(311L, "형식확인");

		this.mockMvc.perform(일괄_조회("ADMIN", query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("C002"));
	}

	/**
	 * 빈 원소는 원소의 {@code @NotNull} 이 거부한다 — 400 {@code C001} 이고 (sp-docs/conventions.md
	 * §6.1), 빈 원소마다 {@code fieldErrors} 에 {@code accountIds} 1건이다
	 * ({@code MemberController#getProfiles}). 쉼표 목록 안·끝, 되풀이한 이름의 빈 값을 함께 본다 —
	 * 세 입력 모두 빈 원소가 하나라 {@code fieldErrors} 가 1건이다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 원소의 {@code @NotNull} 을 뺀 구현은 세 입력 모두 200 으로
	 * 빨개진다 — 빈 원소가 {@code null} 로 조회에 실려 조용히 빠진다(M-16 이 변이로 쟀다).
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"accountIds=311,,312", "accountIds=311,", "accountIds=311&accountIds="})
	@DisplayName("빈 원소가 있으면 400 C001 이고 fieldErrors 에 accountIds 다")
	void 빈_원소(String query) throws Exception {
		등록한다(311L, "빈원소확인");

		this.mockMvc.perform(일괄_조회("ADMIN", query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("accountIds"));
	}

	/**
	 * 검증표: <b>USER 토큰 → 403 {@code A004}</b> (sp-docs/api-contract.md §3.2,
	 * sp-docs/security.md §5.1). 등록해 둔 id 를 묻는다 — 거부가 빈 결과로 위장되지 않고 원소도
	 * 실리지 않는다.
	 *
	 * <p><b>토큰의 계정은 seed 다</b> — ADMIN 판정을 보는 네 조합 중 하나다
	 * ({@link #NON_SEED_CALLER_ID}).
	 */
	@Test
	@DisplayName("seed 계정의 USER 토큰이어도 일괄 조회는 403 A004 이고 원소를 싣지 않는다")
	void USER_토큰_일괄_조회() throws Exception {
		등록한다(311L, "일반사용자");

		this.mockMvc.perform(get(PATH + "?accountIds=311")
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(SEED_ACCOUNT_ID, "USER")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("A004"))
				.andExpect(jsonPath("$.error.message").value("권한이 없습니다."));
	}

	/**
	 * seed 가 아닌 계정의 USER 토큰도 403 {@code A004} 다 — ADMIN 판정을 보는 네 조합 중 하나다
	 * ({@link #NON_SEED_CALLER_ID}). {@code role} 을 보지 않고 seed 계정만 거부하는 구현이 여기서
	 * 빨개진다.
	 */
	@Test
	@DisplayName("seed 가 아닌 계정의 USER 토큰도 일괄 조회는 403 A004 이고 원소를 싣지 않는다")
	void seed_가_아닌_계정의_USER_토큰_일괄_조회() throws Exception {
		등록한다(311L, "일반계정");

		this.mockMvc.perform(일괄_조회("USER", "accountIds=311"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("A004"));
	}

	/**
	 * seed 계정의 ADMIN 토큰도 200 이다 — ADMIN 판정을 보는 네 조합 중 하나다
	 * ({@link #NON_SEED_CALLER_ID}). 계정이 seed 면 거부하는 구현이 여기서 빨개진다.
	 */
	@Test
	@DisplayName("seed 계정의 ADMIN 토큰이면 일괄 조회는 200 이고 원소를 싣는다")
	void seed_계정의_ADMIN_토큰_일괄_조회() throws Exception {
		등록한다(311L, "시드관리확인");

		this.mockMvc.perform(get(PATH + "?accountIds=311")
						.header(HttpHeaders.AUTHORIZATION,
								"Bearer " + TestTokenFactory.accessToken(SEED_ACCOUNT_ID, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(311))
				.andExpect(jsonPath("$.data.members[0].nickname").value("시드관리확인"));
	}

	/**
	 * 검증표: <b>토큰 없음 → 401 {@code A001}</b>, 그리고 <b>{@code GET /api/v1/members/{accountId}}
	 * 는 그대로 무인증으로 응답한다</b>. 둘을 한 테스트에서 본다 — 두 경로는 세그먼트 수로 갈리는
	 * 다른 규칙이고 (sp-docs/api-contract.md §3.2), 일괄 조회를 인증 경로로 선언한 것이 세그먼트가
	 * 하나 더 있는 공개 조회를 막지 않아야 한다.
	 */
	@Test
	@DisplayName("토큰 없는 일괄 조회는 401 A001 이고 같은 id 의 공개 조회는 무인증 200 이다")
	void 토큰_없는_일괄_조회와_공개_조회() throws Exception {
		등록한다(311L, "무인증확인");

		this.mockMvc.perform(get(PATH + "?accountIds=311"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.error.code").value("A001"));

		this.mockMvc.perform(get(PATH + "/311"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accountId").value(311))
				.andExpect(jsonPath("$.data.nickname").value("무인증확인"));
	}

	private void 등록한다(long accountId, String nickname) throws Exception {
		this.mockMvc.perform(등록(accountId, "{\"nickname\": \"" + nickname + "\"}"))
				.andExpect(status().isCreated());
	}

	/** {@link #NON_SEED_CALLER_ID} 의 토큰으로 보낸다. {@code query} 가 비면 쿼리 문자열 없이 보낸다. */
	private static MockHttpServletRequestBuilder 일괄_조회(String role, String query) {
		String 경로 = query.isEmpty() ? PATH : PATH + "?" + query;
		return get(경로).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + TestTokenFactory.accessToken(NON_SEED_CALLER_ID, role));
	}

	/** 공개 조회({@code GET /{accountId}}) 응답의 {@code data}. */
	private Map<String, Object> 공개_조회_data(long accountId) throws Exception {
		String 본문 = this.mockMvc.perform(get(PATH + "/" + accountId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.read(본문, "$.data");
	}

	/** {@code first} 부터 연속한 id {@code count} 개를 쉼표로 잇는다. 0개면 빈 문자열이다. */
	private static String 연속_id(long first, int count) {
		return LongStream.range(first, first + count)
				.mapToObj(String::valueOf)
				.collect(Collectors.joining(","));
	}

	/** 실제 탈퇴 경로는 M-09 다. 지금은 엔티티 메서드로 같은 상태를 만든다. */
	private void 탈퇴시킨다(long accountId) {
		Member profile = this.memberRepository.findByAccountId(accountId).orElseThrow();
		profile.withdraw();
		this.memberRepository.saveAndFlush(profile);
	}

}
