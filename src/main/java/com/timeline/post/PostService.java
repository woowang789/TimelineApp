package com.timeline.post;

import com.timeline.common.api.CursorPageResponse;
import com.timeline.common.error.BusinessException;
import com.timeline.common.error.ErrorCode;
import com.timeline.common.snowflake.SnowflakeIdGenerator;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시글 도메인 서비스. 타 도메인이 post에 접근하는 유일한 통로다(경계 규칙 1).
 */
@Service
public class PostService {

	/** 커서가 없을 때(첫 페이지) 쓰는 시작점. Snowflake id는 항상 양수라 이 값보다 작다. */
	private static final long FIRST_PAGE_CURSOR = Long.MAX_VALUE;

	private final PostRepository postRepository;
	private final SnowflakeIdGenerator snowflakeIdGenerator;

	public PostService(PostRepository postRepository, SnowflakeIdGenerator snowflakeIdGenerator) {
		this.postRepository = postRepository;
		this.snowflakeIdGenerator = snowflakeIdGenerator;
	}

	/**
	 * 게시글 작성 — id는 DB가 아니라 <strong>애플리케이션이 Snowflake로 만들어 대입한다</strong>(마스터 &sect;4.2).
	 *
	 * <p>작성자 존재 여부는 확인하지 않는다. {@code authorId}는 검증된 Access Token의 principal이므로
	 * 그 사용자는 이미 존재하고, 굳이 확인하면 쓰기 경로에 SELECT가 하나 더 붙는다.
	 * 만에 하나 어긋나면 {@code fk_posts_author}가 막는다.
	 */
	@Transactional
	public PostResponse create(Long authorId, String content) {
		Post post = Post.create(snowflakeIdGenerator.nextId(), authorId, content);
		// id가 이미 채워져 있어도 SELECT 없이 INSERT만 나간다 — 근거는 Post.isNew() 주석.
		return PostResponse.from(postRepository.save(post));
	}

	/** 단건 조회. 없는 글과 삭제된 글은 <strong>같은 404</strong>다 — 삭제 여부를 알려 줄 이유가 없다. */
	@Transactional(readOnly = true)
	public PostResponse get(Long postId) {
		return PostResponse.from(findActive(postId));
	}

	/**
	 * 작성자 글 목록 (커서 페이지네이션).
	 *
	 * <p>다음 페이지 유무를 알기 위해 <strong>{@code size + 1}건을 읽고 초과분은 버린다.</strong>
	 * COUNT 쿼리를 한 번 더 던지는 대신 행 하나를 더 읽는 쪽이 싸고, 무엇보다 COUNT와 목록 조회
	 * 사이에 글이 늘거나 삭제되면 {@code hasNext}가 실제 목록과 어긋난다.
	 *
	 * <p>존재하지 않는 {@code authorId}는 빈 페이지가 된다. "없는 사용자"를 404로 구분하려면
	 * user 도메인 조회가 한 번 더 필요한데, 목록 API에서 그 구분이 주는 가치가 없다.
	 */
	@Transactional(readOnly = true)
	public CursorPageResponse<PostResponse> findByAuthor(Long authorId, Long cursor, int size) {
		List<Post> posts = postRepository.findActiveByAuthorBefore(
				authorId,
				cursor != null ? cursor : FIRST_PAGE_CURSOR,
				Limit.of(size + 1));

		boolean hasNext = posts.size() > size;
		List<PostResponse> data = (hasNext ? posts.subList(0, size) : posts).stream()
				.map(PostResponse::from)
				.toList();
		// 다음 페이지가 없으면 커서도 없다 — 클라이언트가 hasNext를 안 보고 nextCursor만 따라가도 멈춘다.
		Long nextCursor = hasNext ? data.get(data.size() - 1).id() : null;

		return new CursorPageResponse<>(data, nextCursor, hasNext);
	}

	/**
	 * soft delete — <strong>작성자 본인만</strong> 할 수 있다.
	 *
	 * <p>없는 글·이미 삭제된 글은 404, 남의 글은 403이다. 이 둘을 합쳐 404로 뭉개면
	 * 본인 글이 아닌지 아예 없는 글인지 알 수 없어 클라이언트가 재시도 여부를 판단하지 못한다.
	 *
	 * <p>플래그만 바꾸므로 별도 UPDATE 호출이 없다 — 트랜잭션 커밋 시 변경 감지가 처리한다.
	 */
	@Transactional
	public void delete(Long postId, Long requesterId) {
		Post post = findActive(postId);
		if (!post.getAuthorId().equals(requesterId)) {
			throw new BusinessException(ErrorCode.NOT_POST_AUTHOR);
		}
		post.delete();
	}

	/**
	 * 좋아요 수 +1. <strong>like 도메인이 post에 들어오는 통로다</strong>(경계 규칙 1 — PostRepository는
	 * 이 패키지 밖에서 보이지 않는다).
	 *
	 * <p>트랜잭션을 새로 열지 않고 호출자({@code LikeService})의 것에 참여한다. likes 행 삽입과
	 * 이 카운터 증가가 나뉘어 커밋되면 좋아요는 됐는데 숫자는 그대로인 상태가 남는다(마스터 &sect;4.4).
	 *
	 * @throws BusinessException 없거나 삭제된 게시글일 때 ({@code POST_NOT_FOUND})
	 */
	@Transactional
	public void increaseLikeCount(Long postId) {
		if (postRepository.increaseLikeCount(postId) == 0) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
	}

	/** 좋아요 수 −1. 트랜잭션·예외 규약은 {@link #increaseLikeCount}와 같다. */
	@Transactional
	public void decreaseLikeCount(Long postId) {
		if (postRepository.decreaseLikeCount(postId) == 0) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
	}

	private Post findActive(Long postId) {
		return postRepository.findActiveById(postId)
				.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}
}
