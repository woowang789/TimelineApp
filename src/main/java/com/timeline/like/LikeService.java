package com.timeline.like;

import com.timeline.common.error.BusinessException;
import com.timeline.common.error.ErrorCode;
import com.timeline.post.PostService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 도메인 서비스.
 *
 * <p>post 도메인은 {@link PostService}로만 참조한다(경계 규칙 1) — 게시글 존재 확인도, 카운터 증감도
 * 그쪽 서비스를 거친다. {@code PostRepository}나 {@code Post} 엔티티는 이 패키지에서 보이지 않는다.
 *
 * <p><strong>읽기 경로는 여기 없다.</strong> 좋아요 수를 {@code post:{postId}} 캐시로 읽고
 * 누른 본인에게만 {@code DEL} 후 갱신값을 즉시 돌려주는 흐름(마스터 &sect;4.4)은 Redis 캐시가 생기는
 * Phase 2a의 몫이다. Phase 0은 DB 쓰기 절반만 만든다(로드맵 4.7절).
 */
@Service
public class LikeService {

	private final LikeRepository likeRepository;
	private final PostService postService;

	public LikeService(LikeRepository likeRepository, PostService postService) {
		this.likeRepository = likeRepository;
		this.postService = postService;
	}

	/**
	 * 좋아요 — <strong>이 메서드가 트랜잭션 경계다.</strong> likes 행 삽입과 {@code like_count} 증가는
	 * 함께 커밋되거나 함께 사라져야 한다(마스터 &sect;4.4).
	 *
	 * <p>중복 좋아요를 미리 조회해서 거르지 않는다. 조회와 삽입 사이로 같은 사용자의 두 번째 요청이
	 * 들어오면 그 검사는 그냥 뚫리기 때문이다. {@code uk_likes_post_user} 위반을 잡아 409로 바꾸는 편이
	 * 동시 요청에서도 정확하다(0.6의 중복 username 처리와 같은 패턴).
	 *
	 * <p>순서가 "카운터 먼저, 행 나중"인 것은 <strong>게시글 생사 확인이 카운터 UPDATE에 붙어 있어서다</strong> —
	 * 없는 글에 좋아요하면 FK 위반(500이 되기 쉬운 예외) 대신 여기서 404가 난다.
	 * 중복이라 롤백되면 증가분도 함께 사라진다.
	 */
	@Transactional
	public void like(Long postId, Long userId) {
		postService.increaseLikeCount(postId);
		try {
			likeRepository.save(Like.create(postId, userId));
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.DUPLICATE_LIKE, e);
		}
	}

	/** 좋아요 취소. 트랜잭션 규약은 {@link #like}와 같다 — 행 삭제와 카운터 감소가 한 트랜잭션이다. */
	@Transactional
	public void unlike(Long postId, Long userId) {
		postService.decreaseLikeCount(postId);
		if (likeRepository.deleteByPostIdAndUserId(postId, userId) == 0) {
			// 누른 적 없는 좋아요를 취소한 것이다 — 위의 감소는 롤백된다.
			throw new BusinessException(ErrorCode.LIKE_NOT_FOUND);
		}
	}
}
