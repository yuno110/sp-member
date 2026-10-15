package com.example.member.member.controller;

import com.example.member.global.common.ApiResponse;
import com.example.member.global.security.CurrentMember;
import com.example.member.global.security.LoginMember;
import com.example.member.member.dto.CheckResponse;
import com.example.member.member.dto.MemberResponse;
import com.example.member.member.dto.NicknameUpdateRequest;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.dto.ProfileRegistration;
import com.example.member.member.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 API. 정본: sp-docs/api-contract.md §3.
 *
 * <p>M-05 가 만들고 M-08·M-09·M-10 이 확장한다 (sp-docs/plan/phase1.md §2.3).
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
	 * 클라이언트는 프로필 등록 화면으로 보낸다 (sp-docs/api-contract.md §3,
	 * sp-docs/requirements/member.md §5). <b>탈퇴한 프로필도 같은 404 {@code M001}</b> 이다
	 * (§3.1 의 표).
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
	 * 호출하지 않는다 (sp-docs/architecture.md §5). 2단계는 1단계에서 쓰던 Access Token 으로
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

}
