package com.timeline.user;

/**
 * 목록 항목으로 쓰는 사용자 요약 — {@link UserService}가 타 도메인에 넘겨주는 형태다.
 *
 * <p>팔로워/팔로잉 목록(0.10)의 항목이 이것이다. follow 도메인은 자기 테이블에서 상대방의
 * <em>id만</em> 얻을 수 있으므로 이름을 채우려면 users를 읽어야 하는데,
 * 그 조회를 follow의 Repository가 JOIN으로 직접 하면 경계 규칙 1이 깨진다.
 * 그래서 <strong>id 목록을 주고 이 record를 받아 가는</strong> 형태로 통로를 하나만 낸다
 * ({@link UserService#findSummaries}). {@link UserCredentials}가 auth에 대해 하는 역할과 같다.
 *
 * @param id       사용자 id
 * @param username 로그인 식별자
 * @param nickname 표시 이름
 */
public record UserSummary(Long id, String username, String nickname) {
}
