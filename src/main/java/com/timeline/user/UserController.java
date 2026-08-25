package com.timeline.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 조회 API (작업 0.9 · 마스터 &sect;6).
 *
 * <p>인증이 필요한 경로다 — {@code /api/v1/auth/**} 외에는 전부 보호 대상이라는 기본값(0.8)을 따른다.
 * 다만 <strong>누가 보든 같은 응답</strong>이라 {@code @AuthenticationPrincipal}을 받지 않는다.
 * "내가 이 사용자를 팔로우 중인가"는 여기 넣지 않았다 — 0.9의 범위가 아니고,
 * 넣으면 조회 한 번에 follows 조회가 딸려 붙는다.
 *
 * <p>같은 {@code /api/v1/users} 아래의 팔로우 관련 경로는 {@code FollowController}에 있다.
 * URL은 사용자 자원 아래에 있지만 다루는 것은 팔로우 관계이고, 컨트롤러는 도메인 패키지를 따른다.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/{userId}")
	public UserResponse getUser(@PathVariable Long userId) {
		return userService.getUser(userId);
	}
}
