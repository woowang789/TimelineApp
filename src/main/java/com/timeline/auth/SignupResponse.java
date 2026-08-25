package com.timeline.auth;

/**
 * 회원가입 응답 — 식별에 필요한 최소 정보만 담는다.
 *
 * <p>비밀번호는 평문이든 해시든 이 record에 필드가 없다. 응답에 넣지 않는 게 아니라
 * <strong>넣을 자리를 만들지 않는다</strong>.
 */
public record SignupResponse(Long id, String username, String nickname) {
}
