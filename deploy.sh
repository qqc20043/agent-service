#!/usr/bin/env bash
# ============================================================
# 漫思茶 Agent 平台 · 一键部署脚本
# 用法：bash deploy.sh [init|up|down|logs|status|backup]
# ============================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

COMPOSE="docker compose -f docker-compose.prod.yml"

log()  { echo -e "\033[32m[$(date '+%H:%M:%S')] $*\033[0m"; }
warn() { echo -e "\033[33m[$(date '+%H:%M:%S')] ⚠️  $*\033[0m"; }
err()  { echo -e "\033[31m[$(date '+%H:%M:%S')] ✗ $*\033[0m" >&2; }

# ---------- 前置检查 ----------
precheck() {
    log "环境检查..."

    command -v docker >/dev/null 2>&1 || { err "未安装 docker"; exit 1; }
    docker compose version >/dev/null 2>&1 || { err "未安装 docker compose v2"; exit 1; }

    [[ -f .env ]] || { err "缺少 .env，请先 cp .env.example .env 并填写"; exit 1; }

    # 检查是否还有未替换的占位符
    if grep -q "CHANGE_ME" .env; then
        err ".env 中仍有 CHANGE_ME 占位符未替换"
        grep -n "CHANGE_ME" .env
        exit 1
    fi

    # 证书检查
    if [[ ! -f nginx/certs/fullchain.pem ]]; then
        warn "未找到 nginx/certs/fullchain.pem"
        warn "请先执行：sudo certbot certonly --nginx -d \$DOMAIN"
        warn "然后：sudo cp /etc/letsencrypt/live/\$DOMAIN/fullchain.pem nginx/certs/"
        warn "      sudo cp /etc/letsencrypt/live/\$DOMAIN/privkey.pem  nginx/certs/"
        read -rp "仍要继续吗？(y/N) " ans
        [[ "$ans" == "y" ]] || exit 1
    fi

    log "检查通过"
}

# ---------- 初始化 ----------
init() {
    precheck
    log "创建必要目录..."
    mkdir -p nginx/certs nginx/logs

    log "启动全部服务..."
    $COMPOSE up -d --build

    log "等待服务就绪（60s）..."
    sleep 60
    status
    log "初始化完成。查看日志：bash deploy.sh logs"
}

# ---------- 启停 ----------
up()     { precheck; $COMPOSE up -d --build; sleep 20; status; }
down()   { $COMPOSE down; log "已停止（数据卷保留）"; }
restart(){ $COMPOSE restart; sleep 15; status; }

# ---------- 状态 ----------
status() {
    log "容器状态："
    $COMPOSE ps

    echo
    log "健康检查："
    if curl -sf http://localhost/actuator/health >/dev/null 2>&1; then
        echo "  agent-service : ✅ UP"
    else
        echo "  agent-service : ❌ 未就绪（查看 bash deploy.sh logs agent-service）"
    fi

    echo
    log "内存占用："
    docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}" \
        $(docker ps --filter "name=ms-" --format "{{.Names}}") 2>/dev/null || true
}

# ---------- 日志 ----------
logs() {
    if [[ -n "${1:-}" ]]; then
        $COMPOSE logs -f --tail=200 "$1"
    else
        $COMPOSE logs -f --tail=100
    fi
}

# ---------- 备份 ----------
backup() {
    local dir="backups/$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$dir"
    log "备份 MySQL..."
    docker exec ms-mysql sh -c \
        'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --all-databases' \
        > "$dir/mysql_all.sql" 2>/dev/null
    log "备份 Redis..."
    docker exec ms-redis redis-cli --no-auth-warning -a "$(grep REDIS_PASSWORD .env | cut -d= -f2)" BGSAVE
    log "备份完成：$dir"
    ls -lh "$dir"
}

# ---------- 更新 ----------
update() {
    precheck
    log "拉取最新代码..."
    git pull

    log "重新构建 agent-service..."
    $COMPOSE build agent-service
    $COMPOSE up -d agent-service

    log "等待健康检查..."
    sleep 30
    status
}

# ---------- 入口 ----------
case "${1:-}" in
    init)    init ;;
    up)      up ;;
    down)    down ;;
    restart) restart ;;
    status)  status ;;
    logs)    logs "${2:-}" ;;
    backup)  backup ;;
    update)  update ;;
    *)
        cat <<'EOF'
漫思茶 Agent 平台部署脚本

用法: bash deploy.sh <命令>

  init      首次部署：检查环境 + 创建目录 + 启动全部服务
  up        启动服务
  down      停止服务（数据保留）
  restart   重启服务
  status    查看状态、健康检查、内存占用
  logs [服务名]  查看日志（不指定则看全部）
  backup    备份 MySQL 与 Redis
  update    拉取代码并重建 agent-service

首次使用：
  1. cp .env.example .env && vim .env   # 填所有 CHANGE_ME
  2. chmod 600 .env
  3. sudo certbot certonly --nginx -d your-domain.com
  4. sudo cp /etc/letsencrypt/live/your-domain.com/{fullchain,privkey}.pem nginx/certs/
  5. bash deploy.sh init

注意：老项目 tearSpring 需先单独 Docker 化并推到镜像仓库，
      或在 docker-compose.prod.yml 里改成 build 本地路径。
EOF
        ;;
esac
