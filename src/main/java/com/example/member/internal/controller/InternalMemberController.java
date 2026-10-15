package com.example.member.internal.controller;

import com.example.member.global.common.ApiResponse;
import com.example.member.internal.dto.MemberBulkRequest;
import com.example.member.internal.dto.MemberBulkResponse;
import com.example.member.internal.service.InternalMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 API. 정본: sp-docs/api-contract.md §5. <b>1차부터 board 가 호출한다</b> — 글·댓글
 * 생성 시 작성자 닉네임 스냅샷을 얻기 위해서다 (sp-docs/requirements/member.md §9).
 *
 * <p>인증은 {@code X-Internal-Api-Key} 헤더이고
 * {@link com.example.member.global.security.InternalApiKeyFilter} 가 검증한다
 * (sp-docs/security.md §6). 이 경로는 M-04 의 {@code SecurityConfig} 가 외부 인증 체인에서
 * 분리해 두었으므로 Bearer 토큰 필터를 지나지 않는다 (sp-docs/security.md §5.1.1).
 *
 * <p><b>벌크 하나뿐이다. 단건 전용 엔드포인트를 두지 않는다</b> (§5) — board 가 1건만 필요해도
 * 벌크에 1건을 담는다. 실패 의미론을 하나로 유지하기 위해서다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/members")
public class InternalMemberController {

	private final InternalMemberService internalMemberService;

	/**
	 * 프로필 요약 벌크 조회. 200 과 {@code data.members} (sp-docs/api-contract.md §5).
	 *
	 * <p><b>요청 키는 {@code accountIds}, 응답 키는 {@code accountId} 다</b> (§5).
	 *
	 * <p>응답 코드의 경계가 좁다 — §5.1 이 member 의 <b>모든 4xx·5xx·타임아웃</b>을 board 에서
	 * 503 {@code S001} 인프라 경보로 분류하기 때문이다.
	 *
	 * <ul>
	 * <li>{@code accountIds} 누락·{@code null} → 400 {@code C001}. <b>호출 측의 결함이므로 경보가
	 * 맞다</b> ({@link MemberBulkRequest} 의 {@code @NotNull})
	 * <li>빈 배열 → <b>200 과 빈 {@code members}</b>. 400 이 아니다
	 * <li>존재하지 않는 {@code accountId} → <b>결과에서 제외.</b> 404 가 아니다
	 * <li>탈퇴한 프로필 → {@code deleted: true}, 닉네임 {@code "탈퇴한 회원"} (저장값은 NULL)
	 * </ul>
	 */
	@PostMapping("/bulk")
	public ApiResponse<MemberBulkResponse> bulk(@Valid @RequestBody MemberBulkRequest request) {
		return ApiResponse.success(this.internalMemberService.findSummaries(request.accountIds()));
	}

}
