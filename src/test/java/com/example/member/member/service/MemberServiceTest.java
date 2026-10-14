package com.example.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.member.global.error.BusinessException;
import com.example.member.global.error.ErrorCode;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.dto.ProfileRegistration;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;

import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * sp-docs/plan/phase1.md §5 M-05 의 "검증" 표 중 저장 쪽을 테스트로 옮긴 것이다. HTTP 상태·응답
 * 본문은 {@link com.example.member.member.controller.MemberControllerTest} 가 본다.
 *
 * <p>상태별 응답의 정본은 sp-docs/api-contract.md §3 의 표이고, 세 상태의 구분은
 * sp-docs/requirements/member.md §3 규칙 6, sp-docs/domain-model.md §3.1 이다.
 *
 * <p>리포지터리를 스텁으로 바꾸지 않고 H2 를 그대로 쓴다. 멱등과 "덮어쓰지 않는다" 는 저장된
 * 행을 봐야 확인되고, 닉네임 해방은 {@code uk_member_nickname} 이 걸린 실제 테이블에서만
 * 의미가 있다.
 *
 * <p>픽스처의 {@code accountId} 는 201 부터, 닉네임은 {@code 관리자} 가 아닌 값을 쓴다. ADMIN
 * seed 가 {@code (1, '관리자')} 를 넣으므로 겹치면 UNIQUE 위반으로 깨진다
 * (sp-docs/requirements/member.md §10.1). M-03 의 픽스처(101~)와도 범위를 겹치지 않게 둔다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberServiceTest {

	/** {@code V2__seed_admin_profile.sql} 의 프로필 (sp-docs/requirements/member.md §10.1). */
	private static final Long SEED_ACCOUNT_ID = 1L;

	@Autowired
	private MemberService memberService;

	@Autowired
	private MemberRepository memberRepository;

	/**
	 * <b>seed 행은 남긴다.</b> H2 는 테스트 클래스 사이에 공유되므로
	 * ({@code DB_CLOSE_DELAY=-1}, sp-docs/tech-stack.md §5.3) 여기서 지우면
	 * {@link com.example.member.member.AdminProfileSeedTest} 가 자기와 무관한 이유로 깨진다.
	 */
	@AfterEach
	void 정리() {
		this.memberRepository.deleteAll(this.memberRepository.findAll().stream()
				.filter(profile -> !SEED_ACCOUNT_ID.equals(profile.getAccountId()))
				.toList());
	}

	@Test
	@DisplayName("프로필을 등록하면 DB 에 저장되고 생성으로 표시된다")
	void 정상_등록() {
		ProfileRegistration registration = this.memberService.register(201L, request("홍길동"));

		assertThat(registration.created())
				.as("행이 없었으므로 201 이다 (sp-docs/api-contract.md §3)")
				.isTrue();
		assertThat(registration.profile().accountId()).isEqualTo(201L);
		assertThat(registration.profile().nickname()).isEqualTo("홍길동");

		Member saved = this.memberRepository.findByAccountId(201L).orElseThrow();
		assertThat(saved.getNickname()).isEqualTo("홍길동");
		assertThat(saved.isDeleted()).isFalse();
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	/**
	 * 응답만 유실된 클라이언트의 재시도다 (sp-docs/requirements/member.md §4). 중복 생성도
	 * 덮어쓰기도 없어야 하고, 상태 코드가 201 이 아니라 200 이어야 한다.
	 */
	@Test
	@DisplayName("같은 계정·같은 닉네임으로 재호출하면 기존 프로필을 생성 없이 돌려준다")
	void 같은_계정_같은_닉네임_재호출() {
		this.memberService.register(202L, request("멱등한사람"));
		LocalDateTime createdAt = this.memberRepository.findByAccountId(202L).orElseThrow().getCreatedAt();

		ProfileRegistration again = this.memberService.register(202L, request("멱등한사람"));

		assertThat(again.created())
				.as("행을 만들지 않았으므로 200 이다 (sp-docs/api-contract.md §3)")
				.isFalse();
		assertThat(again.profile().accountId()).isEqualTo(202L);
		assertThat(again.profile().nickname()).isEqualTo("멱등한사람");

		Member saved = this.memberRepository.findByAccountId(202L).orElseThrow();
		assertThat(saved.getCreatedAt())
				.as("기존 행을 다시 쓰지 않는다 — createdAt 이 바뀌면 덮어쓴 것이다")
				.isEqualTo(createdAt);
	}

	@Test
	@DisplayName("같은 계정·다른 닉네임으로 재호출하면 M006 이고 기존 닉네임이 그대로다")
	void 같은_계정_다른_닉네임_재호출() {
		this.memberService.register(203L, request("처음닉네임"));

		assertThatThrownBy(() -> this.memberService.register(203L, request("바꾼닉네임")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_ALREADY_REGISTERED);

		assertThat(this.memberRepository.findByAccountId(203L).orElseThrow().getNickname())
				.as("등록은 닉네임 변경 경로가 아니다 — 덮어쓰면 PATCH /members/me 가 무의미해진다")
				.isEqualTo("처음닉네임");
	}

	/**
	 * <b>미등록과 탈퇴를 같은 "없음" 으로 처리하면 여기서 프로필이 재생성된다</b>
	 * (sp-docs/requirements/member.md §3 규칙 6). 닉네임이 같든 다르든 결론은 {@code M007} 이다
	 * — 탈퇴한 행은 {@code nickname} 이 NULL 이므로 닉네임 비교가 먼저 오면 {@code M006} 이
	 * 나간다.
	 */
	@Test
	@DisplayName("탈퇴한 프로필의 계정으로 재호출하면 닉네임과 무관하게 M007 이고 행이 되살아나지 않는다")
	void 탈퇴한_프로필의_계정으로_재호출() {
		this.memberService.register(204L, request("탈퇴한사람"));
		탈퇴시킨다(204L);

		assertThatThrownBy(() -> this.memberService.register(204L, request("탈퇴한사람")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_WITHDRAWN);
		assertThatThrownBy(() -> this.memberService.register(204L, request("다른닉네임")))
				.as("탈퇴한 행의 nickname 은 NULL 이므로 닉네임 비교가 먼저 오면 M006 이 나간다")
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_WITHDRAWN);

		Member stale = this.memberRepository.findByAccountId(204L).orElseThrow();
		assertThat(stale.isDeleted()).isTrue();
		assertThat(stale.getNickname()).isNull();
	}

	@Test
	@DisplayName("타인이 쓰는 닉네임으로 등록하면 M003 이고 행이 만들어지지 않는다")
	void 타인이_쓰는_닉네임() {
		this.memberService.register(205L, request("선점한닉네임"));

		assertThatThrownBy(() -> this.memberService.register(206L, request("선점한닉네임")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.NICKNAME_DUPLICATED);

		assertThat(this.memberRepository.findByAccountId(206L)).isEmpty();
	}

	@Test
	@DisplayName("쓰이지 않는 닉네임은 available 이 true 다")
	void 미사용_닉네임_중복_확인() {
		assertThat(this.memberService.checkNickname("아무도안쓰는닉").available()).isTrue();
	}

	@Test
	@DisplayName("쓰이고 있는 닉네임은 available 이 false 다")
	void 사용중인_닉네임_중복_확인() {
		this.memberService.register(207L, request("쓰이는닉네임"));

		assertThat(this.memberService.checkNickname("쓰이는닉네임").available()).isFalse();
	}

	/**
	 * 완료 기준: <b>탈퇴로 해방된 닉네임은 {@code available: true} 다</b> — 이메일과 달리
	 * 닉네임은 재사용할 수 있다 (sp-docs/api-contract.md §2.3).
	 *
	 * <p>탈퇴 전에 {@code false} 임을 먼저 본다. 그러지 않으면 "닉네임을 애초에 저장하지 않는"
	 * 구현에서도 통과하므로 단언이 해방을 보지 않는다.
	 *
	 * <p>조회가 {@code true} 가 되는 것만으로는 재사용이 증명되지 않는다 —
	 * {@code uk_member_nickname} 이 그 값을 쥐고 있으면 등록에서 터진다. 그래서 다른 계정으로
	 * 실제 등록까지 해 본다.
	 */
	@Test
	@DisplayName("탈퇴로 해방된 닉네임은 available 이 true 이고 다른 계정이 실제로 등록할 수 있다")
	void 탈퇴로_해방된_닉네임() {
		this.memberService.register(208L, request("해방되는닉네임"));
		assertThat(this.memberService.checkNickname("해방되는닉네임").available())
				.as("탈퇴 전에는 쓰이고 있다")
				.isFalse();

		탈퇴시킨다(208L);

		assertThat(this.memberService.checkNickname("해방되는닉네임").available())
				.as("탈퇴 시 nickname 이 NULL 로 비워져 조회에 걸리는 행이 없다")
				.isTrue();

		ProfileRegistration reuse = this.memberService.register(209L, request("해방되는닉네임"));

		assertThat(reuse.created()).isTrue();
		assertThat(reuse.profile().accountId()).isEqualTo(209L);
	}

	/**
	 * <b>UNIQUE 둘이 아닌 무결성 위반은 {@code M003} 으로 바뀌지 않는다</b>
	 * (sp-docs/AGENTS.md 불변식 9). 서버측 결함을 "닉네임 중복" 으로 위장하면 409 를 받은
	 * 클라이언트가 닉네임을 바꿔 가며 재시도하고, 원인은 어디에도 기록되지 않는다.
	 *
	 * <p>여기서 쓰는 위반은 <b>컬럼 폭 초과</b>다 — {@code nickname} 은 VARCHAR(30) 이고
	 * (sp-docs/domain-model.md §3.1) 31자는 들어가지 않는다. 입력 검증이 2~10자로 막으므로
	 * 운영 경로로는 도달하지 않는 값이고, 그래서 <b>이것은 서버 결함의 대역</b>이다 —
	 * {@code @EnableJpaAuditing} 이 빠져 {@code created_at} 이 NULL 로 가는 경우와 같은 종류다.
	 * 둘 다 {@code uk_member_nickname} 도 {@code uk_member_account_id} 도 아니다.
	 *
	 * <p>500 {@code C005} 로 나가는 것이 맞다 — {@code GlobalExceptionHandler} 의 마지막 그물이
	 * {@code log.error} 를 남긴다 (sp-docs/conventions.md §6.1).
	 */
	@Test
	@DisplayName("UNIQUE 둘이 아닌 무결성 위반은 M003 으로 바뀌지 않고 그대로 올라온다")
	void 그_밖의_무결성_위반은_다시_던진다() {
		String 컬럼_폭을_넘는_닉네임 = "가".repeat(31);

		assertThatThrownBy(() -> this.memberService.register(236L, request(컬럼_폭을_넘는_닉네임)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.as("BusinessException 으로 바뀌면 409 M003 이 나가고 log.error 가 남지 않는다")
				.isNotInstanceOf(BusinessException.class);

		assertThat(this.memberRepository.findByAccountId(236L)).isEmpty();
	}

	/**
	 * <b>{@code catch (DataIntegrityViolationException)} 의 테스트다.</b> 사전 검사
	 * ({@code existsByNickname})가 막아 주는 동안 INSERT 는 제약을 밟지 않으므로, 사전 검사를
	 * 우회하지 않으면 그 블록이 한 번도 실행되지 않는다.
	 *
	 * <p><b>스텁은 타이밍만 만들고 효과는 실제로 낸다</b>
	 * (sp-docs/process/review-policy.md §4.1). 밟히는 제약은 흉내낸 예외가 아니라 H2 가 실제로
	 * 거부한 {@code uk_member_*} 이고, 경합 상대의 행은 스텁 안에서 <b>정말로 커밋된다.</b>
	 * {@code invocation.callRealMethod()} 는 쓸 수 없다 — Spring Data 리포지터리는 JDK 프록시라
	 * {@code Cannot call abstract real method} 가 난다(§4.1).
	 *
	 * <p>재현하는 것은 "확인–커밋 사이의 창" 이다 — 사전 검사는 경합을 막지 못하고 최종 판정은
	 * UNIQUE 제약이다 (sp-docs/api-contract.md §2.3). {@code register} 가 보는 사건의 순서는
	 * <b>확인이 {@code false} 를 돌려주었고, flush 시점에는 그 행이 있었다</b> 이고, 그것이
	 * 경합의 정의다.
	 *
	 * <p><b>창을 여는 자리로 사전 검사 호출을 쓴다. 그 결합은 의도된 것이다.</b> 다른 seam 은
	 * 모두 더 나쁘다 — {@code findByAccountId} 를 쓰면 catch 안의 되읽기가 같은 스텁에 걸려
	 * <b>되읽기 자체를 가짜로 만들고</b>, {@code saveAndFlush} 를 쓰면 실제 INSERT 를 발행할
	 * 방법이 없다({@code callRealMethod()} 불가). 이 seam 에서는 밟히는 제약과 새 트랜잭션의
	 * 되읽기가 <b>둘 다 실제</b>다. 사전 검사를 지우면 창이 닫혀 아래 셋이 빨개진다.
	 */
	@Nested
	@DisplayName("확인–커밋 사이의 경합 (제약 위반 경로)")
	class 확인_커밋_사이의_경합 {

		/** 사전 검사를 우회하는 자리다. 나머지 메서드는 실제 빈에 위임된다. */
		@MockitoSpyBean
		private MemberRepository spyMemberRepository;

		/** 위 스파이를 주입받은 쪽이다. 바깥 클래스의 것과 다른 인스턴스다. */
		@Autowired
		private MemberService memberService;

		/**
		 * <b>경합 상대의 커넥션이다.</b> {@code DataSourceUtils} 를 쓰지 않고 풀에서 직접
		 * 받는다 — 진행 중인 트랜잭션에 참여하면 되읽기(READ COMMITTED)가 그 행을 볼 수 없다.
		 */
		@Autowired
		private DataSource dataSource;

		/**
		 * 타인이 쥔 닉네임을 사전 검사가 놓친 경우다. 밟히는 것은 {@code uk_member_nickname}
		 * 하나이고 (내 {@code accountId} 는 겹치지 않는다) 결론은 {@code M003} 이다
		 * (sp-docs/api-contract.md §2.3 — "최종 판정은 UNIQUE 제약이고 생성 요청이 409 를
		 * 돌려준다").
		 */
		@Test
		@DisplayName("타인이 선점한 닉네임으로 제약을 밟으면 M003 이고 행이 만들어지지 않는다")
		void 닉네임_제약을_밟으면_M003() {
			MemberServiceTest.this.memberService.register(231L, request("경합한닉네임"));
			사전_검사를_우회한다("경합한닉네임");

			assertThatThrownBy(() -> this.memberService.register(232L, request("경합한닉네임")))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.NICKNAME_DUPLICATED);

			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(232L)).isEmpty();
			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(231L).orElseThrow().getNickname())
					.as("선점자의 행은 그대로다")
					.isEqualTo("경합한닉네임");
		}

		/**
		 * <b>응답만 유실된 클라이언트의 재시도가 정확히 이 경우다</b>
		 * (sp-docs/requirements/member.md §4). 같은 본문의 두 요청이 겹치면
		 * {@code uk_member_account_id} 와 {@code uk_member_nickname} 이 함께 깨지므로, 제약
		 * 이름으로 가르는 구현은 {@code M003} 을 돌려준다 — sp-docs/api-contract.md §3 의 표는
		 * <b>"활성 프로필, 닉네임 동일" 을 200 기존 반환</b>으로 정했다.
		 */
		@Test
		@DisplayName("같은 계정이 같은 닉네임으로 선점했으면 200 으로 기존 프로필을 돌려준다")
		void 같은_계정_같은_닉네임_경합은_200() {
			경합_상대가_커밋한다(233L, "겹친닉네임", false);

			ProfileRegistration registration = this.memberService.register(233L, request("겹친닉네임"));

			assertThat(registration.created())
					.as("행을 만든 것은 경합 상대다 — 200 이다 (sp-docs/api-contract.md §3)")
					.isFalse();
			assertThat(registration.profile().accountId()).isEqualTo(233L);
			assertThat(registration.profile().nickname()).isEqualTo("겹친닉네임");

			assertThat(MemberServiceTest.this.memberRepository.findAll().stream()
					.filter(profile -> profile.getAccountId() == 233L)
					.count())
					.as("중복 생성이 없다 (sp-docs/requirements/member.md §4)")
					.isEqualTo(1);
		}

		/** 활성 프로필, 닉네임 상이 → 409 {@code M006} (sp-docs/api-contract.md §3 의 표). */
		@Test
		@DisplayName("같은 계정이 다른 닉네임으로 선점했으면 M006 이다")
		void 같은_계정_다른_닉네임_경합은_M006() {
			경합_상대가_커밋한다(234L, "먼저도착한닉", false);

			assertThatThrownBy(() -> this.memberService.register(234L, request("나중도착한닉")))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.MEMBER_ALREADY_REGISTERED);

			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(234L).orElseThrow().getNickname())
					.as("덮어쓰지 않는다")
					.isEqualTo("먼저도착한닉");
		}

		/**
		 * 탈퇴한 프로필 → 409 {@code M007} (sp-docs/api-contract.md §3 의 표). 탈퇴한 행은
		 * {@code nickname} 이 NULL 이므로 (sp-docs/domain-model.md §3.1) 밟히는 제약은
		 * {@code uk_member_account_id} 하나다.
		 */
		@Test
		@DisplayName("같은 계정이 탈퇴한 프로필로 선점했으면 M007 이고 행이 되살아나지 않는다")
		void 같은_계정_탈퇴_상태_경합은_M007() {
			경합_상대가_커밋한다(235L, null, true);

			assertThatThrownBy(() -> this.memberService.register(235L, request("되살리려는닉")))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.MEMBER_WITHDRAWN);

			Member stale = MemberServiceTest.this.memberRepository.findByAccountId(235L).orElseThrow();
			assertThat(stale.isDeleted()).isTrue();
			assertThat(stale.getNickname()).isNull();
		}

		/**
		 * 사전 검사가 {@code false} 를 돌려주게 하면서, 그 사이에 경합 상대가 같은 닉네임의
		 * 행을 <b>실제로 커밋한다.</b> 스텁이 흉내만 내면 밟히는 제약이 없어 단언이 반증
		 * 불가능해진다 (sp-docs/process/review-policy.md §4.1).
		 */
		private void 사전_검사를_우회한다(String nickname) {
			willReturn(false).given(this.spyMemberRepository).existsByNickname(nickname);
		}

		/**
		 * 경합 상대의 INSERT 다. <b>짝이 되는 운영 코드는
		 * {@code MemberService.register} 의 {@code memberRepository.saveAndFlush(profile)}</b>
		 * — 같은 다섯 컬럼을 채운다 ({@code created_at}·{@code updated_at} 은 운영에서는
		 * {@code @EnableJpaAuditing} 이 채운다).
		 *
		 * <p>이 행은 {@code findByAccountId} 가 비어 있음을 확인한 <b>뒤에</b> 들어가야 한다.
		 * 그래서 사전 검사 스텁 안에서 커밋한다 — 그 자리가 확인과 flush 사이다.
		 */
		private void 경합_상대가_커밋한다(long accountId, String nickname, boolean deleted) {
			willAnswer(invocation -> {
				try (Connection connection = this.dataSource.getConnection();
						PreparedStatement statement = connection.prepareStatement(
								"insert into member (account_id, nickname, deleted, created_at, updated_at) "
										+ "values (?, ?, ?, ?, ?)")) {
					LocalDateTime now = LocalDateTime.now();
					statement.setLong(1, accountId);
					statement.setString(2, nickname);
					statement.setBoolean(3, deleted);
					statement.setObject(4, now);
					statement.setObject(5, now);
					// 행 수를 버리면 SQL 이 어긋나 0행이 되었을 때 조용하다 — 밟을 제약이
					// 없어지므로 아래 단언이 다시 반증 불가능해진다 (§4.1)
					assertThat(statement.executeUpdate())
							.as("경합 상대의 행이 실제로 커밋돼야 한다")
							.isEqualTo(1);
				}
				catch (SQLException ex) {
					throw new IllegalStateException(ex);
				}
				// 확인 시점에는 이 닉네임을 쥔 행이 없었다. 창은 확인과 flush 사이에 열린다
				return false;
			}).given(this.spyMemberRepository).existsByNickname(anyString());
		}

	}

	/** 실제 탈퇴 경로는 M-09 다. 지금은 엔티티 메서드로 같은 상태를 만든다. */
	private void 탈퇴시킨다(long accountId) {
		Member profile = this.memberRepository.findByAccountId(accountId).orElseThrow();
		profile.withdraw();
		this.memberRepository.saveAndFlush(profile);
	}

	private static ProfileCreateRequest request(String nickname) {
		return new ProfileCreateRequest(nickname);
	}

}
