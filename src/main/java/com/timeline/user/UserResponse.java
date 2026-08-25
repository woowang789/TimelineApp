package com.timeline.user;

/**
 * 사용자 단건 조회 응답 ({@code GET /api/v1/users/{userId}}).
 *
 * <p>{@link User} 엔티티를 그대로 직렬화하지 않는다 — 그러면 {@code password}(BCrypt 해시)와
 * {@code createdAt}까지 응답에 실린다. 노출 필드는 record에 적힌 것이 전부다.
 *
 * @param id            사용자 id
 * @param username      로그인 식별자
 * @param nickname      표시 이름
 * @param followerCount 팔로워 수. self-follow 행은 세지 않으므로 가입 직후에는 0이다(&sect;4.3)
 * @param influencer    Hybrid 분기 플래그(&sect;7.2). Phase 3까지는 승격 로직이 없어 항상 false다
 */
public record UserResponse(Long id, String username, String nickname, int followerCount, boolean influencer) {

	static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getUsername(), user.getNickname(),
				user.getFollowerCount(), user.isInfluencer());
	}
}
