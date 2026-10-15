package com.example.member.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * sp-docs/plan/phase1.md §5 M-10 의 "검증" 표 중 내부 API 쪽을 테스트로 옮긴 것이다. 정본은
 * sp-docs/api-contract.md §5 §5.1, sp-docs/requirements/member.md §9, sp-docs/security.md §6 다.
 *
 * <p><b>응답 코드의 경계가 좁은 이유</b>: §5.1 이 member 의 <b>모든 4xx·5xx·타임아웃</b>을
 * board 에서 503 {@code S001} 인프라 경보로 분류한다. 그래서 "해석할 {@code accountId} 가 없다"
 * 거나 "그 프로필이 없다" 같은 <b>정상 상황이 4xx 가 되면 경로 오설정과 구별되지 않는 경보가
 * 뜬다.</b> 아래 테스트들이 그 경계를 건다 — 빈 배열은 200, 없는 id 는 제외, 누락·{@code null}
 * 만 400 이다.
 *
 * <p>키는 <b>테스트 전용 값</b>이다. {@code src/test/resources/application-test.yml} 이 주고
 * 운영·로컬 값은 환경이 준다 (sp-docs/tech-stack.md §4.3). 여기에 리터럴로 적는 이유는 운영
 * 설정의 값을 읽어와 단언하면 "맞는 키" 의 정의가 설정과 함께 움직이기 때문이다.
 *
 * <p>픽스처의 {@code accountId} 는 <b>289 와 295~299</b> 다. 앞선 항목들이 쓰는 범위와 겹치지
 * 않고, M-10 의 공개 조회 테스트가 쓰는 291~294 와도 겹치지 않는다. ADMIN seed 의
 * 닉네임({@code 관리자})과도 다른 값을 쓴다 (sp-docs/requirements/member.md §10.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalMemberControllerTest {

	private static final String PATH = "/internal/v1/members/bulk";

	/** sp-docs/security.md §6 이 정한 헤더 이름. */
	private static final String KEY_HEADER = "X-Internal-Api-Key";

	/** application-test.yml 이 주는 테스트 전용 키. 실제 키가 아니다. */
	private static final String TEST_KEY = "test-internal-api-key";

	/** {@code V2__seed_admin_profile.sql} 의 프로필 (sp-docs/requirements/member.md §10.1). */
	private static final Long SEED_ACCOUNT_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * <b>이름으로 집는다.</b> 타입 후보가 둘이다 — actuator 가
	 * {@code controllerEndpointHandlerMapping} 을 같은 타입으로 등록한다(측정값). 업무·내부 경로의
	 * 매핑을 가진 쪽은 {@code requestMappingHandlerMapping} 이다.
	 */
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	/**
	 * <b>seed 행은 남긴다.</b> H2 는 테스트 클래스 사이에 공유되므로
	 * ({@code DB_CLOSE_DELAY=-1}, sp-docs/tech-stack.md §5.3) 여기서 지우면
	 * {@code AdminProfileSeedTest} 가 자기와 무관한 이유로 깨진다.
	 */
	@AfterEach
	void 정리() {
		this.memberRepository.deleteAll(this.memberRepository.findAll().stream()
				.filter(profile -> !SEED_ACCOUNT_ID.equals(profile.getAccountId()))
				.toList());
	}

	/**
	 * 검증표: <b>정상 벌크 조회 (3건) → 200, 3건 반환</b> (sp-docs/api-contract.md §5).
	 *
	 * <p>요청 키가 {@code accountIds}, 응답 키가 {@code accountId} 임을 함께 본다 (§5).
	 *
	 * <p><b>순서를 단언하지 않는다.</b> 요청 순서와 응답 순서를 맞추지 않는 것이 계약이고 소비
	 * 측이 {@code accountId} 로 매칭한다 (§5). 그래서 {@code accountId} 집합으로 본다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 요청 키를 {@code accountId} 로 받는 구현은 역직렬화에서
	 * {@code accountIds} 가 {@code null} 이 되어 400 으로 빨개진다.
	 */
	@Test
	@DisplayName("정상 벌크 조회(3건)는 200 이고 3건을 돌려준다")
	void 정상_벌크_조회() throws Exception {
		프로필(295L, "요약하나");
		프로필(296L, "요약둘");
		프로필(297L, "요약셋");

		String 본문 = this.mockMvc.perform(벌크("""
						{"accountIds": [295, 296, 297]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.members", hasSize(3)))
				.andExpect(jsonPath("$.error").doesNotExist())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<List<Integer>>read(본문, "$.data.members[*].accountId"))
				.containsExactlyInAnyOrder(295, 296, 297);
		assertThat(JsonPath.<List<String>>read(본문, "$.data.members[*].nickname"))
				.containsExactlyInAnyOrder("요약하나", "요약둘", "요약셋");
	}

	/**
	 * 검증표: <b>벌크 1건 요청 → 200, 1건 반환</b> (sp-docs/api-contract.md §5).
	 *
	 * <p><b>이 케이스가 지키는 것</b>: board 가 1건만 필요해도 벌크에 1건을 담는다는 계약이다 —
	 * 단건 전용 API 를 만들지 않는 이유가 실패 의미론을 하나로 유지하기 위해서다 (§5).
	 */
	@Test
	@DisplayName("벌크 1건 요청은 200 이고 1건을 돌려준다")
	void 벌크_1건() throws Exception {
		프로필(298L, "한건만");

		this.mockMvc.perform(벌크("""
						{"accountIds": [298]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(298))
				.andExpect(jsonPath("$.data.members[0].nickname").value("한건만"))
				.andExpect(jsonPath("$.data.members[0].deleted").value(false));
	}

	/**
	 * 검증표: <b>존재하지 않는 id 포함 → 200. 해당 id 는 결과에서 제외. 404 아님</b>
	 * (sp-docs/api-contract.md §5).
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 없는 {@code accountId} 를 404 로 내는 구현은 여기서
	 * 빨개진다. board 가 그 404 를 "프로필 없음" 으로 해석하면 <b>경로 오설정이 업무 오류로
	 * 위장되어 전 사용자의 쓰기가 조용히 멈춘다</b> (§5.1). 빈 자리를 {@code null} 로 채워
	 * 건수를 맞추는 구현도 건수 단언에서 빨개진다.
	 */
	@Test
	@DisplayName("존재하지 않는 id 가 섞이면 200 이고 그 id 만 결과에서 빠진다")
	void 존재하지_않는_id_포함() throws Exception {
		프로필(299L, "실재하는이");
		assertThat(this.memberRepository.findByAccountId(289L))
				.as("이 accountId 로는 프로필을 만들지 않았다")
				.isEmpty();

		String 본문 = this.mockMvc.perform(벌크("""
						{"accountIds": [299, 289]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(1)))
				.andExpect(jsonPath("$.data.members[0].accountId").value(299))
				.andExpect(jsonPath("$.error").doesNotExist())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<List<Integer>>read(본문, "$.data.members[*].accountId"))
				.doesNotContain(289);
	}

	/**
	 * 검증표: <b>전부 존재하지 않는 id → 200, 빈 배열. 404 아님</b>
	 * (sp-docs/api-contract.md §5).
	 */
	@Test
	@DisplayName("전부 존재하지 않는 id 면 200 과 빈 배열이다")
	void 전부_존재하지_않는_id() throws Exception {
		this.mockMvc.perform(벌크("""
						{"accountIds": [289, 299]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.members", hasSize(0)))
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	/**
	 * 검증표: <b>{@code accountIds} 누락·{@code null} → 400 {@code C001}</b>
	 * (sp-docs/api-contract.md §5). <b>호출 측의 결함이므로 503 {@code S001} 경보로 올라가는 것이
	 * 맞다</b> (§5.1).
	 *
	 * <p>{@code fieldErrors} 에 {@code accountIds} 가 담기는 것까지 본다 — 어느 필드가 잘못됐는지
	 * 없으면 호출 측이 원인을 찾을 수 없다.
	 */
	@ParameterizedTest(name = "body={0}")
	@ValueSource(strings = {"{}", "{\"accountIds\": null}"})
	@DisplayName("accountIds 누락·null 은 400 C001 이고 fieldErrors 에 accountIds 가 담긴다")
	void accountIds_누락(String body) throws Exception {
		this.mockMvc.perform(벌크(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("C001"))
				.andExpect(jsonPath("$.error.fieldErrors", hasSize(1)))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("accountIds"))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	/**
	 * 검증표: <b>{@code accountIds} 빈 배열 → 200, 빈 배열. 400 이 아니다</b>
	 * (sp-docs/api-contract.md §5).
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: {@code @NotEmpty} 를 쓴 구현은 {@code null} 과 빈 배열을
	 * 같게 보므로 여기서 400 을 내며 빨개진다. 그것이 운영에서 뜻하는 것은 §5.1 에 따라
	 * <b>해석할 {@code accountId} 가 없는 정상 상황이 503 인프라 경보가 되는 것</b>이다. 위의
	 * {@code accountIds_누락} 과 짝으로 봐야 {@code @NotNull} 과 {@code @NotEmpty} 가 갈린다.
	 */
	@Test
	@DisplayName("accountIds 빈 배열은 200 과 빈 배열이다 — 400 이 아니다")
	void accountIds_빈_배열() throws Exception {
		this.mockMvc.perform(벌크("""
						{"accountIds": []}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.members", hasSize(0)))
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	/**
	 * 검증표: <b>탈퇴 프로필 포함 → {@code deleted: true}, 닉네임 {@code "탈퇴한 회원"}</b>,
	 * 그리고 <b>탈퇴 프로필의 DB 값은 {@code nickname IS NULL}</b>
	 * (sp-docs/api-contract.md §5, sp-docs/domain-model.md §3.1).
	 *
	 * <p>활성 프로필을 함께 담아 둘을 가른다 — 늘 마스킹하는 구현과 가르는 자리다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 마스킹을 <b>저장</b>으로 바꾼 구현은 DB 단언에서
	 * 빨개진다. 탈퇴 프로필을 조회에서 제외하는 구현은 건수에서 빨개진다 — 제외하면 board 가
	 * 탈퇴(403 {@code S002})와 미등록을 구별할 근거를 잃는다 (§5.1).
	 */
	@Test
	@DisplayName("탈퇴 프로필은 deleted true 와 마스킹된 닉네임으로 나가고 DB 는 NULL 이다")
	void 탈퇴_프로필_포함() throws Exception {
		프로필(295L, "살아있는이");
		탈퇴시킨다(프로필(296L, "탈퇴하는이"));

		String 본문 = this.mockMvc.perform(벌크("""
						{"accountIds": [295, 296]}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.members", hasSize(2)))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(요약(본문, 295))
				.containsEntry("nickname", "살아있는이")
				.containsEntry("deleted", false);
		assertThat(요약(본문, 296))
				.containsEntry("nickname", "탈퇴한 회원")
				.containsEntry("deleted", true);

		assertThat(this.jdbcTemplate
				.queryForMap("select * from member where account_id = ?", 296L)
				.get("nickname"))
				.as("마스킹 문자열은 응답 시 변환이지 저장이 아니다 (sp-docs/domain-model.md §3.1)")
				.isNull();
	}

	/**
	 * 검증표: <b>응답 본문에 {@code email}·{@code password} 키가 없다</b>
	 * (sp-docs/api-contract.md §5, sp-docs/requirements/member.md §9 — member 는 애초에 갖고
	 * 있지 않다).
	 *
	 * <p><b>키마다 {@code doesNotExist} 를 쓰지 않는다.</b> 요약의 키 집합 자체를 단언하면 새로
	 * 늘어난 키도 잡힌다 — §5 의 예시는 {@code accountId}·{@code nickname}·{@code deleted} 셋이고
	 * <b>{@code createdAt} 이 없다.</b> 봉투까지 함께 본다 (sp-docs/api-contract.md §7).
	 */
	@Test
	@DisplayName("요약의 키 집합은 accountId·nickname·deleted 셋이고 email·password 가 없다")
	void 응답_키_집합() throws Exception {
		프로필(297L, "키집합확인");

		String 본문 = this.mockMvc.perform(벌크("""
						{"accountIds": [297]}"""))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<Map<String, Object>>read(본문, "$.data.members[0]"))
				.as("내부 API 의 요약은 세 키다 — createdAt 도 없다 (sp-docs/api-contract.md §5)")
				.containsOnlyKeys("accountId", "nickname", "deleted");
		assertThat(JsonPath.<Map<String, Object>>read(본문, "$.data"))
				.containsOnlyKeys("members");
		assertThat(JsonPath.<Map<String, Object>>read(본문, "$"))
				.containsOnlyKeys("success", "data", "error");
	}

	/**
	 * 검증표: <b>API 키 없이 / 잘못된 키 → 401 또는 403</b>
	 * (sp-docs/plan/phase1.md §5 M-10, sp-docs/security.md §6). 이 구현은 403 이다 — 이유는
	 * {@link com.example.member.global.security.InternalApiKeyFilter} 에 있다.
	 *
	 * <p><b>무엇을 깨뜨릴 수 있는지</b>: 필터를 등록하지 않았거나 체인을 잘못 고른 구현은 200 을
	 * 내며 빨개진다. 요청 본문은 유효하므로 거부한 것은 키 검증이다. 접두·접미가 한 글자 다른
	 * 값을 함께 보는 것은 비교를 {@code startsWith} 나 "비어 있지 않음" 으로 느슨하게 만든 구현을
	 * 가르기 위해서다.
	 *
	 * <p><b>헤더 부재와 빈 값은 다른 입력이다.</b> 앞은 {@code getHeader} 가 {@code null} 을
	 * 돌려주고 뒤는 빈 문자열을 돌려주므로, 그 둘을 한 파라미터로 합치면 한쪽만 통과시키는
	 * 구현이 숨는다 — 설정 쪽에서도 같은 구분이 측정됐다("없을 때" 와 "비어 있을 때" 가 다르다,
	 * sp-docs/tech-stack.md §4.3). 아래 파라미터는 둘을 <b>따로</b> 보낸다: 첫 줄
	 * ({@code 헤더없음} → {@code null})은 헤더를 싣지 않고, 둘째 줄({@code ''})은 헤더를 싣되
	 * 값을 비운다.
	 *
	 * <p>컨트롤러에 닿지 않았음을 본문으로 함께 본다 — {@code data} 가 없다.
	 */
	@ParameterizedTest(name = "헤더=[{0}]")
	@CsvSource(nullValues = "헤더없음", value = {
			"헤더없음",
			"''",
			"wrong-key",
			"test-internal-api-ke",
			"test-internal-api-keyy"})
	@DisplayName("헤더가 없거나 값이 비었거나 다르면 403 이고 컨트롤러에 닿지 않는다")
	void 잘못된_API_키(String key) throws Exception {
		MockHttpServletRequestBuilder 요청 = post(PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"accountIds": [295]}""");
		// null 은 "헤더를 아예 싣지 않는다", "" 는 "헤더를 싣되 값이 비어 있다" 다
		if (key != null) {
			요청 = 요청.header(KEY_HEADER, key);
		}

		this.mockMvc.perform(요청)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("A004"))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	/**
	 * 검증표: <b>라우팅 테이블에 {@code GET /internal/v1/members/{id}} 가 없다</b>
	 * (sp-docs/plan/phase1.md §5 M-10). <b>벌크 하나만 둔다</b> — 1건만 필요한 호출도 벌크에
	 * 1건을 담아 실패 의미론을 하나로 유지한다 (sp-docs/api-contract.md §5).
	 *
	 * <p>특정 경로 하나를 금지하는 단언을 쓰지 않고 <b>{@code /internal} 아래의 매핑 전체</b>를
	 * 뽑아 비교한다 — 앞의 방식은 {@code /internal/v1/members/summary} 처럼 다른 이름의 단건
	 * API 를 통과시킨다.
	 */
	@Test
	@DisplayName("/internal 아래의 매핑은 POST /internal/v1/members/bulk 하나다")
	void 내부_라우팅_테이블() {
		List<String> internal = this.handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream()
						.filter(pattern -> pattern.startsWith("/internal"))
						.map(pattern -> info.getMethodsCondition().getMethods().stream()
								.map(Enum::name)
								.sorted()
								.toList() + " " + pattern))
				.sorted()
				.toList();

		assertThat(internal)
				.as("단건 전용 엔드포인트를 두지 않는다 (sp-docs/api-contract.md §5)")
				.containsExactly("[POST] /internal/v1/members/bulk");
	}

	private MockHttpServletRequestBuilder 벌크(String body) {
		return post(PATH)
				.header(KEY_HEADER, TEST_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private Member 프로필(long accountId, String nickname) {
		return this.memberRepository.saveAndFlush(Member.builder()
				.accountId(accountId)
				.nickname(nickname)
				.build());
	}

	/** 탈퇴 상태를 엔티티 메서드로 만든다. HTTP 경로({@code DELETE /me})는 M-09 가 본다. */
	private void 탈퇴시킨다(Member profile) {
		profile.withdraw();
		this.memberRepository.saveAndFlush(profile);
	}

	private static Map<String, Object> 요약(String body, int accountId) {
		List<Map<String, Object>> found =
				JsonPath.read(body, "$.data.members[?(@.accountId == " + accountId + ")]");
		assertThat(found)
				.as("accountId %d 의 요약이 결과에 있다", accountId)
				.hasSize(1);
		return found.get(0);
	}

}
