package com.timeline.follow;

import com.timeline.common.api.CursorPageResponse;
import com.timeline.user.UserSummary;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 팔로우 API (작업 0.9 · 0.10 · 마스터 &sect;6).
 *
 * <p>경로는 {@code /api/v1/users} 아래지만 컨트롤러는 follow 패키지에 있다 —
 * URL은 자원의 위치를 나타내고, 패키지는 도메인 경계를 나타낸다. 둘이 항상 같을 필요는 없다.
 *
 * <p><strong>행위자는 언제나 토큰의 주인이다.</strong> {@code {userId}}는 대상이고,
 * "누가 팔로우하는가"는 {@code @AuthenticationPrincipal}로만 정해진다. 요청 본문이나 쿼리로
 * follower를 받으면 남의 이름으로 팔로우하는 API가 된다. 그래서 팔로우/언팔로우에는 본문이 아예 없다.
 */
@RestController
@RequestMapping("/api/v1/users")
public class FollowController {

	private final FollowService followService;

	public FollowController(FollowService followService) {
		this.followService = followService;
	}

	/** 팔로우. 관계(follows 행)가 새로 생기므로 201이고, 돌려줄 표현이 없어 본문은 비운다. */
	@PostMapping("/{userId}/follow")
	@ResponseStatus(HttpStatus.CREATED)
	public void follow(@PathVariable("userId") Long targetUserId, @AuthenticationPrincipal Long loginUserId) {
		followService.follow(loginUserId, targetUserId);
	}

	@DeleteMapping("/{userId}/follow")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unfollow(@PathVariable("userId") Long targetUserId, @AuthenticationPrincipal Long loginUserId) {
		followService.unfollow(loginUserId, targetUserId);
	}

	/**
	 * 팔로워 목록. {@code cursor}가 없으면 첫 페이지다.
	 *
	 * <p>{@code size} 기본값 20은 마스터 &sect;6의 페이지 규약(20개 반환)과 같은 값이고,
	 * 상한 100은 서비스에서 깎는다 — 400 대신 상한을 적용하는 이유는 {@code FollowService.MAX_PAGE_SIZE}에 적었다.
	 */
	@GetMapping("/{userId}/followers")
	public CursorPageResponse<UserSummary> followers(@PathVariable Long userId,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") int size) {
		return followService.getFollowers(userId, cursor, size);
	}

	@GetMapping("/{userId}/followings")
	public CursorPageResponse<UserSummary> followings(@PathVariable Long userId,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") int size) {
		return followService.getFollowings(userId, cursor, size);
	}
}
