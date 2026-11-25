package com.example.member.global.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import java.util.Locale;
import org.slf4j.Marker;

/**
 * 프로필 등록·닉네임 변경의 UNIQUE 충돌에 Hibernate 가 남기는 ERROR 줄만 버린다 (sp-docs/conventions.md §8.1 의 표의 member
 * 행). {@code src/main/resources/logback-spring.xml} 이 로거 컨텍스트에 건다 — 프로파일을 가리지 않는다
 * (sp-docs/tech-stack.md §4.8).
 *
 * <p><b>버리는 것</b>은 {@code org.hibernate.engine.jdbc.spi.SqlExceptionHelper} 의 ERROR 중 아래 제약 이름이 든 줄(대소문자
 * 무시, 부분 일치)이다. {@code MemberService} 가 그 충돌을 잡아 정본의 응답으로 바꾼다 — {@code uk_member_nickname} 은 프로필
 * 등록과 닉네임 변경의 409 {@code M003}, {@code uk_member_account_id} 는 프로필 등록이 그 계정의 행을 되읽어 따르는 멱등 표의
 * 응답이다 (sp-docs/api-contract.md §2.3 §3, sp-docs/requirements/member.md §7). 그 줄에는 중복 값으로 닉네임이나 계정 id 가
 * 실린다(H2 에서 보았다 — MySQL 은 §8.1 의 추정이다). 같은 충돌의 WARN {@code SQL Error: …} 줄, 로거의 수준, 다른 제약의
 * 위반과 다른 SQL 오류의 ERROR 는 그대로다 — 이유는 §8.1 의 규칙 표다.
 *
 * <p><b>같은 계정·같은 본문의 등록 경합은 어느 이름이 보고되든 버린다.</b> 그 경합은 {@code uk_member_account_id} 와
 * {@code uk_member_nickname} 을 함께 깨고 보고되는 이름은 하나인데, 어느 쪽인지는 보장되지 않는다({@code MemberService} 의
 * javadoc). 아래 목록이 그 둘을 모두 가지므로 결과가 같다.
 *
 * <p><b>부분 일치다.</b> H2 는 프로젝트의 테스트 URL 에서 {@code "public.uk_member_nickname_INDEX_B ON …"}·
 * {@code "public.uk_member_account_id_INDEX_B ON …"} 로 냈다 (2026-11-25 M-17 실측). MySQL 은
 * {@code Duplicate entry '…' for key 'member.uk_member_nickname'} 모양으로 낼 것으로 본다 (§8.1 의 추정 — 실 MySQL 은 재지
 * 않았다). 대소문자는 DB 와 설정에 따라 다르다.
 *
 * <p><b>문면이 {@code null} 인 호출은 판정하지 않는다({@link FilterReply#NEUTRAL}).</b> logback 은 {@code isErrorEnabled()}
 * 같은 수준 검사에서도 TurboFilter 를 문면 없이 부른다. Hibernate 의 {@code logExceptions} 는 그 검사가 거짓이면 WARN 도
 * 남기지 않으므로, 거기서 버리면 WARN 과 다른 ERROR 가 모두 사라진다 (§8.1 의 「로거의 수준을 바꾸지 않는다」 행).
 *
 * <p><b>제약을 더하려면 정본이 먼저다</b> (§8.1). 이름은 sp-docs/domain-model.md §3.2 의 이름 그대로다. 필터 클래스는 서비스마다
 * 각자 쓴다 — 공유 모듈을 만들지 않는다 (sp-docs/conventions.md §7.2).
 */
public class ExpectedUniqueConflictLogFilter extends TurboFilter {

	static final String SQL_EXCEPTION_HELPER = "org.hibernate.engine.jdbc.spi.SqlExceptionHelper";

	/** 프로필 등록·닉네임 변경이 잡아 정본의 응답으로 바꾸는 충돌의 제약 — 소문자로 두고 문면을 소문자로 바꿔 맞춘다. */
	static final List<String> EXPECTED_CONFLICT_CONSTRAINTS = List.of("uk_member_nickname", "uk_member_account_id");

	@Override
	public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
			Throwable t) {
		if (format == null || level != Level.ERROR || !SQL_EXCEPTION_HELPER.equals(logger.getName())) {
			return FilterReply.NEUTRAL;
		}
		String message = format.toLowerCase(Locale.ROOT);
		for (String constraint : EXPECTED_CONFLICT_CONSTRAINTS) {
			if (message.contains(constraint)) {
				return FilterReply.DENY;
			}
		}
		return FilterReply.NEUTRAL;
	}

}
