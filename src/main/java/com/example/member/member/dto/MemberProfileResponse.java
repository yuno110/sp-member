package com.example.member.member.dto;

import com.example.member.member.entity.Member;
import java.time.LocalDateTime;

/**
 * 공개 프로필 응답. 정본: sp-docs/api-contract.md §3.1 —
 * {@code {accountId, nickname, createdAt, deleted}}.
 *
 * <p>{@code GET /api/v1/members/{accountId}} 가 쓴다. <b>{@link MemberResponse} 를 늘리지 않고
 * 따로 둔다</b> — 그쪽은 {@code POST /api/v1/members}·{@code GET /me}·{@code PATCH /me} 가 쓰는
 * {@code {accountId, nickname}} 으로 고정됐고 (§3.1), 두 조회가 다른 모양인 것이 계약이다.
 *
 * <p><b>이메일을 담지 않는다.</b> member 는 이메일을 소유하지 않으므로 알지 못한다
 * (sp-docs/adr/0012-auth-as-separate-service.md). {@code member.id} 도 담지 않는다 — 외부
 * 식별자는 {@code accountId} 다 (sp-docs/domain-model.md §3.1).
 *
 * <p><b>{@code createdAt} 은 {@code member.created_at}(프로필 등록 시각)이다.</b> MR-07 의
 * 가입일({@code account.created_at}, 계정 생성 시각)과 다른 값이며 가입이 3단계이므로 사이에
 * 로그인이 끼어 둘은 같지 않다 (§3.1). 타인의 계정 생성 시각은 auth 의
 * {@code GET /api/v1/accounts/me} 만 돌려주므로 이 경로로 나갈 수 없다.
 *
 * <p>직렬화 형태는 <b>오프셋 없는 ISO-8601 지역시각</b>이다. 타입이 {@code LocalDateTime} 이고
 * Boot 가 {@code WRITE_DATES_AS_TIMESTAMPS} 를 끄기 때문이며, <b>소수부는 저장값에 있으면 그대로
 * 나간다</b>. <b>감사가 만든 값에는 소수부가 없다</b> — {@code D-11} 이 초로 잘라 운영과
 * 테스트를 같게 했다 (sp-docs/api-contract.md §4.3). <b>그래도 고정 패턴을 강제하지 않는다</b>:
 * 마이그레이션·SQL 픽스처가 직접 넣은 행은 감사를 거치지 않아 소수부를 가질 수 있다.
 *
 * <p>탈퇴한 프로필의 {@code nickname} 은 {@code "탈퇴한 회원"} 이다 —
 * {@link Member#displayNickname()} 의 <b>응답 시 변환</b>이고 저장값은 NULL 이다
 * (sp-docs/domain-model.md §3.1).
 */
public record MemberProfileResponse(
		Long accountId,
		String nickname,
		LocalDateTime createdAt,
		boolean deleted) {

	public static MemberProfileResponse from(Member member) {
		return new MemberProfileResponse(
				member.getAccountId(),
				member.displayNickname(),
				member.getCreatedAt(),
				member.isDeleted());
	}

}
