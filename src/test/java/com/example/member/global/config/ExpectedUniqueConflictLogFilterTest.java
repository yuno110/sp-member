package com.example.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 버리는 조건을 MySQL 의 문면으로 고정한다 (sp-docs/plan/phase2-auth-member.md M-17 의 완료 기준 (f), 검증 표의 단위 줄).
 * H2 의 녹색은 MySQL 의 문면에서 맞춘다는 근거가 아니다 — 실제 출력은 {@link LogbackSpringConfigTest} 가 H2 로 보고, 실 MySQL 은
 * 이 항목이 재지 않는다 (I-15 가 잰다).
 *
 * <p>중복 키 위반의 문면은 MySQL 의 모양({@code Duplicate entry '…' for key '<테이블>.<제약>'}, sp-docs/conventions.md §8.1)이다.
 * 다른 SQL 오류의 문면도 MySQL 의 모양으로 적었다 — 열 폭·NOT NULL 위반은 M-17 의 완료 기준 (b) 가 드는 「그 밖의 SQL 오류」 의
 * 예다. <b>중복 값은 지어낸 것이다.</b>
 *
 * <p>로거 이름과 제약 이름은 리터럴로 적는다 — 필터의 상수를 가져다 쓰면 그 상수를 바꾼 변이에서 기대가 함께 움직인다.
 */
class ExpectedUniqueConflictLogFilterTest {

	private static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

	private static final String NICKNAME_CONFLICT = "Duplicate entry 'madeUpNick' for key 'member.uk_member_nickname'";

	private final LoggerContext context = new LoggerContext();

	private final ExpectedUniqueConflictLogFilter filter = new ExpectedUniqueConflictLogFilter();

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			NICKNAME_CONFLICT,
			"Duplicate entry '900017' for key 'member.uk_member_account_id'",
			"Duplicate entry 'madeUpNick' for key 'MEMBER.UK_MEMBER_NICKNAME'",
			"Duplicate entry '900017' for key 'MEMBER.UK_MEMBER_ACCOUNT_ID'"})
	@DisplayName("그 로거의 ERROR 중 uk_member_nickname·uk_member_account_id 가 든 줄은 대소문자와 무관하게 버린다")
	void 프로필_충돌의_ERROR_는_버린다(String message) {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.ERROR, message)).isEqualTo(FilterReply.DENY);
	}

	/**
	 * 검증 표 「단위 — 다른 키 이름(예: {@code member.PRIMARY}), 교착의 {@code Deadlock found …}, 다른 로거, 같은 문면의 WARN」 중
	 * 문면의 줄이다 — 다른 로거와 WARN 은 아래 테스트가 본다. 열 폭·NOT NULL 위반의 문면은 열 이름({@code nickname}·
	 * {@code account_id})을 싣지만 제약 이름은 없다 — 제약 이름이 아니라 열 이름으로 맞추는 필터를 이 줄들이 가른다.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"Duplicate entry '900017' for key 'member.PRIMARY'",
			"Deadlock found when trying to get lock; try restarting transaction",
			"Data too long for column 'nickname' at row 1",
			"Column 'account_id' cannot be null"})
	@DisplayName("그 로거의 다른 키 이름·다른 SQL 오류의 ERROR 는 버리지 않는다")
	void 다른_ERROR_는_버리지_않는다(String message) {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.ERROR, message)).isEqualTo(FilterReply.NEUTRAL);
	}

	@Test
	@DisplayName("같은 문면이라도 다른 로거의 ERROR 는 버리지 않는다")
	void 다른_로거는_버리지_않는다() {
		assertThat(decide("com.example.member.global.error.GlobalExceptionHandler", Level.ERROR, NICKNAME_CONFLICT))
				.isEqualTo(FilterReply.NEUTRAL);
		assertThat(decide("org.hibernate.engine.jdbc.spi", Level.ERROR, NICKNAME_CONFLICT))
				.as("그 로거의 부모 이름도 그 로거가 아니다")
				.isEqualTo(FilterReply.NEUTRAL);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"WARN", "INFO", "DEBUG"})
	@DisplayName("그 로거의 같은 문면이라도 ERROR 가 아닌 수준은 버리지 않는다")
	void ERROR_가_아닌_수준은_버리지_않는다(String level) {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.toLevel(level), NICKNAME_CONFLICT)).isEqualTo(FilterReply.NEUTRAL);
	}

	/**
	 * 검증 표 「단위 — 문면이 {@code null} 인 호출(수준 검사, 예: {@code isErrorEnabled})」. logback 은 {@code isErrorEnabled()}
	 * 에서 TurboFilter 를 문면 없이 부른다 — 거기서 버리면 {@code logExceptions} 의 진입 검사가 거짓이 되어 WARN·다른 ERROR 가 모두
	 * 사라진다 (sp-docs/conventions.md §8.1 의 「로거의 수준을 바꾸지 않는다」 행).
	 */
	@Test
	@DisplayName("문면이 null 인 호출(수준 검사)은 버리지 않는다")
	void 문면이_없는_수준_검사는_버리지_않는다() {
		assertThat(decide(SQL_EXCEPTION_HELPER, Level.ERROR, null)).isEqualTo(FilterReply.NEUTRAL);

		Logger logger = this.context.getLogger(SQL_EXCEPTION_HELPER);
		this.filter.start();
		this.context.addTurboFilter(this.filter);
		assertThat(logger.isErrorEnabled()).as("필터를 건 컨텍스트에서도 그 로거의 ERROR 는 켜져 있다").isTrue();
		assertThat(logger.isWarnEnabled()).isTrue();
	}

	private FilterReply decide(String loggerName, Level level, String message) {
		return this.filter.decide(null, this.context.getLogger(loggerName), level, message, null, null);
	}

}
