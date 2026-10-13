package com.example.member.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * sp-docs/api-contract.md §8.1 §8.2 의 표를 그대로 옮긴 것과 enum 전체를 맞춘다.
 *
 * <p>집합 비교이므로 정본에 없는 코드가 하나라도 있으면 실패한다. {@code M002}·{@code M004}·
 * {@code M005} 를 다시 끌어오는 실수가 여기서 잡힌다 (§8.2 — 번호는 재사용하지 않는다).
 */
class ErrorCodeTest {

	@Test
	@DisplayName("ErrorCode 의 코드·HTTP 상태·메시지가 api-contract.md §8 과 정확히 일치한다")
	void 정본과_일치한다() {
		Map<String, String> contract = new LinkedHashMap<>();
		// §8.1 공통 (세 서비스)
		contract.put("C001", "400 잘못된 입력값입니다.");
		contract.put("C002", "400 잘못된 타입의 값입니다.");
		contract.put("C003", "405 지원하지 않는 HTTP 메서드입니다.");
		contract.put("C004", "404 요청한 리소스를 찾을 수 없습니다.");
		contract.put("C005", "500 서버 내부 오류가 발생했습니다.");
		contract.put("C006", "415 지원하지 않는 미디어 타입입니다.");
		contract.put("C007", "406 응답할 수 있는 형식이 없습니다.");
		contract.put("A001", "401 인증이 필요합니다.");
		contract.put("A002", "401 유효하지 않은 토큰입니다.");
		contract.put("A003", "401 만료된 토큰입니다.");
		contract.put("A004", "403 권한이 없습니다.");
		// §8.2 member-service
		contract.put("M001", "404 프로필을 찾을 수 없습니다.");
		contract.put("M003", "409 이미 사용 중인 닉네임입니다.");
		contract.put("M006", "409 이미 프로필이 등록된 계정입니다.");
		contract.put("M007", "409 탈퇴한 프로필입니다.");

		Map<String, String> implemented = Stream.of(ErrorCode.values())
				.collect(Collectors.toMap(
						ErrorCode::getCode,
						errorCode -> errorCode.getStatus().value() + " " + errorCode.getMessage(),
						(a, b) -> {
							throw new IllegalStateException("중복된 에러 코드: " + a);
						},
						LinkedHashMap::new));

		assertThat(implemented).containsExactlyInAnyOrderEntriesOf(contract);
	}

	@Test
	@DisplayName("auth 로 옮겨간 M002·M004·M005 를 쓰지 않는다")
	void 이동한_번호를_재사용하지_않는다() {
		assertThat(Stream.of(ErrorCode.values()).map(ErrorCode::getCode))
				.as("M002·M004·M005 는 AU002·AU003·AU004 로 옮겨갔고 번호를 재사용하지 않는다 (§8.2)")
				.doesNotContain("M002", "M004", "M005");
	}

	@Test
	@DisplayName("M001 의 메시지가 \"프로필을 찾을 수 없습니다.\" 다")
	void M001_메시지() {
		assertThat(ErrorCode.MEMBER_NOT_FOUND.getMessage()).isEqualTo("프로필을 찾을 수 없습니다.");
	}

}
