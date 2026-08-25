package com.timeline.user;

/**
 * 로그인 검증에 필요한 최소 정보 — {@link UserService}가 auth 도메인에 넘겨주는 형태다.
 *
 * <p>{@link User} 엔티티를 그대로 넘기지 않는다(경계 규칙 1). 엔티티를 넘기면 auth가
 * 영속 상태의 객체를 들고 다니게 되고, 그때부터 "auth에서 닉네임도 바꿀 수 있지 않나"가 시작된다.
 *
 * @param id              사용자 id — 토큰의 subject가 된다
 * @param encodedPassword BCrypt 해시. 대조는 auth가 한다(비밀번호 검증은 인증의 일이다)
 */
public record UserCredentials(Long id, String encodedPassword) {
}
