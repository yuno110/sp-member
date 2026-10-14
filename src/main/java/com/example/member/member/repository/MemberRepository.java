package com.example.member.member.repository;

import com.example.member.member.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

	/**
	 * 외부 식별자는 {@code accountId} 다. {@code member.id} 는 외부에 노출하지 않는다
	 * (sp-docs/domain-model.md §3.1).
	 *
	 * <p>탈퇴한 프로필의 행도 남아 있으므로 {@code deleted} 를 조건에 넣지 않는다. 미등록(행이
	 * 없다)과 탈퇴를 같은 "없음" 으로 만들면 탈퇴한 프로필이 재생성된다
	 * (sp-docs/requirements/member.md §3 규칙 6).
	 */
	Optional<Member> findByAccountId(Long accountId);

	/**
	 * 닉네임 선점 여부. {@code GET /members/check-nickname} 과 프로필 등록의 {@code M003}
	 * 판정이 쓴다 (sp-docs/api-contract.md §2.3 §3).
	 *
	 * <p><b>{@code deleted} 를 조건에 넣지 않는다.</b> 탈퇴 시 {@code nickname} 이 NULL 로
	 * 비워지므로 탈퇴한 프로필은 어떤 닉네임에도 걸리지 않는다 — 해방된 닉네임이 다시 쓸 수
	 * 있게 되는 것은 특별한 분기가 아니라 그 결과다 (sp-docs/api-contract.md §2.3,
	 * sp-docs/domain-model.md §3.1). 조건을 넣으면 같은 답을 내면서 "탈퇴를 따로 다룬다"는
	 * 잘못된 인상을 남긴다.
	 *
	 * <p>이 조회는 경합을 막지 못한다. 최종 판정은 {@code uk_member_nickname} 이다 (§2.3).
	 */
	boolean existsByNickname(String nickname);

}
