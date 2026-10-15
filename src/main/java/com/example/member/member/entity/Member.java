package com.example.member.member.entity;

import com.example.member.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프로필. 정본: sp-docs/domain-model.md §3.1.
 *
 * <p><b>member 는 프로필(닉네임)만 소유한다.</b> 이메일·비밀번호·권한은 auth-service 의
 * {@code sp_auth.account} 에 있으므로 여기에 두지 않는다
 * (sp-docs/adr/0012-auth-as-separate-service.md).
 *
 * <p><b>{@code accountId} 가 외부 식별자다.</b> {@code id} 는 외부에 노출하지 않는다.
 * {@code account.id} 를 논리 참조하지만 스키마 경계를 넘으므로 FK 를 두지 않는다.
 *
 * <p>프로필은 세 상태를 구분한다 — 미등록(행이 없다) / 활성({@code deleted = false}) /
 * 탈퇴({@code deleted = true}, {@code nickname} 은 NULL). 미등록과 탈퇴를 같은 "없음" 으로
 * 처리하면 탈퇴한 프로필이 재생성된다 (sp-docs/requirements/member.md §3 규칙 6).
 *
 * <p>권한은 여기 없다. JWT Claim 으로 전달되고 auth 가 소유한다 (sp-docs/api-contract.md §6).
 */
@Getter
@Entity
@Table(name = "member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

	/**
	 * 탈퇴한 프로필의 표시 이름. 정본: sp-docs/api-contract.md §3.1 §5.
	 *
	 * <p><b>저장되는 값이 아니다</b> ({@link #displayNickname()}).
	 */
	private static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private Long accountId;

	/**
	 * 표시 이름. <b>NULL 을 허용한다</b> — 탈퇴 시 비운다 ({@link #withdraw()}).
	 *
	 * <p>컬럼 폭 30 은 입력 규칙(2~10자)보다 넓은 의도된 여유다. 규칙이 넓어질 때 마이그레이션을
	 * 피한다 (sp-docs/domain-model.md §3.1). {@code validate} 는 이 폭을 보지 않으므로
	 * {@code MemberRepositoryTest} 가 {@code information_schema} 의 실제 폭과 대조한다.
	 */
	@Column(length = 30, unique = true)
	private String nickname;

	@Column(nullable = false)
	private boolean deleted;

	@Builder
	private Member(Long accountId, String nickname) {
		this.accountId = accountId;
		this.nickname = nickname;
		this.deleted = false;
	}

	public void updateNickname(String nickname) {
		this.nickname = nickname;
	}

	/**
	 * 탈퇴. 행은 남기고(Soft Delete) <b>{@code nickname} 을 NULL 로 비운다.</b>
	 *
	 * <p>값을 남기면 {@code uk_member_nickname} 이 UNIQUE 이므로 그 닉네임이 영구 소각되고,
	 * {@code "탈퇴한 회원"} 으로 마스킹해 <b>저장</b>하면 두 번째 탈퇴가 UNIQUE 위반으로 500 이
	 * 난다. 마스킹은 응답 시 변환이지 저장이 아니다 (sp-docs/domain-model.md §3.1,
	 * sp-docs/api-contract.md §3.1).
	 */
	public void withdraw() {
		this.deleted = true;
		this.nickname = null;
	}

	/** {@code deleted} 의 반대를 읽는다. */
	public boolean isActive() {
		return !this.deleted;
	}

	/**
	 * 응답에 실을 표시 이름. 탈퇴했으면 {@code "탈퇴한 회원"} 이다.
	 *
	 * <p><b>응답 시 변환이지 저장이 아니다.</b> 저장값은 NULL 이다 ({@link #withdraw()}) —
	 * 마스킹 문자열을 저장하면 {@code uk_member_nickname} 때문에 두 번째 탈퇴가 UNIQUE 위반으로
	 * 500 이 난다 (sp-docs/domain-model.md §3.1).
	 *
	 * <p><b>마스킹 규칙은 하나다.</b> 공개 조회({@code GET /api/v1/members/{accountId}})와 내부
	 * 벌크 API 가 같은 변환을 쓰며, 두 군데서 다르게 변환하지 않는다
	 * (sp-docs/api-contract.md §3.1 §5). 그래서 변환을 각 DTO 에 복제하지 않고 이 메서드에 둔다.
	 *
	 * <p>활성 프로필에서는 {@code nickname} 이 NOT NULL 이 아니라 "탈퇴 전이므로 값이 있다" 는
	 * 상태 불변식에 의존한다 — 컬럼은 NULL 을 허용하고 비우는 지점은 {@link #withdraw()} 뿐이다.
	 */
	public String displayNickname() {
		return this.deleted ? WITHDRAWN_NICKNAME : this.nickname;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Member other)) {
			return false;
		}
		return this.id != null && this.id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.id);
	}

}
