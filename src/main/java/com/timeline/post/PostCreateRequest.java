package com.timeline.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게시글 작성 요청.
 *
 * <p>길이 상한은 스키마와 맞춘다 — {@code posts.content}는 {@code VARCHAR(500)}이다.
 * 하한은 {@code @NotBlank}가 맡는다(공백만 있는 본문도 막는다).
 */
public record PostCreateRequest(

		@NotBlank(message = "content는 필수입니다.")
		@Size(max = 500, message = "content는 500자 이하여야 합니다.")
		String content) {
}
