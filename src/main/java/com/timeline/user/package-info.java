/**
 * 사용자 도메인.
 *
 * <p>0.6에서 User 엔티티 · UserRepository · UserService(가입용 저장·중복 검사)가,
 * 0.9/0.10에서 UserController(단건 조회) · 팔로워 수 원자 증감 · 목록용 요약 조회가 들어왔다.
 *
 * <p>경계 규칙 1 — User 엔티티와 UserRepository는 이 패키지 밖으로 노출하지 않는다.
 * 타 도메인은 UserService를 통해서만 접근한다. 밖으로 나가는 형태는 UserCredentials(auth)와
 * UserSummary(follow) 두 record뿐이다.
 */
package com.timeline.user;
