package com.timeline.post;

import com.timeline.common.api.CursorPageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게시글 API (마스터 &sect;6).
 *
 * <p><strong>{@code PATCH /posts/{postId}}는 만들지 않는다</strong> — 마스터 &sect;3의 의도적 제외 목록에 있다.
 * 수정이 있으면 캐시에 올라간 본문의 무효화 시점을 Phase 2a에서 한 겹 더 다뤄야 하는데,
 * 그건 이 프로젝트가 증명하려는 것(Pull → Push → Hybrid의 성능 차이)과 무관한 복잡도다.
 *
 * <p>{@code GET /users/{userId}/posts}가 여기 있는 것은 경로가 아니라 <strong>리소스</strong>를 따랐기 때문이다 —
 * 반환하는 것은 게시글이고, user 도메인은 이 조회에 관여하지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
public class PostController {

	/** 마스터 &sect;6의 페이지 크기 기본값. */
	private static final String DEFAULT_PAGE_SIZE = "20";

	private final PostService postService;

	public PostController(PostService postService) {
		this.postService = postService;
	}

	@PostMapping("/posts")
	public ResponseEntity<PostResponse> create(@AuthenticationPrincipal Long userId,
			@Valid @RequestBody PostCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(postService.create(userId, request.content()));
	}

	@GetMapping("/posts/{postId}")
	public PostResponse get(@PathVariable Long postId) {
		return postService.get(postId);
	}

	/** soft delete. 본문에 돌려줄 것이 없으므로 204다. */
	@DeleteMapping("/posts/{postId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long postId) {
		postService.delete(postId, userId);
		return ResponseEntity.noContent().build();
	}

	/** 작성자 글 목록. {@code cursor}가 없으면 첫 페이지(최신순)다. */
	@GetMapping("/users/{userId}/posts")
	public CursorPageResponse<PostResponse> findByAuthor(@PathVariable Long userId,
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
		return postService.findByAuthor(userId, cursor, size);
	}
}
