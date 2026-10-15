package com.example.member.internal.service;

import com.example.member.internal.dto.MemberBulkResponse;
import com.example.member.internal.dto.MemberSummaryResponse;
import com.example.member.member.repository.MemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MR-12 — 내부 API 의 프로필 벌크 조회 (sp-docs/requirements/member.md §9). 요청·응답 형식은
 * sp-docs/api-contract.md §5 가 정본이다.
 *
 * <p><b>벌크 하나만 제공한다.</b> 1건만 필요한 호출도 벌크에 1건을 담는다 — 실패 의미론을 하나로
 * 유지하기 위해서다 (§5). 그래서 단건 조회 메서드를 두지 않는다.
 *
 * <p><b>"없음" 을 예외로 바꾸지 않는다.</b> 존재하지 않는 {@code accountId} 는 결과에서 빠지고,
 * 전부 없으면 빈 목록이다 — 404 나 빈 결과에 대한 분기가 없는 것이 계약이다. board 는 member 의
 * 모든 4xx·5xx·타임아웃을 503 {@code S001} <b>인프라 경보</b>로 분류하므로 (§5.1), 정상 상황을
 * 4xx 로 만들면 경로 오설정과 구별되지 않는 경보가 뜬다.
 *
 * <p>{@link com.example.member.member.support.MemberReader} 를 쓰지 않는다 — 그쪽은 "없으면
 * {@code M001}" 이고 그 결론은 클라이언트를 상대하는 경로의 것이다.
 */
@Service
@RequiredArgsConstructor
public class InternalMemberService {

	private final MemberRepository memberRepository;

	/**
	 * 요약 목록. <b>빈 배열 요청은 빈 목록이다</b> (sp-docs/api-contract.md §5).
	 *
	 * <p>순서를 요청과 맞추지 않는다 — 소비 측이 {@code accountId} 로 매칭한다 (§5).
	 *
	 * <p>탈퇴한 프로필의 닉네임 마스킹은 {@link MemberSummaryResponse} 가 하는 <b>응답 시
	 * 변환</b>이다. 저장값은 NULL 이다 (sp-docs/domain-model.md §3.1).
	 */
	@Transactional(readOnly = true)
	public MemberBulkResponse findSummaries(List<Long> accountIds) {
		return new MemberBulkResponse(this.memberRepository.findByAccountIdIn(accountIds).stream()
				.map(MemberSummaryResponse::from)
				.toList());
	}

}
