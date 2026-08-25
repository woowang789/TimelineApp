/**
 * 사용자 도메인 — Phase 0에서는 경계만 잡는 빈 패키지다.
 *
 * <p>들어올 것: User 엔티티, UserRepository, UserController, UserService. 0.9에서 채운다.
 *
 * <p>경계 규칙 1 — User 엔티티와 UserRepository는 이 패키지 밖으로 노출하지 않는다.
 * 타 도메인은 UserService를 통해서만 접근한다.
 */
package com.timeline.user;
