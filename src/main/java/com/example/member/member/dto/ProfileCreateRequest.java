package com.example.member.member.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 프로필 등록 요청 (가입 3단계). 정본: sp-docs/api-contract.md §9.1, 검증 규칙은
 * sp-docs/requirements/member.md §2.
 *
 * <p><b>필드는 {@code nickname} 하나다.</b> 이메일·비밀번호는 auth 소유이므로 받지 않는다
 * (sp-docs/adr/0012-auth-as-separate-service.md). 본문에 그것들이 와도 Boot 의 기본 Jackson
 * 설정({@code FAIL_ON_UNKNOWN_PROPERTIES=false})이 무시하므로 저장될 경로가 없다 —
 * {@code member} 테이블에 그 컬럼 자체가 없다 (sp-docs/domain-model.md §3.1).
 *
 * <p><b>{@code accountId} 도 받지 않는다.</b> 검증된 JWT 의 {@code sub} 에서만 가져온다
 * (sp-docs/api-contract.md §3, sp-docs/requirements/member.md §3 규칙 7). 본문에 실어 보내도
 * 같은 이유로 무시된다.
 *
 * <p>검증 실패는 {@code C001}(400)이 되고 필드별 메시지가 {@code fieldErrors} 에 담긴다
 * (sp-docs/requirements/member.md §2, sp-docs/api-contract.md §7).
 */
public record ProfileCreateRequest(

		@NotNull(message = NICKNAME_MESSAGE)
		@Pattern(regexp = NICKNAME_PATTERN, message = NICKNAME_MESSAGE)
		String nickname) {

	/**
	 * sp-docs/requirements/member.md §2. 항목당 메시지가 하나이므로 두 제약이 같은 값을 쓴다.
	 *
	 * <p><b>{@code public} 인 이유</b> — {@code GET /members/check-nickname} 의
	 * {@code nickname} 파라미터가 <b>등록과 같은 규칙</b>을 써야 한다
	 * (sp-docs/api-contract.md §2.3). 규칙을 두 군데 적지 않기 위해
	 * {@code MemberController} 가 이 상수를 참조한다.
	 */
	public static final String NICKNAME_MESSAGE = "닉네임은 2~10자여야 합니다.";

	/**
	 * 2~10자, 한글/영문/숫자 (sp-docs/requirements/member.md §2).
	 *
	 * <p><b>"한글" 의 범위는 sp-docs/requirements/member.md §2.2 가 정본이다</b> — 완성형
	 * 음절과 호환 자모를 모두 받는다. 이 정규식은 그 세 범위를 옮긴 것이고 구현이 고른 값이
	 * 아니다.
	 *
	 * <ul>
	 * <li>완성형 음절 {@code 가-힣} — U+AC00–U+D7A3
	 * <li>호환 자모 자음 {@code ㄱ-ㅎ} — U+3131–U+314E ({@code ㅋㅋ}·{@code ㅎㅎ})
	 * <li>호환 자모 모음 {@code ㅏ-ㅣ} — U+314F–U+3163 ({@code ㅠㅠ})
	 * </ul>
	 *
	 * <p><b>자모를 받는 이유는 둘이다</b> (§2.2). 하나는 {@code ㅋㅋ}·{@code ㅎㅎ}·{@code ㅠㅠ}
	 * 가 실제로 쓰이는 닉네임이라는 것이고, <b>자모 허용은 이 첫째 이유만으로도 선다.</b> 다른
	 * 하나는 거부 메시지다 — 메시지가 항목당 하나이므로 {@code ㅋㅋ} 를 거부하면 2자 입력에
	 * "2~10자여야 합니다" 가 나가 길이를 탓한다. 모음만으로 된 {@code ㅏㅏ} 도 통과하며, 그것은
	 * 정본이 받아들인 비용이다.
	 *
	 * <p>공백·구두점·이모지는 집합 밖이라 거부된다 — {@code 닉 네임}·{@code nick!} 은 400 이다.
	 *
	 * <p>길이도 이 패턴이 센다 — {@code @Size} 를 따로 걸지 않는 이유는 그러면 1자 입력이 두
	 * 제약을 동시에 깨뜨려 {@code fieldErrors} 에 같은 필드가 두 번 담기기 때문이다
	 * (sp-docs/api-contract.md §2.3 은 <b>중복 확인 응답에</b> 해당 필드 1건으로 고정했다 — 이
	 * 상수는 그 경로의 {@code @RequestParam} 에도 쓰이므로 등록 본문도 같은 모양을 맞춘다).
	 * {@code @NotNull} 을 {@code @NotBlank} 대신 쓴 것도 같은 이유다 — 빈 문자열은 이 패턴이
	 * 거부한다.
	 */
	public static final String NICKNAME_PATTERN = "^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]{2,10}$";

}
