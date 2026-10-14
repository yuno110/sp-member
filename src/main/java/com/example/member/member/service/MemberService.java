package com.example.member.member.service;

import com.example.member.global.error.BusinessException;
import com.example.member.global.error.ErrorCode;
import com.example.member.member.dto.CheckResponse;
import com.example.member.member.dto.MemberResponse;
import com.example.member.member.dto.NicknameUpdateRequest;
import com.example.member.member.dto.ProfileCreateRequest;
import com.example.member.member.dto.ProfileRegistration;
import com.example.member.member.entity.Member;
import com.example.member.member.repository.MemberRepository;
import com.example.member.member.support.MemberReader;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 프로필 유스케이스. 정본: sp-docs/requirements/member.md §1 §2 §3 §4,
 * sp-docs/api-contract.md §2.3 §3 §9.1.
 *
 * <p>M-05 가 만들고 M-08·M-09·M-10 이 확장한다 (sp-docs/plan/phase1.md §2.3).
 *
 * <p><b>이메일·비밀번호를 다루지 않는다.</b> 프로필은 닉네임만 갖는다
 * (sp-docs/adr/0012-auth-as-separate-service.md).
 */
@Service
public class MemberService {

	/**
	 * {@code V1__create_member.sql} 이 선언한 닉네임 UNIQUE 제약이다.
	 *
	 * <p><b>{@code contains} 로 본다. {@code equals} 는 깨진다</b> — 방언이 이름을 변형한다.
	 * H2 가 돌려준 실측값은 {@code public.uk_member_nickname_INDEX_B} 다.
	 */
	private static final String UK_MEMBER_NICKNAME = "uk_member_nickname";

	private final MemberRepository memberRepository;

	/**
	 * 내 프로필을 읽는 지점. <b>없으면 {@code M001} 을 던진다</b> — 그 결론이 {@link MemberReader}
	 * 에 있다.
	 *
	 * <p><b>탈퇴 여부는 거기서 보지 않는다.</b> 상태별 결론은 호출하는 쪽이 정하므로
	 * {@link #myActiveProfile} 이 탈퇴를 가른다.
	 */
	private final MemberReader memberReader;

	/**
	 * <b>{@code PROPAGATION_REQUIRES_NEW} 다.</b> 이 전파 수준을 요구하는 이유가 둘이다.
	 *
	 * <ul>
	 * <li><b>영속성 컨텍스트</b> — flush 가 실패한 영속성 컨텍스트는 더 쓸 수 없고 그
	 * 트랜잭션은 롤백만 가능하므로, 경합에서 진 뒤 상태를 다시 읽으려면 트랜잭션이 새로
	 * 필요하다 ({@link #resolveIntegrityViolation}).
	 * <li><b>스냅샷</b> — 운영 DB 는 MySQL 이고 (sp-docs/tech-stack.md §1) <b>MySQL 의 기본
	 * 격리 수준은 REPEATABLE READ 다.</b> 바깥 트랜잭션에 합류하면 되읽기가 그 트랜잭션이
	 * 시작할 때의 스냅샷을 보는데, 그 시점은 경합 상대의 커밋보다 앞이다 — <b>되읽기가 빈
	 * 결과를 돌려준다.</b> 그러면 같은 계정의 선점을 못 보고 아래 분기가 이름 쪽으로 흘러
	 * sp-docs/api-contract.md §3 의 표와 어긋난다. 운영에서는 이쪽이 더 중요한 이유다
	 * </ul>
	 *
	 * <p><b>두 번째 이유는 이 저장소의 테스트가 검증하지 못한다.</b> 테스트 DB 는 H2 고 (§1)
	 * <b>H2 의 기본 격리 수준은 READ COMMITTED 다</b> — 바깥 트랜잭션에 합류한 되읽기도 경합
	 * 상대의 커밋을 보므로, 스냅샷 때문에 빈 결과가 돌아오는 상황 자체가 H2 에서 만들어지지
	 * 않는다. 운영에서만 드러난다는 뜻이고, 그래서 여기에 적어 둔다.
	 *
	 * <p>위 두 격리 수준은 <b>각 DBMS 의 기본값이다. 정본에 적혀 있지 않고</b>
	 * (sp-docs/tech-stack.md 는 §1 에서 제품과 버전만 고정한다) <b>이 저장소에서 실측한 값도
	 * 아니다.</b>
	 *
	 * <p>바깥 트랜잭션이 커넥션을 쥔 채로 하나를 더 쓴다 — 풀 크기가 1이면 멈춘다.
	 */
	private final TransactionTemplate freshRead;

	public MemberService(
			MemberRepository memberRepository,
			MemberReader memberReader,
			PlatformTransactionManager transactionManager) {

		this.memberRepository = memberRepository;
		this.memberReader = memberReader;
		this.freshRead = new TransactionTemplate(transactionManager);
		this.freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.freshRead.setReadOnly(true);
	}

	/**
	 * 가입 3단계 — 프로필 등록 (sp-docs/requirements/member.md §4).
	 *
	 * <p><b>{@code accountId} 기준으로 멱등이다</b> (규칙 7). 응답만 유실된 클라이언트가
	 * 재시도해도 중복 생성·덮어쓰기가 없다. <b>기존 상태별 결론은 sp-docs/api-contract.md §3 의
	 * 표가 정본이다</b> — 행이 없으면 생성, 활성 프로필의 닉네임이 같으면 기존 반환, 다르면
	 * {@code M006}, 탈퇴한 프로필이면 {@code M007} 이다.
	 *
	 * <p><b>프로필의 상태를 먼저 가른다.</b> 미등록(행이 없다)과 탈퇴({@code deleted = true})를 같은
	 * "없음" 으로 처리하면 탈퇴한 프로필이 재생성된다 (규칙 6,
	 * sp-docs/domain-model.md §3.1).
	 *
	 * <p><b>{@code MemberReader} 를 쓰지 않는다.</b> 그쪽은 "없으면 {@code M001}" 이지만 이
	 * 경로에서 행이 없는 것은 오류가 아니라 생성 조건이다.
	 *
	 * <p>아래 닉네임 사전 검사는 경합을 막지 못한다 — 확인과 커밋 사이에 창이 있다
	 * (sp-docs/api-contract.md §2.3). <b>최종 판정은 UNIQUE 제약이고</b> 그것을 밟았을 때의
	 * 결론은 {@link #resolveIntegrityViolation} 에 있다.
	 *
	 * @param accountId 검증된 JWT 의 {@code sub}. 요청 본문의 식별자를 쓰지 않는다
	 *         (sp-docs/security.md §4, sp-docs/api-contract.md §3)
	 */
	@Transactional
	public ProfileRegistration register(Long accountId, ProfileCreateRequest request) {
		Optional<Member> existing = this.memberRepository.findByAccountId(accountId);
		if (existing.isPresent()) {
			return resolveExisting(existing.get(), request.nickname());
		}

		if (this.memberRepository.existsByNickname(request.nickname())) {
			throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
		}

		Member profile = Member.builder()
				.accountId(accountId)
				.nickname(request.nickname())
				// deleted 를 지정하지 않는다 — 생성자가 false 로 둔다 (Member)
				.build();

		try {
			// saveAndFlush 다. save 만 하면 제약 위반이 커밋 시점(이 메서드 밖)에 터져
			// DataIntegrityViolationException 이 500 C005 로 새어 나간다
			return new ProfileRegistration(MemberResponse.from(this.memberRepository.saveAndFlush(profile)), true);
		} catch (DataIntegrityViolationException e) {
			return resolveIntegrityViolation(accountId, request.nickname(), e);
		}
	}

	/**
	 * INSERT 가 무결성 제약을 밟았을 때의 결론. <b>"확인–커밋 사이의 창"에서 일어난 일을
	 * 되읽어 가른다</b> — 사전 검사는 경합을 막지 못하고 최종 판정은 UNIQUE 제약이다
	 * (sp-docs/api-contract.md §2.3).
	 *
	 * <p><b>제약 이름만으로 가르지 않는다.</b> 같은 본문의 두 요청이 겹치면
	 * {@code uk_member_account_id} 와 {@code uk_member_nickname} 이 <b>함께</b> 깨지는데,
	 * 보고되는 이름은 하나다 — 어느 쪽인지는 <b>보장되지 않는다.</b> 그 경우에 닉네임 이름이
	 * 돌아오면 이름을 1차 분기로 쓰는 구현은 {@code M003} 을 내고, 그것은
	 * sp-docs/api-contract.md §3 의 표("활성 프로필, 닉네임 동일" → 200 기존 반환)와 어긋난다.
	 * <b>그래서 내 {@code accountId} 의 상태를 먼저 본다</b> — 시작할 때 행이 없었으므로 지금
	 * 행이 있다는 것은 같은 계정의 다른 요청이 선점했다는 뜻이고, 그러면 INSERT 가 실패한
	 * 이유는 어느 이름이 돌아왔든 그 행이다. 되읽기는 보고된 이름에 의존하지 않는다.
	 *
	 * <p>이 저장소의 H2 가 그 경우에 돌려준 실측값은 {@code uk_member_account_id} 쪽이었다
	 * ({@code public.uk_member_account_id_INDEX_B}). <b>그 값을 전제로 삼지 않는다</b> —
	 * 운영 DB 는 MySQL 이다 (sp-docs/tech-stack.md §1).
	 *
	 * <p><b>남은 셋을 섞지 않는다</b> (sp-docs/AGENTS.md 불변식 9).
	 *
	 * <ul>
	 * <li>내 {@code accountId} 의 행이 생겼다 → §3 의 표대로 200 / {@code M006} / {@code M007}
	 * <li>{@code uk_member_nickname} → {@code M003} (타인이 선점했다, §2.3)
	 * <li><b>그 밖 → 다시 던진다.</b> {@code created_at} NOT NULL 위반처럼 서버측 설정 오류가
	 * 이 자리로 오는데, 그것을 {@code M003} 으로 바꾸면 순수한 서버 결함이 "닉네임 중복" 으로
	 * 위장되고 {@code log.error} 도 남지 않는다. 500 {@code C005} 로 드러내는 것이 맞다
	 * </ul>
	 */
	private ProfileRegistration resolveIntegrityViolation(
			Long accountId, String requestedNickname, DataIntegrityViolationException e) {

		// flush 가 실패했으므로 이 트랜잭션은 롤백만 가능하다. 명시해 두지 않으면 아래에서
		// 정상 반환할 때 커밋 시도가 UnexpectedRollbackException 이 되어 200 이 500 으로 바뀐다
		TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

		Member concurrent = this.freshRead
				.execute(status -> this.memberRepository.findByAccountId(accountId).orElse(null));
		if (concurrent != null) {
			return resolveExisting(concurrent, requestedNickname);
		}

		if (constraintNameOf(e).contains(UK_MEMBER_NICKNAME)) {
			throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
		}

		throw e;
	}

	/**
	 * 깨진 제약의 이름. 없으면 빈 문자열이다 — NOT NULL 위반처럼 이름이 없는 경우가 있다.
	 *
	 * <p>{@link DataIntegrityViolationException} 자체는 제약 이름을 노출하지 않으므로 Hibernate
	 * 의 {@link ConstraintViolationException} 까지 원인 사슬을 내려간다.
	 */
	private static String constraintNameOf(DataIntegrityViolationException e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
				return violation.getConstraintName();
			}
		}
		return "";
	}

	/**
	 * MR-03 — 닉네임 중복 확인 (sp-docs/api-contract.md §2.3).
	 *
	 * <p><b>쓸 수 있으면 {@code true}</b> 다. 탈퇴한 프로필은 {@code nickname} 이 NULL 이라
	 * 조회에 걸리지 않으므로 해방된 닉네임은 자연히 {@code true} 가 된다 — 그것을 다시 찾아
	 * {@code false} 로 돌리는 분기를 넣지 않는다 (§2.3).
	 *
	 * <p>이 확인은 경합을 막지 못한다 — 확인과 등록 사이에 다른 사용자가 같은 닉네임을 쓸 수
	 * 있다. 최종 판정은 {@link #register} 의 {@code uk_member_nickname} 이다 (§2.3).
	 */
	@Transactional(readOnly = true)
	public CheckResponse checkNickname(String nickname) {
		return new CheckResponse(!this.memberRepository.existsByNickname(nickname));
	}

	/**
	 * MR-07 의 프로필 절반 — 내 프로필 조회 (sp-docs/requirements/member.md §5).
	 *
	 * <p>돌려주는 것은 {@code accountId}·{@code nickname} 이다 ({@link MemberResponse},
	 * sp-docs/api-contract.md §3.1). <b>이메일을 담지 않는다</b> — member 는 소유하지 않으므로
	 * 알지 못하고, 필요하면 클라이언트가 auth 의 {@code GET /api/v1/accounts/me} 를 따로 부른다
	 * (§5). <b>{@code createdAt} 도 담지 않는다</b> — MR-07 이 member 에 배정한 것은 닉네임뿐이고,
	 * 가입일을 함께 주는 것은 {@code GET /api/v1/members/{accountId}} 다
	 * (sp-docs/api-contract.md §3.1).
	 *
	 * <p>프로필이 없거나 탈퇴했으면 {@code M001} 이다 ({@link #myActiveProfile}).
	 */
	@Transactional(readOnly = true)
	public MemberResponse getMyProfile(Long accountId) {
		return MemberResponse.from(myActiveProfile(accountId));
	}

	/**
	 * MR-08 — 닉네임 변경 (sp-docs/requirements/member.md §7). 중복 검사 → 갱신 → <b>갱신된 프로필
	 * 반환</b>이고, 응답 모양은 {@code GET /me} 와 같다 (sp-docs/api-contract.md §3.1).
	 *
	 * <p><b>토큰을 반환하지 않는다.</b> Claim 에 {@code nickname} 이 없어 토큰이 낡을 이유가 없고
	 * (sp-docs/api-contract.md §6), member 는 개인키를 갖지 않으므로 발급할 수도 없다 (§7).
	 *
	 * <p><b>중복 검사는 자기 행을 제외한다</b> (§7). 제외하지 않으면 사전 검사가 자기 행을 찾아
	 * {@code M003} 을 내는데, 그것은 자기가 쓰고 있는 값을 "이미 사용 중" 이라고 거부하는 셈이고
	 * 바꾸지 않은 필드를 함께 보내는 평범한 수정 화면이 막힌다. <b>제외를 사전 검사 쪽에서 하는
	 * 이유</b>는 {@code uk_member_nickname} 이 이 경우 깨지지 않기 때문이다 — 같은 행을 같은 값으로
	 * 쓰므로 제약은 통과하고, 막는 것은 사전 검사뿐이다 (§7).
	 *
	 * <p><b>제외 기준은 {@code accountId} 다. 값 동일성이 아니다</b> (§7) — 그래서
	 * {@link MemberRepository#existsByNicknameAndAccountIdNot} 를 쓴다. 근거(콜레이션)와 이 저장소의
	 * 테스트가 그 입력을 재현하지 못하는 이유는 그 메서드의 javadoc 에 있다.
	 *
	 * <p><b>사전 검사는 경합을 막지 못한다.</b> 확인과 UPDATE 사이에 타인이 그 닉네임을 등록하면
	 * 최종 판정은 {@code uk_member_nickname} 이고, 그 결론도 409 {@code M003} 이다 (§7,
	 * sp-docs/api-contract.md §2.3 이 생성 경로에 정한 것과 같다). <b>500 으로 내보내지 않는다</b> —
	 * 클라이언트가 잘못한 것이 없는데 마지막 그물이 {@code log.error} 를 남기면 흔한 경합이 경보가
	 * 된다 (sp-docs/conventions.md §6.1 의 로그 수준 분리).
	 *
	 * <p><b>이 경로의 결론은 {@link #resolveIntegrityViolation} 보다 단순하다.</b> 여기에는
	 * {@code uk_member_account_id} 경합이 없다 — 자기 행을 {@code accountId} 로 집어 UPDATE 하므로
	 * 그 UNIQUE 를 건드리지 않고, 깨질 수 있는 UNIQUE 는 닉네임 하나다. 그래서 등록 쪽이 필요로 한
	 * "내 {@code accountId} 의 상태를 새 트랜잭션에서 되읽어 200/{@code M006}/{@code M007} 로
	 * 가르기" 가 필요 없고, 결론이 둘로 끝난다 — {@code uk_member_nickname} 이면 {@code M003},
	 * <b>그 밖은 다시 던진다</b> (§7). 후자를 {@code M003} 으로 돌리면 서버 결함이 "닉네임 중복" 으로
	 * 위장되고 {@code log.error} 도 남지 않는다.
	 */
	@Transactional
	public MemberResponse updateNickname(Long accountId, NicknameUpdateRequest request) {
		Member profile = myActiveProfile(accountId);
		String nickname = request.nickname();

		if (this.memberRepository.existsByNicknameAndAccountIdNot(nickname, accountId)) {
			throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
		}

		profile.updateNickname(nickname);

		try {
			// flush 다. 더티 체킹에 맡기면 UPDATE 가 커밋 시점(이 메서드 밖)에 발행되어 제약 위반이
			// 아래 catch 를 지나치고 500 C005 로 새어 나간다
			this.memberRepository.flush();
		} catch (DataIntegrityViolationException e) {
			if (constraintNameOf(e).contains(UK_MEMBER_NICKNAME)) {
				throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
			}
			throw e;
		}

		return MemberResponse.from(profile);
	}

	/**
	 * {@code /me} 가 다루는 내 프로필. <b>없으면 {@code M001}, 탈퇴했으면 똑같이 {@code M001}</b>
	 * 이다 — sp-docs/api-contract.md §3.1 의 표가 {@code GET /me} 와 {@code PATCH /me} 두 행에 같은
	 * 결론을 적는다.
	 *
	 * <p>앞은 {@link MemberReader} 가 내고 뒤는 여기서 낸다. 그쪽은 탈퇴 여부를 보지 않으며 상태별
	 * 결론을 호출하는 쪽에 맡긴다.
	 *
	 * <p><b>두 상태가 같은 코드로 나가는 것이 의도다.</b> {@code /me} 는 "내 프로필을 보러·고치러
	 * 왔다" 이고 탈퇴했으면 볼 것도 고칠 것도 없다. 반대로 {@code GET /{accountId}} 는 탈퇴한
	 * 프로필에 200 과 {@code deleted: true} 를 준다 (§3.1) — 그쪽은 글쓴이의 표시 이름이므로 글이
	 * 남아 있는 한 표시할 것이 있다. <b>그래서 "탈퇴 = 없음" 을 {@link MemberReader} 로 올리지
	 * 않는다</b> — 올리면 그 결론이 공개 조회와 프로필 등록({@code M007})에도 적용된다.
	 */
	private Member myActiveProfile(Long accountId) {
		Member profile = this.memberReader.getByAccountId(accountId);
		if (!profile.isActive()) {
			throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
		}
		return profile;
	}

	/**
	 * 행이 이미 있을 때의 결론. 활성·탈퇴를 가르는 자리다. 시작할 때 이미 있었던 경우와
	 * 경합에서 져 되읽은 경우가 같은 표를 쓰므로 {@link #register} 와
	 * {@link #resolveIntegrityViolation} 이 함께 호출한다.
	 *
	 * <p>탈퇴한 프로필은 {@code nickname} 이 NULL 이므로 닉네임 비교보다 <b>먼저</b> 거부한다.
	 * 순서를 바꾸면 NULL 과 요청값을 비교해 {@code M006} 이 나가고, 탈퇴에 배정된
	 * {@code M007} 이 영원히 도달하지 않는다 (sp-docs/api-contract.md §3 의 표).
	 */
	private static ProfileRegistration resolveExisting(Member profile, String requestedNickname) {
		if (profile.isDeleted()) {
			throw new BusinessException(ErrorCode.MEMBER_WITHDRAWN);
		}
		if (!profile.getNickname().equals(requestedNickname)) {
			// 덮어쓰지 않는다. 닉네임 변경은 PATCH /members/me 다 (M-08)
			throw new BusinessException(ErrorCode.MEMBER_ALREADY_REGISTERED);
		}
		return new ProfileRegistration(MemberResponse.from(profile), false);
	}

}
