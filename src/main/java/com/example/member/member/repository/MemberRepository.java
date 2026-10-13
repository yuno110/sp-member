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

}
