package com.example.member.member.dto;

import com.example.member.member.entity.Member;

/**
 * 프로필 응답. 정본: sp-docs/api-contract.md §3.1 — {@code {accountId, nickname}}.
 *
 * <p>{@code POST /api/v1/members} 와 {@code GET /api/v1/members/me} 가 같은 모양을 쓴다
 * (§3.1, §9.1).
 *
 * <p><b>이메일·가입일을 담지 않는다.</b> MR-07 이 member 에 배정한 것은 닉네임뿐이고 이메일은
 * member 가 소유하지 않는다 (sp-docs/requirements/member.md §5). {@code member.id} 도 담지
 * 않는다 — 외부 식별자는 {@code accountId} 다 (sp-docs/domain-model.md §3.1).
 *
 * <p><b>{@code GET /api/v1/members/{accountId}} 는 다른 모양이다</b> —
 * {@code createdAt}·{@code deleted} 가 더 붙는다 (§3.1). 그쪽은 M-10 의 몫이고 이 record 를
 * 늘려 쓰지 않는다.
 */
public record MemberResponse(Long accountId, String nickname) {

	public static MemberResponse from(Member member) {
		return new MemberResponse(member.getAccountId(), member.getNickname());
	}

}
