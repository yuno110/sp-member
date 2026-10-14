package com.example.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.member.global.error.BusinessException;
import com.example.member.global.error.ErrorCode;
import com.example.member.member.dto.MemberResponse;
import com.example.member.member.dto.NicknameUpdateRequest;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.dto.ProfileRegistration;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * sp-docs/plan/phase1.md §5 M-05 · M-08 의 "검증" 표 중 저장 쪽을 테스트로 옮긴 것이다. HTTP
 * 상태·응답 본문은 {@link com.example.member.member.controller.MemberControllerTest} 가 본다.
 *
 * <p>상태별 응답의 정본은 sp-docs/api-contract.md §3 의 표이고, 세 상태의 구분은
 * sp-docs/requirements/member.md §3 규칙 6, sp-docs/domain-model.md §3.1 이다.
 *
 * <p>리포지터리를 스텁으로 바꾸지 않고 H2 를 그대로 쓴다. 멱등과 "덮어쓰지 않는다" 는 저장된
 * 행을 봐야 확인되고, 닉네임 해방은 {@code uk_member_nickname} 이 걸린 실제 테이블에서만
 * 의미가 있다.
 *
 * <p>픽스처의 {@code accountId} 는 M-05 가 201~, <b>M-08 이 241~249 · 263~267 · 271~272</b> 를
 * 쓰고, 닉네임은
 * {@code 관리자} 가 아닌 값을 쓴다. ADMIN seed 가 {@code (1, '관리자')} 를 넣으므로 겹치면 UNIQUE
 * 위반으로 깨진다 (sp-docs/requirements/member.md §10.1). M-03 의 픽스처(101~)·M-05 의
 * {@code MemberControllerTest}(211~)와도 범위를 겹치지 않게 둔다.
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

	// --- M-08 내 프로필 조회·수정 (sp-docs/api-contract.md §3.1, requirements/member.md §5 §7) ---

	/**
	 * 돌려주는 값은 {@code accountId}·{@code nickname} 이다 ({@code MemberResponse} 의 컴포넌트가
	 * 그것을 정한다). <b>직렬화된 응답의 키 집합은
	 * {@link com.example.member.member.controller.MemberControllerTest} 가 본다</b> — 여기서는
	 * 어느 계정의 어느 닉네임이 나오는지를 본다.
	 */
	@Test
	@DisplayName("내 프로필을 조회하면 그 계정의 accountId 와 닉네임이 나온다")
	void 내_프로필_조회() {
		this.memberService.register(241L, request("조회되는사람"));

		MemberResponse profile = this.memberService.getMyProfile(241L);

		assertThat(profile.accountId()).isEqualTo(241L);
		assertThat(profile.nickname()).isEqualTo("조회되는사람");
	}

	/**
	 * 완료 기준: <b>프로필이 없으면 {@code M001}</b> 이다. 오류가 아니라 가입 3단계 미완료를 뜻한다
	 * (sp-docs/requirements/member.md §5, sp-docs/api-contract.md §8.2).
	 */
	@Test
	@DisplayName("프로필 없는 계정으로 조회하면 M001 이다")
	void 프로필_없는_계정_조회() {
		assertThatThrownBy(() -> this.memberService.getMyProfile(242L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
	}

	/**
	 * 완료 기준: <b>탈퇴한 프로필은 조회할 수 없다 — 미등록과 같은 {@code M001}</b> 이다
	 * (sp-docs/api-contract.md §3.1 의 표). {@code MemberReader} 는 탈퇴 여부를 보지 않으므로
	 * (상태별 결론을 호출하는 쪽에 맡긴다) 이 결론은 서비스가 낸다.
	 *
	 * <p><b>탈퇴 전에 조회가 성공함을 먼저 본다.</b> 그러지 않으면 "늘 {@code M001}" 인 구현도
	 * 통과한다.
	 */
	@Test
	@DisplayName("탈퇴한 프로필로 조회하면 M001 이다")
	void 탈퇴한_프로필_조회() {
		this.memberService.register(243L, request("곧탈퇴한다"));
		assertThat(this.memberService.getMyProfile(243L).nickname())
				.as("탈퇴 전에는 조회된다 — 늘 M001 인 구현과 가른다")
				.isEqualTo("곧탈퇴한다");

		탈퇴시킨다(243L);

		assertThatThrownBy(() -> this.memberService.getMyProfile(243L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
	}

	/**
	 * MR-08 — 닉네임을 갱신하고 <b>갱신된 프로필을 반환한다</b>
	 * (sp-docs/requirements/member.md §7).
	 *
	 * <p>반환값만 보면 DB 에 쓰지 않는 구현도 통과하므로 저장된 행을 함께 읽는다.
	 */
	@Test
	@DisplayName("닉네임을 변경하면 갱신된 프로필을 돌려주고 DB 에 반영된다")
	void 닉네임_변경() {
		this.memberService.register(244L, request("옛닉네임"));

		MemberResponse updated = this.memberService.updateNickname(244L, update("새닉네임"));

		assertThat(updated.accountId()).isEqualTo(244L);
		assertThat(updated.nickname()).isEqualTo("새닉네임");
		assertThat(this.memberRepository.findByAccountId(244L).orElseThrow().getNickname())
				.as("응답만 바뀌고 DB 가 그대로면 다음 조회가 옛 닉네임을 돌려준다")
				.isEqualTo("새닉네임");
	}

	/**
	 * 완료 기준: <b>자기 현재 닉네임으로 변경 시 중복 오류가 나지 않는다</b>
	 * (sp-docs/requirements/member.md §7).
	 *
	 * <p>중복 검사가 자기 행을 제외하지 않으면 사전 검사가 자기 행을 찾아 {@code M003} 을 낸다 —
	 * 자기가 쓰고 있는 값을 "이미 사용 중" 이라고 거부하는 셈이다. {@code uk_member_nickname} 은
	 * 이 경우 깨지지 않으므로(같은 행을 같은 값으로 쓴다) <b>막는 것은 사전 검사뿐이고, 그래서
	 * 제외도 사전 검사 쪽에서 해야 한다</b> (§7).
	 */
	@Test
	@DisplayName("자기 현재 닉네임으로 변경하면 중복 오류 없이 그 닉네임을 돌려준다")
	void 자기_현재_닉네임으로_변경() {
		this.memberService.register(245L, request("그대로인닉"));

		MemberResponse updated = this.memberService.updateNickname(245L, update("그대로인닉"));

		assertThat(updated.nickname()).isEqualTo("그대로인닉");
		assertThat(this.memberRepository.findByAccountId(245L).orElseThrow().getNickname())
				.isEqualTo("그대로인닉");
	}

	/** 완료 기준: <b>중복 닉네임으로 변경 시 {@code M003}</b> (sp-docs/api-contract.md §8.2). */
	@Test
	@DisplayName("타인이 쓰는 닉네임으로 변경하면 M003 이고 두 프로필이 그대로다")
	void 타인_닉네임으로_변경() {
		this.memberService.register(246L, request("선점한닉네임"));
		this.memberService.register(247L, request("바꾸려는사람"));

		assertThatThrownBy(() -> this.memberService.updateNickname(247L, update("선점한닉네임")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.NICKNAME_DUPLICATED);

		assertThat(this.memberRepository.findByAccountId(247L).orElseThrow().getNickname())
				.as("거부됐으므로 바뀌지 않는다")
				.isEqualTo("바꾸려는사람");
		assertThat(this.memberRepository.findByAccountId(246L).orElseThrow().getNickname())
				.as("선점자의 행도 그대로다")
				.isEqualTo("선점한닉네임");
	}

	/**
	 * <b>중복 검사의 제외 기준이 {@code accountId} 라는 것을 고정한다</b>
	 * (sp-docs/requirements/member.md §7). 값 동일성으로 제외하면 운영 콜레이션
	 * ({@code utf8mb4_general_ci}, sp-docs/tech-stack.md §4.1)에서 {@code abcde} 를 쥔 사용자가
	 * {@code ABCDE} 로 바꿀 때 사전 검사가 <b>자기 행</b>을 찾아 409 {@code M003} 이 나가는데,
	 * {@code uk_member_nickname} 은 같은 행이라 그 UPDATE 를 허용한다 — §7 이 금지한 것이다.
	 * 그 규칙을 지키는 것이 지금은 javadoc 뿐이어서 이 테스트를 둔다.
	 *
	 * <p><b>그 입력 자체는 이 저장소의 DB 로 만들 수 없다.</b> H2 는 데이터 비교가 대소문자를
	 * 구분하고({@code IGNORECASE} 를 켜지 않았다) {@code CASE_INSENSITIVE_IDENTIFIERS} 는
	 * 식별자에만 적용된다 (§7). <b>별도 인메모리 스키마에 {@code IGNORECASE=TRUE} 를 더하지
	 * 않는다</b> — sp-docs/tech-stack.md §5.3 이 "이 URL 을 그대로 쓴다. {@code <스키마>} 만
	 * 서비스별로 바꾼다" 로 정했으므로 두 번째 URL 형태를 쓰려면 그 절의 개정이 먼저다.
	 *
	 * <p><b>대신 리포지터리 경계에서 그 조건만 주입한다</b> (§7 의 블록인용이 가리키는 방법이다).
	 * 값 동일성 조회({@link MemberRepository#existsByNickname})가 {@code true} 를 돌려주게 해 두고
	 * 변경이 여전히 성공하는지 본다.
	 *
	 * <p><b>한계를 숨기지 않는다.</b> {@code accountId} 기준 구현에서 이 스텁은 <b>한 번도
	 * 호출되지 않는다</b> — 초록인 이유는 단언이 통과해서가 아니라 <b>운영 코드가 그 질문을 아예
	 * 던지지 않아서</b>이고, 그래서 이것은 사실상 <b>경계 관측</b>이다. 반증 가능한 방향은 하나뿐이다
	 * — 기준을 값 비교로 되돌리면 {@code BusinessException: 이미 사용 중인 닉네임입니다.} 로
	 * 빨개진다(실측). <b>그 한 방향이면 된다</b>: sp-docs/process/review-policy.md §4.1 은 변이
	 * 테스트를 증명이 아니라 반증 도구로 쓰라고 정하고, 중요한 변이 하나를 잡는 것 이상을
	 * 요구하지 않는다.
	 *
	 * <p>닉네임 {@code abcde}·{@code ABCDE} 는 <b>2~10자 영문</b>이라
	 * sp-docs/requirements/member.md §2 의 패턴을 지킨다 — 테스트용으로 꾸며낸 값이 아니라 HTTP
	 * 경로로도 그대로 도달하는 입력이다.
	 */
	@Nested
	@DisplayName("중복 검사의 제외 기준 (운영 콜레이션의 조건을 주입한다)")
	class 제외_기준은_accountId {

		/** 값 동일성 사전 검사가 자기 행을 찾는 상황을 만드는 자리다. 나머지 메서드는 실제 빈에 위임된다. */
		@MockitoSpyBean
		private MemberRepository spyMemberRepository;

		/** 위 스파이를 주입받은 쪽이다. 바깥 클래스의 것과 다른 인스턴스다. */
		@Autowired
		private MemberService memberService;

		/**
		 * 자기 닉네임의 대소문자만 바꾸는 요청이다. 운영에서는 값 동일성 사전 검사가 자기 행을
		 * 찾지만 {@code uk_member_nickname} 은 그 UPDATE 를 허용하므로, 200 이어야 한다
		 * (sp-docs/requirements/member.md §7).
		 *
		 * <p>반환값만 보면 DB 에 쓰지 않는 구현도 통과하므로 저장된 행을 함께 읽는다.
		 */
		@Test
		@DisplayName("자기 닉네임의 대소문자만 바꾸면 값 동일성 검사가 자기 행을 찾아도 200 이다")
		void 대소문자만_바꾸는_변경() {
			MemberServiceTest.this.memberService.register(264L, request("abcde"));
			// 운영 콜레이션(utf8mb4_general_ci)에서는 값 동일성 사전 검사가 자기 행을 찾는다
			willReturn(true).given(this.spyMemberRepository).existsByNickname("ABCDE");

			MemberResponse updated = this.memberService.updateNickname(264L, update("ABCDE"));

			assertThat(updated.nickname()).isEqualTo("ABCDE");
			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(264L).orElseThrow().getNickname())
					.as("응답만 바뀌고 DB 가 그대로면 다음 조회가 옛 닉네임을 돌려준다")
					.isEqualTo("ABCDE");
		}

		/**
		 * <b>대조군이다.</b> 위 테스트와 다른 것은 하나뿐이다 — 요청하는 값을 <b>다른 활성 계정이
		 * 쥐고 있다.</b> 이것이 없으면 위 테스트가 "늘 통과시킨다"(중복 검사가 아예 없는) 구현과
		 * 갈리지 않는다.
		 *
		 * <p>{@link MemberServiceTest#타인_닉네임으로_변경()} 과 같은 결함을 두 번 보는 것이
		 * 아니다 (sp-docs/AGENTS.md 불변식 11) — 그쪽은 값이 전혀 다른 평범한 선점이고, 여기는 위
		 * 테스트의 짝이 되도록 <b>같은 요청값·같은 현재 닉네임·같은 스텁</b>을 두고 선점자만 더한
		 * 대조군이다.
		 */
		@Test
		@DisplayName("같은 입력이라도 타인이 그 닉네임을 쥐고 있으면 M003 이다")
		void 타인이_쥔_값은_M003() {
			MemberServiceTest.this.memberService.register(265L, request("ABCDE"));
			MemberServiceTest.this.memberService.register(266L, request("abcde"));
			willReturn(true).given(this.spyMemberRepository).existsByNickname("ABCDE");

			assertThatThrownBy(() -> this.memberService.updateNickname(266L, update("ABCDE")))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.NICKNAME_DUPLICATED);

			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(266L).orElseThrow().getNickname())
					.as("거부됐으므로 바뀌지 않는다")
					.isEqualTo("abcde");
			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(265L).orElseThrow().getNickname())
					.as("선점자의 행도 그대로다")
					.isEqualTo("ABCDE");
		}

	}

	/**
	 * 완료 기준: <b>탈퇴한 프로필은 수정할 수 없다 — 404 {@code M001}</b>
	 * (sp-docs/api-contract.md §3.1 의 표).
	 *
	 * <p>탈퇴를 활성으로 보면 UPDATE 가 통과해 <b>{@code nickname} 이 다시 채워지는데
	 * {@code deleted} 는 {@code true} 로 남는다</b> — 탈퇴한 행이 닉네임을 쥔 상태이고, 그것은
	 * sp-docs/domain-model.md §3.1 이 정한 세 상태 어디에도 없다. 게다가 그 값은
	 * {@code uk_member_nickname} 에 선점되어 아무도 쓸 수 없다. 그래서 코드만 보지 않고 <b>행
	 * 상태까지</b> 단언한다.
	 *
	 * <p><b>요청할 닉네임을 다른 활성 계정이 쥐고 있게 둔다 — 그래야 검사 순서가 고정된다.</b>
	 * 아무도 쥐고 있지 않으면 "존재·탈퇴 확인 → 중복 검사" 와 "중복 검사 → 존재·탈퇴 확인" 이
	 * 같은 답을 내므로 두 순서가 구별되지 않는다. 선점자를 두면 뒤쪽 순서는 409 {@code M003} 을
	 * 내는데, 이 입력의 결론은 404 {@code M001} 이다 (sp-docs/api-contract.md §3.1 의 표).
	 */
	@Test
	@DisplayName("탈퇴한 프로필로 수정하면 M001 이고 닉네임이 되살아나지 않는다")
	void 탈퇴한_프로필_수정() {
		this.memberService.register(248L, request("탈퇴할사람"));
		탈퇴시킨다(248L);
		this.memberService.register(267L, request("되살리려는닉"));

		assertThatThrownBy(() -> this.memberService.updateNickname(248L, update("되살리려는닉")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

		Member stale = this.memberRepository.findByAccountId(248L).orElseThrow();
		assertThat(stale.isDeleted()).isTrue();
		assertThat(stale.getNickname())
				.as("탈퇴한 행에 닉네임이 다시 채워지면 그 값이 선점된다 (sp-docs/domain-model.md §3.1)")
				.isNull();
	}

	/**
	 * <b>수정은 프로필을 만들지 않는다.</b> 생성 경로는 {@code POST /api/v1/members} 하나이고 그쪽은
	 * {@code accountId} 기준 멱등이다 (sp-docs/requirements/member.md §3 규칙 7 · §4). 없는
	 * 프로필에 수정이 행을 만들면 가입 3단계를 건너뛰는 두 번째 생성 경로가 된다.
	 */
	@Test
	@DisplayName("프로필 없는 계정으로 수정하면 M001 이고 행이 생기지 않는다")
	void 프로필_없는_계정_수정() {
		assertThatThrownBy(() -> this.memberService.updateNickname(249L, update("없는사람의닉")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

		assertThat(this.memberRepository.findByAccountId(249L)).isEmpty();
	}

	/**
	 * <b>{@code uk_member_nickname} 이 아닌 무결성 위반은 {@code M003} 으로 바뀌지 않는다</b>
	 * (sp-docs/requirements/member.md §7). 서버측 결함을 "닉네임 중복" 으로 위장하면 409 를 받은
	 * 클라이언트가 닉네임을 바꿔 가며 재시도하고, 원인은 어디에도 기록되지 않는다 — M-05 에서
	 * 전부 {@code M003} 으로 돌린 구현이 그랬다.
	 *
	 * <p>여기서 쓰는 위반은 <b>컬럼 폭 초과</b>다 — {@code nickname} 은 VARCHAR(30) 이고
	 * (sp-docs/domain-model.md §3.1) 31자는 들어가지 않는다. 입력 검증이 2~10자로 막으므로 HTTP
	 * 경로로는 도달하지 않는 값이고, 그래서 <b>서버 결함의 대역</b>이다. 500 {@code C005} 로
	 * 나가는 것이 맞다 — {@code GlobalExceptionHandler} 의 마지막 그물이 {@code log.error} 를
	 * 남긴다 (sp-docs/conventions.md §6.1).
	 */
	@Test
	@DisplayName("수정에서 uk_member_nickname 이 아닌 무결성 위반은 M003 으로 바뀌지 않고 그대로 올라온다")
	void 수정의_그_밖의_무결성_위반은_다시_던진다() {
		this.memberService.register(263L, request("폭을넘긴다"));

		assertThatThrownBy(() -> this.memberService.updateNickname(263L, update("가".repeat(31))))
				.isInstanceOf(DataIntegrityViolationException.class)
				.as("BusinessException 으로 바뀌면 409 M003 이 나가고 log.error 가 남지 않는다")
				.isNotInstanceOf(BusinessException.class);

		assertThat(this.memberRepository.findByAccountId(263L).orElseThrow().getNickname())
				.as("롤백됐으므로 옛 닉네임이 그대로다")
				.isEqualTo("폭을넘긴다");
	}

	/**
	 * <b>수정 경로의 {@code catch (DataIntegrityViolationException)} 의 테스트다.</b> 사전 검사
	 * ({@code existsByNicknameAndAccountIdNot})가 막아 주는 동안 UPDATE 는 제약을 밟지 않으므로,
	 * 사전 검사를 우회하지 않으면 그 블록이 한 번도 실행되지 않는다 — M-05 에서 그 종류의 블록에
	 * 테스트가 0건이어서 결함이 리뷰 전까지 드러나지 않았다
	 * (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p><b>스텁은 타이밍만 만들고 효과는 실제로 낸다</b> (§4.1). 밟히는 제약은 흉내낸 예외가
	 * 아니라 H2 가 실제로 거부한 {@code uk_member_nickname} 이고, 선점자의 행은 스텁 안에서
	 * <b>정말로 커밋된다.</b> {@code invocation.callRealMethod()} 는 쓸 수 없다 — Spring Data
	 * 리포지터리는 JDK 프록시라 {@code Cannot call abstract real method} 가 난다 (§4.1).
	 *
	 * <p><b>창을 여는 자리로 사전 검사 호출을 쓴다.</b> 그 자리가 "내 행을 읽은 뒤, UPDATE 가
	 * 발행되기 전" 이고 그것이 §7 이 말하는 경합의 정의다. {@code flush} 를 seam 으로 쓰면 실제
	 * UPDATE 를 발행할 방법이 없고({@code callRealMethod()} 불가), {@code findByAccountId} 를 쓰면
	 * 수정 대상 자체가 가짜가 된다.
	 *
	 * <p>등록 경로의 같은 종류 테스트({@link 확인_커밋_사이의_경합})와 <b>기대 결과가 하나뿐인
	 * 점</b>이 다르다 — 이 경로에는 {@code uk_member_account_id} 경합이 없으므로 200·{@code M006}·
	 * {@code M007} 로 갈릴 상태가 없다.
	 */
	@Nested
	@DisplayName("수정의 확인–UPDATE 사이의 경합 (제약 위반 경로)")
	class 확인_UPDATE_사이의_경합 {

		/** 사전 검사를 우회하는 자리다. 나머지 메서드는 실제 빈에 위임된다. */
		@MockitoSpyBean
		private MemberRepository spyMemberRepository;

		/** 위 스파이를 주입받은 쪽이다. 바깥 클래스의 것과 다른 인스턴스다. */
		@Autowired
		private MemberService memberService;

		/**
		 * <b>선점자의 커넥션이다.</b> {@code DataSourceUtils} 를 쓰지 않고 풀에서 직접 받는다 —
		 * 진행 중인 트랜잭션에 참여하면 그 INSERT 가 같은 트랜잭션에서 롤백되어 밟을 제약이
		 * 사라진다.
		 */
		@Autowired
		private DataSource dataSource;

		/**
		 * 사전 검사가 {@code false} 를 돌려준 뒤 타인이 그 닉네임을 쥔 경우다. 최종 판정은
		 * {@code uk_member_nickname} 이고 결론은 409 {@code M003} 이다
		 * (sp-docs/requirements/member.md §7).
		 *
		 * <p><b>내 행이 롤백됐음을 함께 단언한다.</b> {@code M003} 을 돌려주면서 닉네임이 바뀌어
		 * 있으면 클라이언트가 보는 것과 저장된 것이 어긋난다.
		 */
		@Test
		@DisplayName("사전 검사 뒤 타인이 그 닉네임을 선점하면 M003 이고 내 닉네임이 그대로다")
		void 수정_경합은_M003() {
			MemberServiceTest.this.memberService.register(271L, request("경합전닉"));
			사전_검사_뒤에_선점된다(272L, "경합한닉");

			assertThatThrownBy(() -> this.memberService.updateNickname(271L, update("경합한닉")))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.NICKNAME_DUPLICATED);

			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(271L).orElseThrow().getNickname())
					.as("롤백됐으므로 옛 닉네임이 그대로다")
					.isEqualTo("경합전닉");
			assertThat(MemberServiceTest.this.memberRepository.findByAccountId(272L).orElseThrow().getNickname())
					.as("선점자의 행은 그대로다")
					.isEqualTo("경합한닉");
		}

		/**
		 * 사전 검사가 {@code false} 를 돌려주게 하면서, 그 사이에 선점자가 그 닉네임의 행을
		 * <b>실제로 커밋한다.</b> 스텁이 흉내만 내면 밟히는 제약이 없어 위 단언이 반증 불가능해진다
		 * (sp-docs/process/review-policy.md §4.1).
		 *
		 * <p><b>짝이 되는 운영 코드는 다른 사용자의 {@code POST /api/v1/members} 다</b> —
		 * {@link MemberService#register} 의 {@code saveAndFlush} 가 같은 다섯 컬럼을 채운다
		 * ({@code created_at}·{@code updated_at} 은 운영에서는 {@code @EnableJpaAuditing} 이
		 * 채운다). 수정 경로가 INSERT 를 발행하는 것이 아니다.
		 */
		private void 사전_검사_뒤에_선점된다(long accountId, String nickname) {
			willAnswer(invocation -> {
				try (Connection connection = this.dataSource.getConnection();
						PreparedStatement statement = connection.prepareStatement(
								"insert into member (account_id, nickname, deleted, created_at, updated_at) "
										+ "values (?, ?, ?, ?, ?)")) {
					LocalDateTime now = LocalDateTime.now();
					statement.setLong(1, accountId);
					statement.setString(2, nickname);
					// 활성 행이어야 한다. deleted = true 면 nickname 이 NULL 이어야 하고
					// (sp-docs/domain-model.md §3.1) 그러면 밟을 제약이 없다
					statement.setBoolean(3, false);
					statement.setObject(4, now);
					statement.setObject(5, now);
					// 행 수를 버리면 SQL 이 어긋나 0행이 되었을 때 조용하다 — 밟을 제약이
					// 없어지므로 위 단언이 다시 반증 불가능해진다 (§4.1)
					assertThat(statement.executeUpdate())
							.as("선점자의 행이 실제로 커밋돼야 한다")
							.isEqualTo(1);
				}
				catch (SQLException ex) {
					throw new IllegalStateException(ex);
				}
				// 확인 시점에는 이 닉네임을 쥔 다른 행이 없었다. 창은 확인과 UPDATE 사이에 열린다
				return false;
			}).given(this.spyMemberRepository).existsByNicknameAndAccountIdNot(anyString(), anyLong());
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

	private static NicknameUpdateRequest update(String nickname) {
		return new NicknameUpdateRequest(nickname);
	}

}
