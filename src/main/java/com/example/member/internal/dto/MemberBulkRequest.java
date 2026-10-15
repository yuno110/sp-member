package com.example.member.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 내부 API 의 벌크 조회 요청. 정본: sp-docs/api-contract.md §5 —
 * {@code {"accountIds": [1, 2, 5]}}.
 *
 * <p><b>{@code @NotNull} 이다. {@code @NotEmpty} 를 쓰지 않는다.</b> §5 가 누락·{@code null} 과
 * 빈 배열을 가른다 — 앞은 호출 측의 결함이므로 400 {@code C001} 이고, 뒤는 해석할
 * {@code accountId} 가 없는 정상 상황이므로 <b>200 과 빈 배열</b>이다.
 * {@code @NotEmpty} 는 둘을 같게 보므로 빈 배열이 400 이 되고, §5.1 이 member 의 모든 4xx 를
 * board 에서 503 {@code S001} 인프라 경보로 분류하므로 <b>정상 상황이 경보가 된다.</b>
 *
 * <p><b>건수 상한을 두지 않는다.</b> 초과가 400 이 되어 위와 같은 이유로 경보가 된다. 실질
 * 상한은 호출 측에 있다 — board 목록의 한 페이지가 최대 50건이다 (§4.1).
 */
public record MemberBulkRequest(
		@NotNull(message = "accountIds 는 필수입니다.")
		List<Long> accountIds) {

}
