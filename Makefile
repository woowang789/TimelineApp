# 타임라인 서비스 — 재현 명령 모음
#
# 마스터 §9.6: "면접관은 '정말 그 규모로 쟀나'를 검증할 수 없다. 검증 가능성이 곧 신뢰도다."
# 더미 생성 · 스냅샷 · 측정을 한 명령으로 만드는 게 이 파일의 목적이다.
#
# macOS 기본 make 는 3.81 이라 .ONESHELL 을 못 쓴다. 여러 줄이 필요한 레시피는
# 백슬래시로 이어 붙여 한 셸에서 돌린다.

SHELL := /bin/bash

# docker compose 는 프로젝트 이름(= 디렉토리 이름 소문자)으로 볼륨 이름을 짓는다.
COMPOSE_PROJECT ?= $(shell basename "$(CURDIR)" | tr '[:upper:]' '[:lower:]')
MYSQL_VOLUME    ?= $(COMPOSE_PROJECT)_mysql-data

# 스냅샷 아카이브 보관 위치. .gitignore 대상이다 — 수 GB 라 커밋할 수 없다.
SNAP_DIR := docker/snapshots

# make seed SCALE=smoke 로 축소 스케일 적재가 된다. 기본은 풀 스케일.
SCALE ?= full

.PHONY: help seed verify-seed db-reset db-snapshot db-restore wait-mysql require-mysql bench-m0 bench-m1

help:
	@echo "타임라인 서비스 — 사용 가능한 명령"
	@echo ""
	@echo "  make seed                    더미 데이터 적재 (users→follows→posts→counts→cohorts)"
	@echo "  make seed SCALE=smoke        축소 스케일 적재 (사용자 1,000 / 관계 3만 / 게시글 3만)"
	@echo "  make verify-seed             분포 검증 쿼리 (SCALE 반영)"
	@echo "  make db-reset                4개 테이블 TRUNCATE — 재적재 전 초기화"
	@echo ""
	@echo "  make db-snapshot SNAP=m0     MySQL 데이터 볼륨을 $(SNAP_DIR)/m0.tar.gz 로 보관"
	@echo "  make db-restore  SNAP=m0     위 아카이브로 되돌린다 (측정 전 필수 — 마스터 §9.3)"
	@echo ""
	@echo "  make bench-m0 / bench-m1     측정 파이프라인 실행"

# -------------------------------------------------------------------------------------
# 더미 데이터
# -------------------------------------------------------------------------------------

# 단계 순서(users→follows→posts→counts→cohorts)는 SeedMain 이 고정한다 —
# follows 가 users FK 를, posts 가 author FK 를 참조하므로 순서를 바꾸면 적재가 실패한다.
seed: require-mysql
	./gradlew seed --args="all --scale=$(SCALE)"

verify-seed: require-mysql
	scripts/verify-seed.sh $(SCALE)

# 생성기는 이미 적재된 테이블에 덧씌우지 않고 멈춘다(중복 적재는 분포 검증을 조용히 깨뜨린다).
# 다시 적재하려면 여기서 비운다. FK 때문에 자식 테이블부터, TRUNCATE 는 FK 참조가 있으면 거부되므로
# 체크를 잠깐 끈다 — 어차피 전부 비우는 중이라 참조 무결성이 깨질 대상이 없다.
db-reset: require-mysql
	docker compose exec -T mysql mysql -uroot -proot timeline -e \
		"SET FOREIGN_KEY_CHECKS=0; TRUNCATE likes; TRUNCATE posts; TRUNCATE follows; TRUNCATE users; SET FOREIGN_KEY_CHECKS=1;"
	@echo "users/follows/posts/likes 초기화 완료."

# -------------------------------------------------------------------------------------
# 스냅샷 — 측정 전 데이터 동일성 보장 (마스터 §9.3)
#
# mysqldump 가 아니라 볼륨 tar 다. 이유:
#   · 복원 후 인덱스 재생성이 없다 — dump/restore 는 300만 행의 보조 인덱스를 매번 다시 만든다
#   · 버퍼 풀·테이블스페이스 상태까지 같은 지점에서 출발한다
#   · 복원이 수 분이 아니라 수십 초다. 3회 반복 × 코호트 3종이면 이 차이가 하루를 가른다
#
# 반드시 컨테이너를 멈추고 뜬다. 돌아가는 InnoDB 의 데이터 파일을 그대로 tar 로 뜨면
# 체크포인트 중간 상태가 잡혀 복원 시 크래시 복구가 돌고, 그러면 "같은 지점"이 아니게 된다.
# -------------------------------------------------------------------------------------

db-snapshot:
	@test -n "$(SNAP)" || { echo "SNAP=<이름> 이 필요하다 (예: make db-snapshot SNAP=m0)"; exit 1; }
	@docker volume inspect $(MYSQL_VOLUME) > /dev/null || \
		{ echo "볼륨 $(MYSQL_VOLUME) 을 찾을 수 없다. MYSQL_VOLUME=<이름> 으로 지정하라."; exit 1; }
	mkdir -p $(SNAP_DIR)
	docker compose stop mysql
	docker run --rm -v $(MYSQL_VOLUME):/data:ro -v "$(CURDIR)/$(SNAP_DIR)":/backup alpine \
		tar czf /backup/$(SNAP).tar.gz -C /data --exclude=./slow.log .
	docker compose start mysql
	@$(MAKE) wait-mysql
	@echo "스냅샷 저장 완료: $(SNAP_DIR)/$(SNAP).tar.gz ($$(du -h $(SNAP_DIR)/$(SNAP).tar.gz | cut -f1))"

db-restore:
	@test -n "$(SNAP)" || { echo "SNAP=<이름> 이 필요하다 (예: make db-restore SNAP=m0)"; exit 1; }
	@test -f $(SNAP_DIR)/$(SNAP).tar.gz || \
		{ echo "$(SNAP_DIR)/$(SNAP).tar.gz 가 없다. 먼저 make db-snapshot SNAP=$(SNAP)"; exit 1; }
	docker compose stop mysql
	docker run --rm -v $(MYSQL_VOLUME):/data -v "$(CURDIR)/$(SNAP_DIR)":/backup:ro alpine \
		sh -c 'rm -rf /data/* /data/.[!.]* /data/..?* 2>/dev/null; tar xzf /backup/$(SNAP).tar.gz -C /data'
	docker compose start mysql
	@$(MAKE) wait-mysql
	@echo "복원 완료: $(SNAP)"

# -------------------------------------------------------------------------------------
# 측정 — 실행 본체는 scripts/bench.sh 가 갖는다
# -------------------------------------------------------------------------------------

bench-m0:
	@test -x scripts/bench.sh || { echo "scripts/bench.sh 가 없거나 실행 권한이 없다."; exit 1; }
	scripts/bench.sh m0

bench-m1:
	@test -x scripts/bench.sh || { echo "scripts/bench.sh 가 없거나 실행 권한이 없다."; exit 1; }
	scripts/bench.sh m1

# -------------------------------------------------------------------------------------
# 보조
# -------------------------------------------------------------------------------------

require-mysql:
	@docker compose ps -q mysql | grep -q . || \
		{ echo "dev 스택이 떠 있지 않다. 먼저 docker compose up -d"; exit 1; }
	@$(MAKE) wait-mysql

wait-mysql:
	@id=$$(docker compose ps -q mysql); \
	test -n "$$id" || { echo "mysql 컨테이너가 없다."; exit 1; }; \
	printf "mysql healthy 대기"; \
	for i in $$(seq 1 90); do \
		if [ "$$(docker inspect -f '{{.State.Health.Status}}' $$id)" = "healthy" ]; then \
			echo " → healthy"; exit 0; \
		fi; \
		printf "."; sleep 2; \
	done; \
	echo " → 타임아웃(180s)"; exit 1
