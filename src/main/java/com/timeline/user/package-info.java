/**
 * 사용자 도메인.
 *
 * <p>0.6에서 User 엔티티 · UserRepository · UserService(가입용 저장·중복 검사)까지 들어왔다.
 * 남은 것: UserController와 조회 기능 — 0.9에서 채운다.
 *
 * <p>경계 규칙 1 — User 엔티티와 UserRepository는 이 패키지 밖으로 노출하지 않는다.
 * 타 도메인은 UserService를 통해서만 접근한다.
 */
package com.timeline.user;
