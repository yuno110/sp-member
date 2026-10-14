package com.example.member.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADMIN 프로필 seed 를 검증한다 (sp-docs/plan/phase1.md §5 M-05 의 seed 관련 완료 기준).
 * 값의 정본은 sp-docs/requirements/member.md §10.1, 비밀번호 취급은 §10.2 다.
 *
 * <p><b>이 테스트는 두 스키마 중 member 쪽만 본다.</b> auth 의
 * {@code V3__seed_admin_account.sql} 과 {@code account.id} 가 같은지는 서비스 둘이 함께 떠
 * 있어야 하므로 통합 검증 I-01 의 몫이다 (sp-docs/plan/integration.md). 양쪽이 각자 정본의
 * 값 {@code 1} 과 대조하므로 한쪽이 어긋나면 그쪽 테스트가 깨진다.
 *
 * <p>{@code @Import(JpaConfig.class)} 를 붙이지 않았다. 이 테스트는 엔티티를 저장하지 않으므로
 * {@code @EnableJpaAuditing} 이 쓰이지 않는다 (sp-docs/conventions.md §9.3).
 * {@code replace = NONE} 은 모든 {@code @DataJpaTest} 에 필요하다 — 없으면 DataSource URL 이
 * 덮여 Flyway 가 적용한 DB 가 아닌 곳을 보게 된다 (sp-docs/tech-stack.md §5.2).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AdminProfileSeedTest {

	private static final String SEED_SCRIPT = "db/migration/V2__seed_admin_profile.sql";

	/**
	 * sp-docs/requirements/member.md §10.1 이 확정한 값이다. <b>리터럴로 적는다</b> — 구현의
	 * 상수를 가져다 쓰면 그 상수가 정본에서 벗어나도 통과한다.
	 */
	private static final long SEED_ACCOUNT_ID = 1L;

	private static final String SEED_NICKNAME = "관리자";

	/** 마이그레이션 번호는 계획이 배정했다 (sp-docs/plan/phase1.md §2.4). */
	private static final String SEED_MIGRATION_VERSION = "2";

	/**
	 * <b>{@code NOW()} 의 하한이다.</b> seed 는 Flyway 마이그레이션이므로 이 JVM 이 뜬 뒤에
	 * 적용된다 — 적용 시각이 그보다 이르다면 스크립트가 {@code NOW()} 가 아니라 고정 상수를
	 * 박은 것이다 (sp-docs/requirements/member.md §10.1).
	 *
	 * <p>초로 내림한다. 컬럼이 초 단위로 끊는 환경에서 JVM 기동과 마이그레이션이 같은 초에
	 * 들어가면 아래 단언이 1초 미만 차이로 거짓이 될 수 있다.
	 */
	private static final LocalDateTime JVM_기동_시각 = LocalDateTime
			.ofInstant(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
					ZoneId.systemDefault())
			.truncatedTo(ChronoUnit.SECONDS);

	@Autowired
	private TestEntityManager em;

	@Autowired
	private MemberRepository memberRepository;

	/**
	 * <b>{@code created_at}·{@code updated_at} 을 {@code isNotNull()} 로만 보지 않는다.</b>
	 * 정본이 정한 값은 {@code NOW()} — <b>seed 적용 시각</b>이고, 고정 상수를 박으면 "언제
	 * 만들어진 프로필인가" 가 거짓이 된다 (sp-docs/requirements/member.md §10.1).
	 * {@code isNotNull()} 은 {@code '2020-01-01 00:00:00'} 이 박힌 스크립트도 통과시킨다.
	 */
	@Test
	@DisplayName("seed 가 적용되어 account_id 1 의 프로필이 활성 상태로 있다")
	void seed_가_적용되어_있다() {
		Member admin = this.memberRepository.findByAccountId(SEED_ACCOUNT_ID).orElseThrow();

		assertThat(admin.getNickname()).isEqualTo(SEED_NICKNAME);
		assertThat(admin.isDeleted())
				.as("seed 프로필은 탈퇴 상태가 아니다")
				.isFalse();
		assertThat(admin.getCreatedAt())
				.as("created_at 은 NOW() 다 — 이 실행에서 적용된 시각이어야 한다 (§10.1). "
						+ "seed 는 SQL 이므로 JPA 감사를 거치지 않는다")
				.isAfterOrEqualTo(JVM_기동_시각);
		assertThat(admin.getUpdatedAt())
				.as("updated_at 도 NOW() 다 (§10.1)")
				.isAfterOrEqualTo(JVM_기동_시각);
	}

	/**
	 * 닉네임으로 거꾸로 찾아 {@code account_id} 를 읽는다. auth 의 seed 가
	 * {@code account.id} 를 {@code 1} 로 명시 고정했고 이쪽이 그 값을 참조한다 — AUTO_INCREMENT
	 * 에 맡기면 환경마다 달라진다 (§10.1).
	 */
	@Test
	@DisplayName("SELECT account_id FROM member WHERE nickname = '관리자' 가 1 이다")
	void 네이티브_조회로도_1_이다() {
		Object accountId = this.em.getEntityManager()
				.createNativeQuery("select account_id from member where nickname = :nickname")
				.setParameter("nickname", SEED_NICKNAME)
				.getSingleResult();

		assertThat(((Number) accountId).longValue())
				.as("member 의 seed 가 auth 의 account.id 를 참조하는 값이다 (§10.1). "
						+ "두 seed 의 일치는 I-01 이 확인한다")
				.isEqualTo(SEED_ACCOUNT_ID);
	}

	/** 계획이 배정한 번호로 적용돼야 한다. 번호를 바꾸면 이미 적용된 DB 에서 체크섬이 깨진다. */
	@Test
	@DisplayName("seed 가 V2 로 적용되어 flyway_schema_history 에 성공으로 남는다")
	void V2_로_적용된다() {
		Object count = this.em.getEntityManager()
				.createNativeQuery("select count(*) from flyway_schema_history "
						+ "where version = :version and success = true")
				.setParameter("version", SEED_MIGRATION_VERSION)
				.getSingleResult();

		assertThat(((Number) count).intValue()).isEqualTo(1);
	}

	/**
	 * <b>member 의 seed 는 Flyway placeholder 를 쓰지 않는다</b> — 비밀번호를 갖지 않으므로
	 * 주입할 비밀 값이 없다 (sp-docs/requirements/member.md §10.2).
	 *
	 * <p>이 단언이 지키는 것은 auth 의 {@code V3} 를 베껴 오는 변경이다. 그쪽은
	 * {@code ${adminPasswordHash}} 를 쓰고 가드({@code FlywayConfigurationCustomizer})가 함께
	 * 있어야 미주입이 조용히 통과하지 않는데, member 에는 그 가드가 없다. placeholder 가 들어
	 * 오면 환경변수 없이도 리터럴이 그대로 저장된다.
	 *
	 * <p>값이 실제로 그 행에 들어갔는지는 DB 를 읽는 위 테스트들이 확인한다. 여기서는
	 * 스크립트 본문만 본다 — placeholder 의 부재는 DB 에서 관측할 수 없다.
	 */
	@Test
	@DisplayName("seed SQL 에 Flyway placeholder 가 없다")
	void seed_SQL_에_placeholder_가_없다() throws IOException {
		String sql = new ClassPathResource(SEED_SCRIPT).getContentAsString(StandardCharsets.UTF_8);

		assertThat(sql)
				.as("member 는 비밀번호를 갖지 않으므로 주입할 비밀 값이 없다 (§10.2)")
				.doesNotContain("${");
	}

}
