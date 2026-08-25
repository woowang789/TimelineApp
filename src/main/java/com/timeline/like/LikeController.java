package com.timeline.like;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 좋아요 API (마스터 &sect;6).
 *
 * <p>둘 다 204다. 갱신된 좋아요 수를 응답에 실어 주는 흐름(마스터 &sect;4.4의 "누른 본인에게는 즉시 반영")은
 * 그 값을 캐시에서 읽어 오는 Phase 2a에서 만든다 — 지금 돌려주려면 SELECT를 한 번 더 던져야 하고,
 * 그건 캐시가 생기면 사라질 코드다.
 */
@RestController
@RequestMapping("/api/v1/posts/{postId}/likes")
public class LikeController {

	private final LikeService likeService;

	public LikeController(LikeService likeService) {
		this.likeService = likeService;
	}

	@PostMapping
	public ResponseEntity<Void> like(@AuthenticationPrincipal Long userId, @PathVariable Long postId) {
		likeService.like(postId, userId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping
	public ResponseEntity<Void> unlike(@AuthenticationPrincipal Long userId, @PathVariable Long postId) {
		likeService.unlike(postId, userId);
		return ResponseEntity.noContent().build();
	}
}
