package com.example.member.member.controller;

import com.example.member.global.common.ApiResponse;
import com.example.member.global.security.CurrentMember;
import com.example.member.global.security.LoginMember;
import com.example.member.member.dto.CheckResponse;
import com.example.member.member.dto.MemberResponse;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.dto.ProfileRegistration;
import com.example.member.member.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

}
