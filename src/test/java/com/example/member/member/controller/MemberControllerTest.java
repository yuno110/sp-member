package com.example.member.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import com.example.member.support.TestTokenFactory;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * <p>픽스처의 {@code accountId} 는 M-05 가 211~, <b>M-08 이 251~</b> 을 쓰고, 닉네임은
 * {@code 관리자} 가 아닌 값을 쓴다. ADMIN seed 가 {@code (1, '관리자')} 를 넣으므로 겹치면 UNIQUE
 * 위반으로 깨진다 (sp-docs/requirements/member.md §10.1). M-08 의 {@code MemberServiceTest}
 * 픽스처(241~)와도 범위를 겹치지 않게 둔다.
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

	/** sp-docs/requirements/member.md §2 가 정한 닉네임 메시지다. */
	private static final String NICKNAME_MESSAGE = "닉네임은 2~10자여야 합니다.";

	@DynamicPropertySource
	static void jwtPublicKey(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
	}

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

	private static String bearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, "USER");
	}

	/** 실제 탈퇴 경로는 M-09 다. 지금은 엔티티 메서드로 같은 상태를 만든다. */
	private void 탈퇴시킨다(long accountId) {
		Member profile = this.memberRepository.findByAccountId(accountId).orElseThrow();
		profile.withdraw();
		this.memberRepository.saveAndFlush(profile);
	}

}
