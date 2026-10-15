package com.example.member.internal.dto;

import java.util.List;

/**
 * 내부 API 의 벌크 조회 응답 본문. 정본: sp-docs/api-contract.md §5 —
 * {@code data} 아래의 키가 {@code members} 다.
 *
 * <p><b>요청 순서와 응답 순서를 맞추지 않는다.</b> 소비 측이 {@code accountId} 로 매칭한다 (§5).
 *
 * <p>존재하지 않는 {@code accountId} 는 이 목록에서 <b>빠진다</b>. 404 가 아니다 — board 가
 * 404 를 "프로필 없음" 으로 해석하면 경로 오설정이 업무 오류로 위장된다 (§5.1).
 */
public record MemberBulkResponse(List<MemberSummaryResponse> members) {

}
