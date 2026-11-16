package com.example.member.member.controller;

import com.example.member.global.common.ApiResponse;
import com.example.member.global.security.CurrentMember;
import com.example.member.global.security.LoginMember;
import com.example.member.member.dto.CheckResponse;
import com.example.member.member.dto.MemberProfileResponse;
import com.example.member.member.dto.MemberProfilesResponse;
import com.example.member.member.dto.MemberResponse;
import com.example.member.member.dto.NicknameUpdateRequest;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.dto.ProfileRegistration;
import com.example.member.member.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 API. 정본: sp-docs/api-contract.md §3.
 *
 * <p>M-05 가 만들고 M-08·M-09·M-10 이 확장한다 (sp-docs/plan/phase1.md §2.3). 2차의 M-16 이 프로필
 * 일괄 조회(ADMIN)를 더했다 (sp-docs/plan/phase2-auth-member.md).
 *
 * <p>경로별 인가는 M-04 의 {@code SecurityConfig} 가 선반영했다 —
 * {@code POST /api/v1/members} 는 인증이고 {@code GET /members/check-nickname} 은 무인증이다
 * (sp-docs/api-contract.md §3 표의 "인증" 열). <b>업무 경로의 정본은 그 표다</b> —
 * sp-docs/security.md §5.1.1 은 인프라 경로({@code /swagger-ui/**}·{@code /v3/api-docs/**}·
 * {@code /actuator/**}·{@code /internal/**})만 정한다. {@code SecurityConfig} 가 그 구분을
 * 적어 두었다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberController {

	/** sp-docs/api-contract.md §3.2 가 정한 {@code accountIds} 개수의 하한이다. */
	private static final int MIN_ACCOUNT_IDS = 1;

	/** 같은 절의 상한이다. 계정 목록(§2.5)의 최대 {@code size} 와 같은 값이라고 §3.2 가 적는다. */
	private static final int MAX_ACCOUNT_IDS = 50;

	private static final String ACCOUNT_IDS_SIZE_MESSAGE = "accountIds 는 {min}~{max}개여야 합니다.";

	private static final String ACCOUNT_IDS_ELEMENT_MESSAGE = "accountIds 에 빈 값을 넣을 수 없습니다.";

	private final MemberService memberService;

	/**
	 * 가입 3단계 — 프로필 등록 (sp-docs/api-contract.md §9.1).
	 *
	 * <p><b>인증이 필요하다.</b> 저장되는 {@code account_id} 는 검증된 Access Token 의
	 * {@code sub} 가 정한다 — 본문에 다른 식별자를 실어 보내도 무시된다 (§3,
	 * sp-docs/security.md §4). 토큰 없는 접근은 Security 필터 체인이 401 {@code A001} 로
	 * 끊는다 (sp-docs/api-contract.md §8.5).
	 *
	 * <p><b>상태 코드가 결과에 따라 갈리므로 {@code @ResponseStatus} 를 쓰지 않는다</b> — 행을
	 * 만들었으면 201 이고, 활성 프로필이 이미 있고 닉네임이 같으면 200 이다 (§3 의 표). 본문은
	 * 어느 쪽이든 같으므로({@link MemberResponse}) 서비스가 알려주는
	 * {@link ProfileRegistration#created()} 가 그 갈림을 전달한다.
	 *
	 * <p>닉네임 위반은 400 {@code C001} 이고 {@code fieldErrors} 에 {@code nickname} 이 담긴다
	 * ({@code GlobalExceptionHandler}), 타인이 쓰는 닉네임은 409 {@code M003}, 닉네임이 다른
	 * 재등록은 409 {@code M006}, 탈퇴한 프로필은 409 {@code M007} 이다
	 * (sp-docs/api-contract.md §8.2).
	 */
	@PostMapping
	public ResponseEntity<ApiResponse<MemberResponse>> register(
			@CurrentMember LoginMember loginMember,
			@Valid @RequestBody ProfileCreateRequest request) {

		ProfileRegistration registration = this.memberService.register(loginMember.accountId(), request);

		return ResponseEntity
				.status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(ApiResponse.success(registration.profile()));
	}

	/**
	 * MR-03 — 닉네임 중복 확인. 200 과 {@code {available}} (sp-docs/api-contract.md §2.3).
	 *
	 * <p><b>파라미터에 등록과 같은 닉네임 규칙을 적용한다</b> (§2.3). 형식 오류에
	 * {@code available: true} 를 돌려주면 쓸 수 없는 값을 쓸 수 있다고 답하는 셈이고,
	 * 클라이언트가 등록에서 400 을 받아 같은 판정을 두 번 하게 된다.
	 *
	 * <p>규칙은 {@link ProfileCreateRequest} 의 상수를 그대로 쓴다 — 두 군데 적지 않기
	 * 위해서다. <b>누락</b>은 {@code @RequestParam} 이 기본으로 필수이므로
	 * {@code MissingServletRequestParameterException} 이 되고 {@code fieldErrors} 가 빈
	 * 배열인 400 {@code C001} 이다. 빈 값·형식 오류·길이 초과는 메서드 검증이
	 * {@code HandlerMethodValidationException} 으로 걸러 {@code nickname} 1건을 담는다
	 * (둘 다 {@code GlobalExceptionHandler} 가 {@code C001} 로 변환한다).
	 */
	@GetMapping("/check-nickname")
	public ApiResponse<CheckResponse> checkNickname(
			@RequestParam
			@Pattern(regexp = ProfileCreateRequest.NICKNAME_PATTERN,
					message = ProfileCreateRequest.NICKNAME_MESSAGE)
			String nickname) {

		return ApiResponse.success(this.memberService.checkNickname(nickname));
	}

	/**
	 * MR-07 의 프로필 절반 — 내 프로필 조회. 200 과 {@code {accountId, nickname}}
	 * (sp-docs/api-contract.md §3.1).
	 *
	 * <p><b>이메일·가입일이 없다.</b> 이메일은 member 가 소유하지 않고, 가입일을 함께 주는 것은
	 * {@code GET /api/v1/members/{accountId}} 다 (§3.1, sp-docs/requirements/member.md §5). 화면
	 * 하나에 이메일까지 필요하면 클라이언트가 auth 의 {@code GET /api/v1/accounts/me} 를 따로
	 * 부른다 — member 가 auth 를 호출해 합치지 않는다 (sp-docs/adr/0012 §1).
	 *
	 * <p><b>조회 대상은 검증된 토큰의 {@code sub} 가 정한다.</b> 경로에 식별자가 없으므로 타인의
	 * 프로필이 이 경로로 나갈 수 없다 (sp-docs/security.md §4).
	 *
	 * <p>프로필이 없으면 404 {@code M001} 이다 — <b>오류가 아니라 가입 3단계 미완료</b>를 뜻하고
	 * 클라이언트는 프로필 등록으로 안내한다 (sp-docs/api-contract.md §3,
	 * sp-docs/requirements/member.md §5). <b>탈퇴한 프로필도 같은 404 {@code M001}</b> 이다
	 * (§3.1 의 표). 이 응답으로는 둘을 가를 수 없다 — 가르는 것은 공개 조회의 {@code deleted} 다.
	 */
	@GetMapping("/me")
	public ApiResponse<MemberResponse> getMyProfile(@CurrentMember LoginMember loginMember) {
		return ApiResponse.success(this.memberService.getMyProfile(loginMember.accountId()));
	}

	/**
	 * MR-08 — 닉네임 수정. 200 과 <b>갱신된 프로필</b>이고 응답 모양은 {@code GET /me} 와 같다
	 * (sp-docs/api-contract.md §3.1, sp-docs/requirements/member.md §7).
	 *
	 * <p><b>응답에 토큰이 없다.</b> Claim 에 {@code nickname} 이 없어 토큰이 낡을 이유가 없고
	 * (sp-docs/api-contract.md §6), member 는 개인키를 갖지 않으므로 발급할 수도 없다
	 * (sp-docs/requirements/member.md §7).
	 *
	 * <p>타인이 쓰는 닉네임이면 409 {@code M003} 이고, <b>자기 현재 닉네임이면 200</b> 이다 —
	 * 중복 검사가 자기 행을 제외한다 (§7). 닉네임 규칙 위반은 400 {@code C001} 이고
	 * {@code fieldErrors} 에 {@code nickname} 이 담긴다 ({@link NicknameUpdateRequest}).
	 * 프로필이 없거나 <b>탈퇴했으면 404 {@code M001}</b> 이다 (sp-docs/api-contract.md §3.1 의 표).
	 */
	@PatchMapping("/me")
	public ApiResponse<MemberResponse> updateNickname(
			@CurrentMember LoginMember loginMember,
			@Valid @RequestBody NicknameUpdateRequest request) {

		return ApiResponse.success(this.memberService.updateNickname(loginMember.accountId(), request));
	}

	/**
	 * MR-10 의 2단계 — 프로필 탈퇴. <b>204, 본문 없음</b> (sp-docs/api-contract.md §3 의 표,
	 * sp-docs/requirements/member.md §8).
	 *
	 * <p><b>멱등이다. 이미 탈퇴한 프로필에 다시 호출해도 204</b> 다 — §3.1 의 표에서
	 * {@code DELETE /me} 는 404 가 아닌 유일한 {@code /me} 행이고, auth 의
	 * {@code DELETE /accounts/me} 가 같은 예외다 (§2.2). 프로필 행이 <b>아예 없으면</b>
	 * 404 {@code M001} 이다 ({@link MemberService#withdraw} 의 근거).
	 *
	 * <p><b>본문을 받지 않는다 — 비밀번호를 포함해서다.</b> member 는 비밀번호를 갖지 않으며
	 * 재확인은 1단계인 auth 의 {@code DELETE /api/v1/accounts/me} 에서 끝났다
	 * (sp-docs/requirements/member.md §8). 그래서 {@code @RequestBody} 도
	 * {@code @Valid} 도 없다.
	 *
	 * <p><b>계정 상태를 확인하지 않는다.</b> member 는 {@code sp_auth} 를 조회할 수 없고 auth 를
	 * 호출하지 않는다 (sp-docs/architecture.md §5). 2단계는 1단계와 같은 로그인의 Access Token 으로
	 * 수행되며 토큰 서명이 유효하면 통과한다 (sp-docs/requirements/member.md §8).
	 *
	 * <p>탈퇴 대상은 검증된 토큰의 {@code sub} 가 정한다 — 경로에 식별자가 없으므로 타인의
	 * 프로필이 이 경로로 탈퇴될 수 없다 (sp-docs/security.md §4). 토큰 없는 접근은 Security
	 * 필터 체인이 401 {@code A001} 로 끊는다 (sp-docs/api-contract.md §8.5).
	 */
	@DeleteMapping("/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw(@CurrentMember LoginMember loginMember) {
		this.memberService.withdraw(loginMember.accountId());
	}

	/**
	 * MR-11 — 특정 회원 프로필 조회. 200 과
	 * {@code {accountId, nickname, createdAt, deleted}} 이고 <b>무인증</b>이다
	 * (sp-docs/api-contract.md §3 의 "인증" 열, §3.1).
	 *
	 * <p><b>경로 변수가 {@code accountId} 다.</b> {@code member.id} 를 외부에 노출하지 않는다
	 * (§3, sp-docs/domain-model.md §3.1).
	 *
	 * <p><b>{@code GET /me} 가 이 매핑보다 먼저 매칭돼야 한다.</b> {@code me} 가 경로 변수로
	 * 먹히면 숫자 파싱이 실패해 엉뚱한 오류가 난다. 리터럴 세그먼트가 변수보다 구체적이라는
	 * {@code PathPattern} 의 우선순위가 그것을 정하지만 <b>그 동작에 기대고 끝내지 않고</b>
	 * {@code MemberControllerTest} 가 두 경로를 함께 보는 테스트로 고정한다. 인가 쪽 선언 순서는
	 * {@code SecurityConfig} 와 {@code SecurityConfigTest} 가 따로 고정한다.
	 *
	 * <p><b>{@link MemberResponse} 가 아니라 {@link MemberProfileResponse} 다</b> — 두 조회가
	 * 다른 모양인 것이 계약이다 (§3.1).
	 *
	 * <p>탈퇴한 프로필은 <b>200</b> 과 {@code deleted: true}, 닉네임 {@code "탈퇴한 회원"} 이고,
	 * 프로필이 <b>아예 없으면</b> 404 {@code M001} 이다 (§3.1 의 탈퇴 상태 표,
	 * {@link MemberService#getProfile}). 뒤쪽 404 는 이 경로까지다 — 내부 API 는 404 를 쓰지
	 * 않는다 (§5.1).
	 */
	@GetMapping("/{accountId}")
	public ApiResponse<MemberProfileResponse> getProfile(@PathVariable Long accountId) {
		return ApiResponse.success(this.memberService.getProfile(accountId));
	}

	/**
	 * MR-13 의 프로필 쪽 — 프로필 일괄 조회 (ADMIN). 200 과 {@code {members: [...]}}
	 * (sp-docs/api-contract.md §3.2, sp-docs/requirements/member.md §13).
	 *
	 * <p><b>{@code accountIds} 는 쉼표 목록과 같은 이름의 반복을 둘 다 받는다</b> (§3.2) —
	 * {@code accountIds=3,1} 도 {@code accountIds=3&accountIds=1} 도 같은 목록이 된다. 둘을 가르는
	 * 코드를 두지 않는다: 값이 하나면 쉼표로 나눠 변환하고 여럿이면 하나씩 변환하는 것이
	 * {@code List} 파라미터의 바인딩이다.
	 *
	 * <p><b>개수가 §3.2 의 범위 밖이면 400 {@code C001} 이고 {@code fieldErrors} 에
	 * {@code accountIds} 가 담긴다</b> ({@code @Size}). <b>절삭하지 않는다</b> — 잘린
	 * id 의 프로필이 조용히 빠지면 화면이 「프로필 없음」 과 구별하지 못한다. 세는 것은 보낸 값의
	 * 수다(되풀이한 id 도 센다). 빈 값({@code accountIds=})은 원소가 0개라 같은 400 이다.
	 *
	 * <p>개수 말고도 다음 입력을 400 으로 거부한다. 거부하는 단계는 입력마다 다르고, 예외를 코드로
	 * 바꾸는 것은 {@code GlobalExceptionHandler} 다 (sp-docs/conventions.md §6.1).
	 *
	 * <ul>
	 * <li><b>누락</b> — {@code @RequestParam} 이 기본으로 필수라 바인딩 단계가 거부한다. 400
	 * {@code C001} 이고 {@code fieldErrors} 는 빈 배열이다 (§3.2, §2.3)
	 * <li><b>숫자가 아닌 값</b>({@code accountIds=abc}) — 바인딩 단계의 변환이 실패해 400
	 * {@code C002} 다 (§3.2)
	 * <li><b>빈 원소</b>({@code accountIds=1,,2}) — 변환이 빈 문자열을 {@code null} 로 바꾸고, 원소의
	 * {@code @NotNull} 이 거부한다. 그 실패({@code HandlerMethodValidationException})가 400
	 * {@code C001} 이 되고 (sp-docs/conventions.md §6.1), {@code fieldErrors} 에는 빈 원소마다
	 * {@code accountIds} 1건이 담긴다({@code GlobalExceptionHandler}). 이 검사가 없으면 {@code null} 이
	 * 조회의 {@code IN} 에 실려 아무 행에도 걸리지 않고 200 으로 조용히 빠진다
	 * </ul>
	 *
	 * <p><b>ADMIN 판정은 {@link MemberService#getProfiles} 가 한다</b> — 아니면 403 {@code A004}
	 * (§3.2, sp-docs/security.md §5.1). 위 검증은 인자를 만드는 단계라 그 판정보다 먼저다. 토큰
	 * 없는 접근은 Security 필터 체인이 401 {@code A001} 로 끊는다 ({@code SecurityConfig} 가 이
	 * 경로를 인증으로 선언했다).
	 *
	 * <p><b>내부 API({@code /internal/**})를 쓰지 않는다</b> — 그것은 서비스 간 호출 전용이고 API
	 * 키로 보호된다. 사람의 요청은 JWT 로 판정한다 (§3.2).
	 */
	@GetMapping
	public ApiResponse<MemberProfilesResponse> getProfiles(
			@CurrentMember LoginMember loginMember,
			@RequestParam
			@Size(min = MIN_ACCOUNT_IDS, max = MAX_ACCOUNT_IDS, message = ACCOUNT_IDS_SIZE_MESSAGE)
			List<@NotNull(message = ACCOUNT_IDS_ELEMENT_MESSAGE) Long> accountIds) {

		return ApiResponse.success(this.memberService.getProfiles(loginMember, accountIds));
	}

}
