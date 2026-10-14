package com.example.member.member.dto;

/**
 * 프로필 등록의 결과. <b>응답 본문이 아니라 상태 코드를 가르기 위한 것이다.</b>
 *
 * <p>{@code POST /api/v1/members} 는 성공이 둘이다 — 행이 없었으면 <b>201</b>, 활성 프로필이
 * 이미 있고 닉네임이 같으면 <b>200</b>(기존 반환)이다 (sp-docs/api-contract.md §3 의 표).
 * 응답 본문은 두 경우가 같으므로 ({@link MemberResponse}) 어느 쪽인지는 서비스만 알 수 있고,
 * 컨트롤러가 다시 조회하지 않고 알 수 있도록 {@code created} 를 함께 돌려준다.
 *
 * @param profile 응답 본문에 실리는 값
 * @param created 이 호출이 행을 만들었으면 {@code true}. 멱등 재호출이면 {@code false}
 */
public record ProfileRegistration(MemberResponse profile, boolean created) { }
