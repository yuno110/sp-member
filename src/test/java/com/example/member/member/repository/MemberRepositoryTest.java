package com.example.member.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.member.global.config.JpaConfig;
import com.example.member.global.error.BusinessException;
import com.example.member.global.error.ErrorCode;
import com.example.member.member.entity.Member;
import com.example.member.member.support.MemberReader;
import jakarta.persistence.Column;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * sp-docs/plan/phase1.md §5 M-03 의 "검증" 표를 테스트로 옮긴 것이다. 그중 테스트 환경 자체에
 * 대한 것(실행 중 DataSource URL, 저장된 테이블명, 스키마 출처)은
 * {@link com.example.member.TestDatabaseEnvironmentTest} 가 본다.
 *
 * <p>{@code replace = NONE} 이 필수다. {@code @DataJpaTest} 의 기본값은 DataSource 를
 * {@code jdbc:h2:mem:<uuid>} 로 교체해 {@code MODE=MySQL} 을 날려버린다
 * (sp-docs/tech-stack.md §5.2).
 *
 * <p>스키마는 Flyway 가 만들고 {@code ddl-auto: validate} 가 엔티티와 대조한다. 이 클래스가
 * 로딩되는 것 자체가 마이그레이션과 엔티티 매핑의 일치를 뜻한다. <b>다만 {@code validate} 는
 * 컬럼 길이를 보지 않으므로</b> {@link #nickname_폭이_30이다()} 가 실제 폭을 따로 읽는다.
 *
 * <p>{@code JpaConfig} 를 {@code @Import} 하는 이유 — {@code @DataJpaTest} 슬라이스는
 * {@code @Configuration} 을 컴포넌트 스캔하지 않으므로 {@code @EnableJpaAuditing} 이 빠진다.
 * 그러면 {@code created_at} 이 NULL 로 들어가 INSERT 가 실패한다 (sp-docs/conventions.md §9.3).
 * {@code MemberReader} 도 같은 이유로 직접 올린다.
 *
 * <p>픽스처의 {@code accountId} 와 닉네임은 ADMIN seed 의 값({@code 1} / {@code 관리자}) 을
 * 피한다. M-05 의 {@code V2__seed_admin_profile.sql} 이 그 행을 넣으면 겹치는 픽스처가 UNIQUE
 * 위반으로 깨진다 (sp-docs/requirements/member.md §10.1).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, MemberReader.class})
@ActiveProfiles("test")
class MemberRepositoryTest {

	/** sp-docs/domain-model.md §3.1 이 정한 {@code nickname} 컬럼 폭이다. */
	private static final int NICKNAME_WIDTH = 30;

	@Autowired
	private TestEntityManager em;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private MemberReader memberReader;

	@Test
	@DisplayName("저장 후 조회하면 모든 필드가 일치한다")
	void 저장_후_조회() {
		Member saved = memberRepository.saveAndFlush(member(101L, "홍길동"));
		em.clear();

		Member found = memberRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getId()).isEqualTo(saved.getId());
		assertThat(found.getAccountId()).isEqualTo(101L);
		assertThat(found.getNickname()).isEqualTo("홍길동");
		assertThat(found.isDeleted()).isFalse();
		assertThat(found.isActive()).isTrue();
	}

	@Test
	@DisplayName("중복 account_id 를 저장하면 DataIntegrityViolationException 이 난다")
	void 중복_account_id_저장() {
		memberRepository.saveAndFlush(member(102L, "먼저온사람"));
		Member duplicate = member(102L, "나중에온사람");

		assertThatThrownBy(() -> memberRepository.saveAndFlush(duplicate))
				.as("uk_member_account_id 가 탈퇴 계정의 프로필 재생성까지 막는다")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("중복 nickname 을 저장하면 DataIntegrityViolationException 이 난다")
	void 중복_nickname_저장() {
		memberRepository.saveAndFlush(member(103L, "겹치는닉네임"));
		Member duplicate = member(104L, "겹치는닉네임");

		assertThatThrownBy(() -> memberRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("저장하면 createdAt·updatedAt 이 자동으로 채워진다")
	void 감사_필드가_채워진다() {
		Member saved = memberRepository.saveAndFlush(member(105L, "감사대상"));

		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("deleted 를 지정하지 않으면 false 다")
	void 기본값() {
		Member saved = memberRepository.saveAndFlush(member(106L, "신규가입자"));
		em.clear();

		assertThat(memberRepository.findById(saved.getId()).orElseThrow().isDeleted()).isFalse();
	}

	@Test
	@DisplayName("updateNickname() 은 닉네임만 바꾸고 createdAt 은 그대로다")
	void 닉네임_변경() {
		memberRepository.saveAndFlush(member(107L, "변경전닉네임"));
		em.clear();
		// DB 에서 읽은 값끼리 비교한다. 저장 직후의 메모리 값은 컬럼 정밀도로 잘릴 수 있다
		// (sp-docs/domain-model.md §1.1)
		LocalDateTime createdAt = memberRepository.findByAccountId(107L).orElseThrow().getCreatedAt();
		em.clear();

		memberRepository.findByAccountId(107L).orElseThrow().updateNickname("변경후닉네임");
		em.flush();
		em.clear();

		Member found = memberRepository.findByAccountId(107L).orElseThrow();
		assertThat(found.getNickname()).isEqualTo("변경후닉네임");
		assertThat(found.getCreatedAt()).isEqualTo(createdAt);
		assertThat(found.getAccountId()).isEqualTo(107L);
		assertThat(found.isDeleted()).isFalse();
	}

	@Test
	@DisplayName("withdraw() 는 deleted 를 true 로, nickname 을 NULL 로 만들고 행은 남긴다")
	void 탈퇴() {
		memberRepository.saveAndFlush(member(108L, "탈퇴하는사람"));
		em.clear();

		memberRepository.findByAccountId(108L).orElseThrow().withdraw();
		em.flush();
		em.clear();

		Member found = memberRepository.findByAccountId(108L).orElseThrow();
		assertThat(found.isDeleted()).isTrue();
		assertThat(found.isActive()).isFalse();
		assertThat(found.getNickname())
				.as("닉네임을 남기면 uk_member_nickname 때문에 그 닉네임이 영구 소각된다")
				.isNull();
	}

	/**
	 * <b>첫 탈퇴는 항상 성공하므로 1건만 보면 결함을 놓친다.</b> {@code "탈퇴한 회원"} 같은 고정
	 * 문자열을 저장하는 구현은 여기서 UNIQUE 위반으로 걸린다 (sp-docs/domain-model.md §3.1).
	 */
	@Test
	@DisplayName("두 회원을 연달아 withdraw() 하면 둘 다 성공한다")
	void 두_회원_연속_탈퇴() {
		memberRepository.saveAndFlush(member(109L, "먼저탈퇴"));
		memberRepository.saveAndFlush(member(110L, "나중탈퇴"));
		em.clear();

		memberRepository.findByAccountId(109L).orElseThrow().withdraw();
		em.flush();
		memberRepository.findByAccountId(110L).orElseThrow().withdraw();
		em.flush();
		em.clear();

		Member first = memberRepository.findByAccountId(109L).orElseThrow();
		Member second = memberRepository.findByAccountId(110L).orElseThrow();
		assertThat(first.getNickname()).isNull();
		assertThat(second.getNickname()).isNull();
		assertThat(first.isDeleted()).isTrue();
		assertThat(second.isDeleted()).isTrue();
	}

	@Test
	@DisplayName("MemberReader 는 없는 accountId 조회 시 BusinessException(M001) 을 던진다")
	void 없는_프로필_조회() {
		assertThatThrownBy(() -> memberReader.getByAccountId(999_999L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
	}

	/**
	 * 미등록(행이 없다)과 탈퇴를 같은 "없음" 으로 만들면 탈퇴한 프로필이 재생성된다
	 * (sp-docs/requirements/member.md §3 규칙 6). 상태별 결론은 호출하는 쪽이 정한다.
	 */
	@Test
	@DisplayName("MemberReader 는 탈퇴한 프로필도 돌려준다")
	void 탈퇴한_프로필도_조회된다() {
		memberRepository.saveAndFlush(member(111L, "탈퇴했지만행은있다"));
		em.clear();
		memberRepository.findByAccountId(111L).orElseThrow().withdraw();
		em.flush();
		em.clear();

		Member found = memberReader.getByAccountId(111L);

		assertThat(found.isDeleted()).isTrue();
		assertThat(found.getNickname()).isNull();
	}

	@Test
	@DisplayName("member 테이블에 email·password·role 컬럼이 없다")
	void 계정_컬럼이_없다() {
		List<?> columns = em.getEntityManager()
				.createNativeQuery("select column_name from information_schema.columns "
						+ "where table_name = 'member'")
				.getResultList();

		assertThat(columns).map(String::valueOf)
				.as("email·password·role 은 auth 소유다 (sp-docs/domain-model.md §3.1)")
				.containsExactlyInAnyOrder(
						"id", "account_id", "nickname", "deleted", "created_at", "updated_at");
	}

	/**
	 * {@code V1} 을 한 번 읽어 FK 가 없음을 확인하는 것으로는 <b>나중 마이그레이션이 FK 를
	 * 추가하는 것</b>을 막지 못한다. 그래서 지금 테이블에 걸려 있는 제약을 읽는다.
	 *
	 * <p>{@code account_id} 는 {@code sp_auth.account.id} 의 <b>논리</b> 참조다
	 * (sp-docs/domain-model.md §3.1). 서비스는 자기 DB 만 접근하므로 스키마 경계를 넘는 FK
	 * 제약은 금지이고, 걸면 서비스 간 배포·삭제 순서가 강결합된다 (sp-docs/architecture.md).
	 */
	@Test
	@DisplayName("member 테이블에 FOREIGN KEY 제약이 없다")
	void FK_제약이_없다() {
		List<?> foreignKeys = em.getEntityManager()
				.createNativeQuery("select constraint_name from information_schema.table_constraints "
						+ "where table_name = 'member' and constraint_type = 'FOREIGN KEY'")
				.getResultList();

		assertThat(foreignKeys).map(String::valueOf)
				.as("account_id 는 sp_auth.account.id 의 논리 참조다 (sp-docs/architecture.md)")
				.isEmpty();
	}

	/**
	 * {@code ddl-auto: validate} 는 길이 불일치를 통과시킨다 (sp-docs/tech-stack.md §5.2).
	 * 엔티티의 {@code @Column(length)} 와 마이그레이션의 실제 폭을 여기서 직접 맞춘다.
	 */
	@Test
	@DisplayName("information_schema 의 nickname 폭이 30 이고 엔티티의 @Column(length) 와 같다")
	void nickname_폭이_30이다() throws Exception {
		Number width = (Number) em.getEntityManager()
				.createNativeQuery("select character_maximum_length from information_schema.columns "
						+ "where table_name = 'member' and column_name = 'nickname'")
				.getSingleResult();

		assertThat(width.intValue())
				.as("V1__create_member.sql 의 VARCHAR 폭")
				.isEqualTo(NICKNAME_WIDTH);

		int declared = Member.class.getDeclaredField("nickname").getAnnotation(Column.class).length();
		assertThat(declared)
				.as("엔티티와 마이그레이션이 어긋나도 validate 는 통과한다. 여기서 막는다")
				.isEqualTo(width.intValue());
	}

	/**
	 * 폭이 넉넉한 컬럼은 읽기 쉬운 값으로 저장을 확인하고 경계는 따로 본다
	 * (sp-docs/conventions.md §9.2). MySQL {@code VARCHAR} 는 <b>문자 수</b>를 세므로 한글
	 * 30자가 들어간다 (sp-docs/domain-model.md §3.1).
	 */
	@Test
	@DisplayName("폭 경계인 30자 닉네임이 잘리지 않고 저장된다")
	void 경계_길이_닉네임() {
		String boundary = "닉".repeat(NICKNAME_WIDTH);
		Member saved = memberRepository.saveAndFlush(member(112L, boundary));
		em.clear();

		assertThat(memberRepository.findById(saved.getId()).orElseThrow().getNickname())
				.isEqualTo(boundary);
	}

	private static Member member(Long accountId, String nickname) {
		return Member.builder()
				.accountId(accountId)
				.nickname(nickname)
				.build();
	}

}
