package com.example.member.member.dto;

import java.util.List;

/**
 * 프로필 일괄 조회(ADMIN)의 응답. 정본: sp-docs/api-contract.md §3.2 —
 * {@code {"members": [...]}}.
 *
 * <p><b>원소는 {@link MemberProfileResponse} 를 그대로 쓴다</b> — §3.2 가 원소를
 * {@code GET /api/v1/members/{accountId}} 와 같은 모양으로 정했다. 원소 타입을 따로 만들면 두
 * 경로가 갈라질 자리가 생기고, 탈퇴 마스킹({@code "탈퇴한 회원"})도 그 타입의 변환을 따로
 * 거치게 된다 — §3.2 는 마스킹 규칙을 하나로 둔다.
 *
 * <p>원소의 순서는 {@code accountId} 오름차순이고 프로필이 없는 {@code accountId} 는 원소가
 * 없다 (§3.2). 그 결론은 조회가 낸다({@code MemberRepository#findByAccountIdInOrderByAccountIdAsc})
 * — 이 타입은 받은 순서대로 싣는다.
 */
public record MemberProfilesResponse(List<MemberProfileResponse> members) {

}
