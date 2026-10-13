package com.example.member.member.support;

import com.example.member.global.error.BusinessException;
import com.example.member.global.error.ErrorCode;
import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 프로필 조회의 "없으면 예외" 지점을 한곳에 둔다.
 *
 * <p>{@code M001}(404, 프로필을 찾을 수 없습니다) 은 sp-docs/api-contract.md §8.2 가 정본이다.
 * 이 코드는 오류가 아니라 <b>"가입 3단계가 아직"</b> 이라는 뜻으로도 쓴다 (§3).
 *
 * <p><b>탈퇴 여부는 보지 않는다.</b> 탈퇴한 프로필도 행은 존재하므로 조회는 성공한다. 여기서
 * 탈퇴를 "없음" 으로 바꾸면 미등록과 구별되지 않고, §3 이 둘에 다른 응답을 배정한 것이 무너진다
 * — 프로필 등록은 탈퇴한 프로필에 409 {@code M007} 이고, {@code GET /api/v1/members/{accountId}}
 * 는 탈퇴한 프로필에 200 과 {@code deleted: true} 다 (§3.1). 상태별 결론은 호출하는 쪽이 정한다.
 */
@Component
@RequiredArgsConstructor
public class MemberReader {

	private final MemberRepository memberRepository;

	public Member getByAccountId(Long accountId) {
		return memberRepository.findByAccountId(accountId)
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
	}

}
