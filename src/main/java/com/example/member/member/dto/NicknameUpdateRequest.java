package com.example.member.member.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 닉네임 수정 요청. 정본: sp-docs/requirements/member.md §7, 검증 규칙은 §2 다.
 *
 * <p><b>필드는 {@code nickname} 하나다.</b> {@code accountId} 는 받지 않는다 — 고칠 대상은 검증된
 * JWT 의 {@code sub} 가 정한다 (sp-docs/security.md §4). 본문에 실어 보내도 Boot 의 기본 Jackson
 * 설정({@code FAIL_ON_UNKNOWN_PROPERTIES=false})이 무시한다.
 *
 * <p><b>검증 규칙은 {@link ProfileCreateRequest} 의 상수를 그대로 쓴다.</b> 등록과 수정이 같은
 * 닉네임 규칙을 쓰므로 (sp-docs/requirements/member.md §2 는 항목당 규칙을 하나로 정한다) 두
 * 군데 적지 않는다 — 한쪽만 고치면 등록이 거부하는 값을 수정이 통과시킨다.
 *
 * <p>검증 실패는 {@code C001}(400)이고 {@code fieldErrors} 에 {@code nickname} 이 담긴다
 * (sp-docs/api-contract.md §7).
 */
public record NicknameUpdateRequest(

		@NotNull(message = ProfileCreateRequest.NICKNAME_MESSAGE)
		@Pattern(regexp = ProfileCreateRequest.NICKNAME_PATTERN, message = ProfileCreateRequest.NICKNAME_MESSAGE)
		String nickname) { }
