package com.example.member.member.dto;

/**
 * 중복 확인 응답. 정본: sp-docs/api-contract.md §2.3 — {@code { "available": boolean }}.
 *
 * <p><b>쓸 수 있으면 {@code true}</b> 다. {@code duplicated} 같은 부정형을 쓰지 않는다 — 이중
 * 부정이 생겨 호출 측이 뒤집어 읽는다. auth 의 {@code check-email} 도 같은 형태를 쓴다.
 *
 * <p><b>탈퇴로 해방된 닉네임은 {@code true} 다.</b> 탈퇴 시 {@code nickname} 이 NULL 로
 * 비워져 조회에 걸리는 행이 없기 때문이며, 특별한 분기가 아니라 자연한 결과다 (§2.3,
 * sp-docs/domain-model.md §3.1). 이메일은 행이 남아 {@code false} 이므로 <b>둘 중 닉네임만
 * 해방된다</b>.
 */
public record CheckResponse(boolean available) { }
