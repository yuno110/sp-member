package com.example.member.member.repository;

import com.example.member.member.entity.Member;
import java.util.Collection;
import java.util.List;
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
	 * 내부 벌크 조회가 쓴다 ({@code POST /internal/v1/members/bulk},
	 * sp-docs/api-contract.md §5).
	 *
	 * <p><b>존재하지 않는 {@code accountId} 는 결과에서 빠진다.</b> {@code IN} 조회가 그 자체로
	 * 그렇게 동작하므로 "없음" 을 404 로 바꾸는 분기를 두지 않는다 — board 가 404 를 "프로필
	 * 없음" 으로 해석하면 경로 오설정이 업무 오류로 위장된다 (§5.1).
	 *
	 * <p><b>{@code deleted} 를 조건에 넣지 않는다.</b> 탈퇴한 프로필도 {@code deleted: true} 와
	 * 닉네임 {@code "탈퇴한 회원"} 으로 <b>반환해야 한다</b> (§5) — 조건에 넣으면 탈퇴가 미등록과
	 * 구별되지 않아 board 가 두 상태를 같게 본다 (§5.1 은 둘에 같은 403 {@code S002} 를 주지만
	 * 그것은 board 의 결론이고 member 가 미리 합치는 것이 아니다).
	 */
	List<Member> findByAccountIdIn(Collection<Long> accountIds);

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

	/**
	 * <b>다른 계정이 이 닉네임을 쥐고 있는가.</b> 닉네임 변경의 사전 검사가 쓴다 —
	 * {@code PATCH /api/v1/members/me} 는 <b>자기 행을 제외하고 검사하며 그 기준이
	 * {@code accountId} 다</b> (sp-docs/requirements/member.md §7).
	 *
	 * <p><b>값 동일성으로 제외할 수 없어서 이 조회가 필요하다.</b> 세 스키마의 콜레이션이
	 * {@code utf8mb4_general_ci} 여서 (sp-docs/tech-stack.md §4.1) 대소문자를 구분하지 않으므로,
	 * {@code abc} 를 쥔 사람이 {@code ABC} 로 바꾸면 요청값과 현재 닉네임은 다른데 조회는 자기
	 * 행을 찾는다 — {@code uk_member_nickname} 은 같은 행이므로 그 UPDATE 를 허용하는데 사전
	 * 검사만 409 {@code M003} 을 낸다. {@code accountId} 로 제외하면 콜레이션과 무관해진다.
	 *
	 * <p>{@code deleted} 를 조건에 넣지 않는 이유는 {@link #existsByNickname} 과 같다 — 탈퇴한
	 * 프로필은 {@code nickname} 이 NULL 이라 어떤 닉네임에도 걸리지 않는다.
	 *
	 * <p>이 조회도 경합을 막지 못한다. 최종 판정은 {@code uk_member_nickname} 이고 그것을 밟았을
	 * 때의 결론은 {@code MemberService.updateNickname} 에 있다 (§7).
	 */
	boolean existsByNicknameAndAccountIdNot(String nickname, Long accountId);

}
