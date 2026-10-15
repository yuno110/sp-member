package com.example.member.internal.dto;

import com.example.member.member.entity.Member;

/**
 * 내부 API 의 프로필 요약. 정본: sp-docs/api-contract.md §5 —
 * {@code {accountId, nickname, deleted}}.
 *
 * <p><b>{@code createdAt} 이 없다.</b> §5 의 응답 예시가 세 키만 담는다 — board 가 필요로 하는
 * 것은 작성자 닉네임 스냅샷이다 (sp-docs/requirements/member.md §9).
 *
 * <p><b>이메일·비밀번호가 없다.</b> member 는 애초에 갖고 있지 않다 (§9).
 *
 * <p>탈퇴한 프로필은 {@code deleted: true} 와 닉네임 {@code "탈퇴한 회원"} 이다 —
 * {@link Member#displayNickname()} 의 <b>응답 시 변환</b>이고 저장값은 NULL 이다. 공개 조회와
 * 같은 규칙을 쓴다 (sp-docs/api-contract.md §3.1 §5, sp-docs/domain-model.md §3.1).
 */
public record MemberSummaryResponse(Long accountId, String nickname, boolean deleted) {

	public static MemberSummaryResponse from(Member member) {
		return new MemberSummaryResponse(
				member.getAccountId(),
				member.displayNickname(),
				member.isDeleted());
	}

}
