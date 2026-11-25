package com.example.member.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import com.example.member.global.error.GlobalExceptionHandler;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.repository.MemberRepository;
import com.example.member.member.service.MemberService;
import com.example.member.support.TestTokenFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code src/main/resources/logback-spring.xml} 이 프로필 등록·닉네임 변경의 UNIQUE 충돌({@code uk_member_nickname}·
 * {@code uk_member_account_id})에 Hibernate 가 남기는 ERROR 줄만 버리는지 <b>실제 Hibernate 출력</b>으로 본다
 * (sp-docs/plan/phase2-auth-member.md M-17, sp-docs/conventions.md §8.1, sp-docs/tech-stack.md §4.8).
 *
 * <p><b>필터를 손으로 붙이지 않는다.</b> 받는 쪽은 로거에 단 {@link ListAppender} 이고, 필터는 Boot 가 읽은 설정 파일이 로거
 * 컨텍스트에 건 것이다 — TurboFilter 는 appender 로 가기 전에 로거 컨텍스트에서 판정하므로 버린 줄은 이 appender 에도 오지
 * 않는다. 그래서 설정 파일을 지우거나 필터 줄을 뺀 변이에서 충돌의 「ERROR 0」 이 빨개진다.
 *
 * <p><b>WARN 과 다른 ERROR 를 함께 단언한다.</b> 로거를 끄거나 수준을 올린 변이에서는 「ERROR 0」 이 녹색이다 — 실패할 수 없는
 * 단언이 되지 않게(sp-docs/process/review-policy.md §4.1) 같은 충돌의 WARN 한 줄과, 열 폭 위반의 ERROR 한 줄이 남는 것을 본다.
 *
 * <p><b>충돌은 H2 의 실제 위반이다.</b> 사전 검사의 대역이 그 행을 다른 커넥션에서 커밋한 뒤 「없음」 을 돌려준다 — 그러면 이
 * 요청의 INSERT·UPDATE 가 그 제약에 실제로 걸린다. {@code MemberServiceTest} 의 「확인–커밋 사이의 경합」 과 같은 자리를 연다.
 * 스파이의 나머지 메서드는 실제 빈에 위임하고, 스텁은 테스트가 끝나면 지워진다.
 *
 * <p><b>닉네임은 ASCII 로 쓴다.</b> H2 는 위반 문면에서 ASCII 밖의 문자를 유니코드 이스케이프로 싣는다 — 한글 닉네임
 * {@code 등록경합닉} 이 {@code U&'\\b4f1\\b85d\\acbd\\d569\\b2c9'} 로 실렸다(M-17 이 고치기 전에 쟀다). 한글이면 그 줄에 닉네임의
 * 글자가 그대로 있지 않아 「어느 줄에도 그 닉네임이 없다」 가 그 줄을 보지 못한다.
 *
 * <p>로거 이름은 리터럴로 적는다 — 필터의 상수를 가져다 쓰면 그 상수를 바꾼 변이에서 받는 쪽이 함께 움직인다. 받은 줄은 이
 * 테스트의 스레드 것만 본다 — MockMvc 는 부르는 스레드에서 요청을 처리한다.
 *
 * <p>픽스처의 {@code accountId} 는 341~349 를 쓴다(M-17). <b>정리는 seed 를 뺀 전체 행을 지운다</b> — sp-docs/tech-stack.md
 * §5.3 이 적은 member 의 방식이다. 다른 커넥션에서 넣은 행은 요청의 롤백과 무관하게 커밋되어 남으므로, 정리가 지우지 않으면 같은
 * JVM 의 다음 테스트에 남는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogbackSpringConfigTest {

	private static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

	/** {@code V2__seed_admin_profile.sql} 의 프로필 (sp-docs/requirements/member.md §10.1). */
	private static final Long SEED_ACCOUNT_ID = 1L;

	/** H2 의 UNIQUE 위반에 그 로거가 남기는 WARN 줄. */
	private static final String H2_UNIQUE_WARN = "SQL Error: 23505, SQLState: 23505";

	/** H2 의 열 폭 위반에 그 로거가 남기는 WARN 줄. */
	private static final String H2_VALUE_TOO_LONG_WARN = "SQL Error: 22001, SQLState: 22001";

	/** 「그 요청이 남긴 줄」 에서 빼는 SQL·바인드 로거 (M-17 의 검증 표). */
	private static final List<String> SQL_AND_BIND_LOGGERS = List.of("org.hibernate.SQL", "org.hibernate.orm.jdbc.bind");

	@DynamicPropertySource
	static void jwtPublicKey(DynamicPropertyRegistry registry) {
		TestTokenFactory.registerPublicKeyLocation(registry);
	}

	@Autowired
	private MockMvc mockMvc;

	/** 사전 검사에 끼워넣기를 거는 자리다. 대역 밖에서는 실제 빈에 위임한다. 스텁은 테스트가 끝나면 지워진다. */
	@MockitoSpyBean
	private MemberRepository memberRepository;

	/** 위 스파이를 주입받은 쪽이다. 요청으로 낼 수 없는 입력(열 폭 위반)을 넣는 자리다. */
	@Autowired
	private MemberService memberService;

	/**
	 * <b>끼워넣는 쪽의 커넥션이다.</b> {@code DataSourceUtils} 를 거치지 않고 풀에서 직접 받는다 — 이 스레드에 묶인 요청의
	 * 트랜잭션에 참여하면 끼운 행이 요청과 함께 롤백되어, 요청이 밟을 것이 다른 요청이 커밋한 행이 아니게 된다.
	 */
	@Autowired
	private DataSource dataSource;

	private final ListAppender<ILoggingEvent> 받은_줄 = new ListAppender<>();

	private Logger sqlExceptionHelper;

	@BeforeEach
	void 로거에_받는_쪽을_단다() {
		this.sqlExceptionHelper = (Logger) LoggerFactory.getLogger(SQL_EXCEPTION_HELPER);
		this.받은_줄.start();
		this.sqlExceptionHelper.addAppender(this.받은_줄);
	}

	/**
	 * <b>seed 행은 남긴다.</b> H2 는 테스트 클래스 사이에 공유되므로
	 * ({@code DB_CLOSE_DELAY=-1}, sp-docs/tech-stack.md §5.3) 여기서 지우면
	 * {@link com.example.member.member.AdminProfileSeedTest} 가 자기와 무관한 이유로 깨진다.
	 */
	@AfterEach
	void 정리() {
		this.sqlExceptionHelper.detachAppender(this.받은_줄);
		this.받은_줄.stop();
		this.memberRepository.deleteAll(this.memberRepository.findAll().stream()
				.filter(profile -> !SEED_ACCOUNT_ID.equals(profile.getAccountId()))
				.toList());
	}

	/**
	 * 검증 표 <b>「프로필 등록 — 사전 검사를 지난 뒤 타인이 그 닉네임을 쥔다」</b>. 내 {@code accountId} 는 겹치지 않으므로 밟히는
	 * 것은 {@code uk_member_nickname} 이고, 응답은 M-05 의 기대 그대로 409 {@code M003} 이다 (sp-docs/api-contract.md §2.3).
	 */
	@Test
	@DisplayName("프로필 등록의 닉네임 충돌은 409 M003 이고, 그 로거의 ERROR 없이 WARN 한 줄과 핸들러의 WARN 만 남고 닉네임이 없다")
	void 프로필_등록의_닉네임_충돌은_ERROR_를_남기지_않는다() throws Exception {
		String nickname = "regRace17";
		AtomicInteger 끼운_행 = new AtomicInteger(-1);
		willAnswer(invocation -> {
			끼운_행.set(다른_커넥션에서_넣는다(341L, nickname));
			return false;
		}).given(this.memberRepository).existsByNickname(nickname);

		보낸_요청 sent = 줄을_모으며_보낸다(등록(342L, nickname));

		assertThat(끼운_행.get()).as("끼운 행이 먼저 커밋돼야 이 요청의 INSERT 가 충돌한다").isEqualTo(1);
		sent.응답()
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("M003"));
		충돌의_로그를_본다(sent.줄(), "M003", nickname);
	}

	/**
	 * 검증 표 <b>「프로필 등록 — 사전 검사를 지난 뒤 같은 계정의 행이 다른 닉네임으로 생긴다」</b>. 같은 계정의 다른 요청이 먼저
	 * 등록한 것과 같다. 닉네임이 다르므로 밟히는 것은 {@code uk_member_account_id} 이고, 잡는 코드가 그 계정의 행을 되읽어 멱등
	 * 표대로 409 {@code M006} 을 낸다 (sp-docs/api-contract.md §3).
	 *
	 * <p><b>보고되는 제약 이름에 기대지 않는다.</b> 같은 계정·같은 본문이면 두 제약이 함께 깨지고 보고되는 이름은 하나인데, 어느
	 * 쪽인지는 보장되지 않는다({@code MemberService} 의 javadoc). 이 테스트의 단언은 이름을 보지 않는다. 다른 닉네임으로 행을 만드는
	 * 것은 이 줄이 {@code uk_member_account_id} 의 충돌만 내게 하려는 것이다 — 필터에서 그 이름을 뺀 변이가 여기서 빨개진다.
	 *
	 * <p>이 충돌이 깨는 것은 계정 id 의 UNIQUE 이므로, 닉네임과 함께 계정 id 도 어느 줄에도 없음을 본다.
	 */
	@Test
	@DisplayName("프로필 등록의 같은 계정 충돌은 409 M006 이고, 그 로거의 ERROR 없이 WARN 한 줄과 핸들러의 WARN 만 남고 닉네임·계정 id 가 없다")
	void 프로필_등록의_같은_계정_충돌은_ERROR_를_남기지_않는다() throws Exception {
		String nickname = "lateSent17";
		AtomicInteger 끼운_행 = new AtomicInteger(-1);
		willAnswer(invocation -> {
			끼운_행.set(다른_커넥션에서_넣는다(343L, "firstHeld"));
			return false;
		}).given(this.memberRepository).existsByNickname(nickname);

		보낸_요청 sent = 줄을_모으며_보낸다(등록(343L, nickname));

		assertThat(끼운_행.get()).as("끼운 행이 먼저 커밋돼야 이 요청의 INSERT 가 충돌한다").isEqualTo(1);
		sent.응답()
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("M006"));
		충돌의_로그를_본다(sent.줄(), "M006", nickname, "343");
	}

	/**
	 * 검증 표 <b>「닉네임 변경 — 사전 검사를 지난 뒤 타인이 그 닉네임을 쥔다」</b>. UPDATE 가 {@code uk_member_nickname} 에 걸리고,
	 * 응답은 M-08 의 기대 그대로 409 {@code M003} 이며 내 닉네임은 그대로다 (sp-docs/requirements/member.md §7).
	 */
	@Test
	@DisplayName("닉네임 변경의 충돌은 409 M003 이고 내 닉네임이 그대로이며, 그 로거의 ERROR 없이 WARN 한 줄과 핸들러의 WARN 만 남고 닉네임이 없다")
	void 닉네임_변경의_충돌은_ERROR_를_남기지_않는다() throws Exception {
		this.memberService.register(344L, new ProfileCreateRequest("before17"));
		String nickname = "renameR17";
		AtomicInteger 끼운_행 = new AtomicInteger(-1);
		willAnswer(invocation -> {
			끼운_행.set(다른_커넥션에서_넣는다(345L, nickname));
			return false;
		}).given(this.memberRepository).existsByNicknameAndAccountIdNot(nickname, 344L);

		보낸_요청 sent = 줄을_모으며_보낸다(닉네임_변경(344L, nickname));

		assertThat(끼운_행.get()).as("끼운 행이 먼저 커밋돼야 이 요청의 UPDATE 가 충돌한다").isEqualTo(1);
		sent.응답()
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("M003"));
		assertThat(this.memberRepository.findByAccountId(344L).orElseThrow().getNickname())
				.as("롤백됐으므로 옛 닉네임이 그대로다")
				.isEqualTo("before17");
		충돌의_로그를_본다(sent.줄(), "M003", nickname);
	}

	/**
	 * 검증 표 <b>「열 폭을 넘는 닉네임의 등록」</b> — 그 밖의 SQL 오류는 그 로거의 ERROR 로 남는다 (sp-docs/conventions.md §8.1 의
	 * 「그 로거의 ERROR 중 그 서비스의 제약 이름(위 표)이 든 줄만 버린다」 행). 변이 표의 「필터가 그 로거의 ERROR 를 모두
	 * 버린다」 를 이 줄이 잡는다.
	 *
	 * <p>입력은 {@code MemberServiceTest} 의 「UNIQUE 둘이 아닌 무결성 위반」 의 것이다 — {@code nickname} 은 VARCHAR(30) 이고
	 * 31자는 들어가지 않는다. 입력 검증이 2~10자로 막아 요청으로는 낼 수 없으므로 서비스를 부른다. Hibernate 를 거친다 —
	 * {@code JdbcTemplate} 은 {@code SqlExceptionHelper} 를 지나지 않는다.
	 */
	@Test
	@DisplayName("열 폭을 넘는 닉네임의 등록은 지금처럼 다시 던지고 그 로거의 ERROR 로 남는다")
	void 열_폭_위반은_ERROR_로_남는다() {
		assertThatThrownBy(() -> this.memberService.register(346L, new ProfileCreateRequest("가".repeat(31))))
				.as("잡는 코드는 그 밖의 위반을 다시 던진다 (M-05)")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(수준별(Level.ERROR)).as("그 밖의 SQL 오류는 ERROR 로 남는다 (§8.1)")
				.singleElement(STRING)
				.containsIgnoringCase("nickname");
		assertThat(수준별(Level.WARN)).as("WARN 줄도 그대로다").containsExactly(H2_VALUE_TOO_LONG_WARN);
	}

	/**
	 * 검증 표 <b>「루트 logger 의 appender」</b> — 콘솔만이다. Boot 의 기본 출력을 바꾸지 않는다 — {@code base.xml} 을 포함하면
	 * 파일 appender 가 함께 달린다 (sp-docs/tech-stack.md §4.8).
	 */
	@Test
	@DisplayName("루트 logger 에는 콘솔 appender 하나만 있고 수준은 INFO 다")
	void 루트에는_콘솔만_있다() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
		for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext();) {
			appenders.add(it.next());
		}

		assertThat(appenders).singleElement().isInstanceOf(ConsoleAppender.class);
		assertThat(root.getLevel()).isEqualTo(Level.INFO);
	}

	/**
	 * 검증 표 <b>「{@code logback-spring.xml} 의 내용」</b> — {@code <springProfile>} 이 없다 (sp-docs/tech-stack.md §4.8). 필터는
	 * 모든 프로파일에 같다. 프로파일마다 컨텍스트를 띄워 보는 테스트는 logback 의 초기화가 한 번뿐이라 실행 순서에 기댈 수 있어 파일을
	 * 읽어 본다(AU-25 의 같은 줄과 같다). 대소문자를 가리지 않고 찾는다 — logback 은 요소 이름을 대소문자 없이 맞춘다(AU-25 의
	 * logback-core {@code ElementSelector} 읽기).
	 */
	@Test
	@DisplayName("logback-spring.xml 은 springProfile 로 가르지 않는다")
	void 설정_파일은_프로파일로_가르지_않는다() throws IOException {
		ClassPathResource file = new ClassPathResource("logback-spring.xml");

		assertThat(file.exists()).as("Boot 가 읽는 자리에 설정 파일이 있다").isTrue();
		assertThat(file.getContentAsString(StandardCharsets.UTF_8)).doesNotContainIgnoringCase("<springProfile");
	}

	// --- 도우미 ---

	/**
	 * 실제 충돌 한 건의 로그 단언 — 검증 표의 「그 로거의 ERROR 0, WARN 한 줄」 과 「그 요청이 남긴 줄」.
	 *
	 * <p><b>그 요청이 남긴 줄을 모두 본다</b> (sp-docs/conventions.md §8.1 의 「버린다」 행 — 잡는 코드가 줄을 더하지 않고, 수준을
	 * 낮춰 다시 남기지 않는다). SQL·바인드 로거를 빼면 그 로거의 WARN 과 핸들러의 업무 예외 WARN 뿐이고, 어느 줄에도 그 값이 없다.
	 *
	 * <p>단언을 모아 낸다 — 하나가 깨져도 나머지의 결과가 함께 나와, 고치기 전과 변이의 빨강이 어느 단언의 것인지 보인다.
	 *
	 * @param 없어야_할_값 그 충돌의 값 — 요청한 닉네임, 계정 id 의 충돌이면 그 계정 id 도
	 */
	private void 충돌의_로그를_본다(List<ILoggingEvent> 그_요청의_줄, String code, String... 없어야_할_값) {
		SoftAssertions.assertSoftly(softly -> {
			softly.assertThat(수준별(Level.ERROR))
					.as("코드가 잡아 정본의 응답으로 바꾼 충돌은 그 로거의 ERROR 가 아니다 (§8.1)")
					.isEmpty();
			softly.assertThat(수준별(Level.WARN))
					.as("충돌의 흔적인 WARN 한 줄은 남는다 (§8.1)")
					.containsExactly(H2_UNIQUE_WARN);
			softly.assertThat(그_요청의_줄)
					.as("그 요청이 남긴 줄은 그 WARN 과 핸들러의 업무 예외 WARN 뿐이다 (§8.1, §6.1)")
					.extracting(ILoggingEvent::getLoggerName, ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
					.containsExactly(
							tuple(SQL_EXCEPTION_HELPER, Level.WARN, H2_UNIQUE_WARN),
							tuple(GlobalExceptionHandler.class.getName(), Level.WARN, "business exception: " + code));
			for (String value : 없어야_할_값) {
				softly.assertThat(그_요청의_줄).extracting(LogbackSpringConfigTest::줄의_전부)
						.as("어느 줄에도 %s 가 없다 — 예외가 실렸으면 그 원인 사슬의 메시지까지 본다", value)
						.allSatisfy(line -> assertThat(line).doesNotContainIgnoringCase(value));
			}
		});
	}

	/**
	 * 요청을 보내고, 그 요청이 이 스레드에 남긴 줄을 모은다 — SQL·바인드 로거의 줄은 뺀다. 루트 logger 에 받는 쪽을 달아 모으고,
	 * 수준을 낮춰 남긴 줄도 모이게 앱 패키지의 수준을 이 요청 동안 {@code TRACE} 로 둔다.
	 */
	private 보낸_요청 줄을_모으며_보낸다(MockHttpServletRequestBuilder request) throws Exception {
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		Logger app = (Logger) LoggerFactory.getLogger("com.example.member");
		ListAppender<ILoggingEvent> 모든_줄 = new ListAppender<>();
		모든_줄.start();
		Level original = app.getLevel();
		app.setLevel(Level.TRACE);
		root.addAppender(모든_줄);
		ResultActions response;
		try {
			response = this.mockMvc.perform(request);
		} finally {
			root.detachAppender(모든_줄);
			app.setLevel(original);
			모든_줄.stop();
		}
		String thread = Thread.currentThread().getName();
		return new 보낸_요청(response, 모든_줄.list.stream()
				.filter(event -> thread.equals(event.getThreadName()))
				.filter(event -> !SQL_AND_BIND_LOGGERS.contains(event.getLoggerName()))
				.toList());
	}

	/** 요청의 응답과, 그 요청이 이 스레드에 남긴 줄(SQL·바인드 로거 제외). */
	private record 보낸_요청(ResultActions 응답, List<ILoggingEvent> 줄) {
	}

	/** 그 로거가 이 테스트의 스레드에서 남긴, 그 수준의 줄. */
	private List<String> 수준별(Level level) {
		String thread = Thread.currentThread().getName();
		return this.받은_줄.list.stream()
				.filter(event -> thread.equals(event.getThreadName()))
				.filter(event -> event.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	/** 줄의 문면과, 예외가 실렸으면 그 원인 사슬의 메시지. */
	private static String 줄의_전부(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
		}
		return text.toString();
	}

	private static MockHttpServletRequestBuilder 등록(long accountId, String nickname) {
		return post("/api/v1/members")
				.header(HttpHeaders.AUTHORIZATION, bearer(accountId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\": \"" + nickname + "\"}");
	}

	private static MockHttpServletRequestBuilder 닉네임_변경(long accountId, String nickname) {
		return patch("/api/v1/members/me")
				.header(HttpHeaders.AUTHORIZATION, bearer(accountId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\": \"" + nickname + "\"}");
	}

	private static String bearer(long accountId) {
		return "Bearer " + TestTokenFactory.accessToken(accountId, "USER");
	}

	/**
	 * 활성 프로필 행을 <b>다른 커넥션에서</b> 커밋하고 영향 행 수를 돌려준다 — 다른 요청이 먼저 그 행을 만든 것과 같다. 행 수를
	 * 버리지 않는다 — 0행이면 밟을 제약이 없어 아래 단언들이 반증 불가능해진다 (sp-docs/process/review-policy.md §4.1).
	 *
	 * <p>짝이 되는 운영 코드는 {@code MemberService.register} 의 {@code saveAndFlush} 다 — 그 INSERT 와 같은 컬럼을 채운다
	 * ({@code created_at}·{@code updated_at} 은 운영에서는 {@code @EnableJpaAuditing} 이 채운다).
	 */
	private int 다른_커넥션에서_넣는다(long accountId, String nickname) {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"insert into member (account_id, nickname, deleted, created_at, updated_at) "
								+ "values (?, ?, ?, ?, ?)")) {
			LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
			statement.setLong(1, accountId);
			statement.setString(2, nickname);
			statement.setBoolean(3, false);
			statement.setObject(4, now);
			statement.setObject(5, now);
			return statement.executeUpdate();
		}
		catch (SQLException e) {
			throw new IllegalStateException("끼운 행이 커밋되지 않았다 — 이 측정은 충돌을 볼 수 없다", e);
		}
	}

}
