#!/usr/bin/env bash
# 本地集成测试用 MySQL 实例（隔离数据目录，不碰系统 MySQL 服务）
#
# 为什么需要这个脚本：集成测试必须在真实 MySQL 8 上跑，不能用 H2 替代；
# 但本机 Docker 不可用时需要一个可重复、可关闭、只监听回环地址的实例。
#
# 安全约束（脚本强制，不靠人记得）：
#   1. 只监听 127.0.0.1，绝不监听 0.0.0.0 —— 否则同网段可直连空密码 root。
#   2. root 密码在首次启动时设置，不再是空密码。
#   3. 业务连接使用专用账号 repopilot，只有 repopilot_* 库的权限。
#   4. 测试结束务必 stop，不常驻。
#
# 用法：
#   ./tools/it-mysql.sh start     # 启动（首次会自动初始化数据目录）
#   ./tools/it-mysql.sh status    # 查看状态、监听地址、账号
#   ./tools/it-mysql.sh stop      # 停止
#   ./tools/it-mysql.sh env       # 打印集成测试所需环境变量
#   ./tools/it-mysql.sh reset     # 整库重建（迁移中途被打断后必须执行）
set -euo pipefail

PORT="${REPOPILOT_IT_DB_PORT:-13306}"
DATADIR="${REPOPILOT_IT_DATADIR:-$(cd "$(dirname "$0")/.." && pwd)/tmp/mysql-it/data}"
BASEDIR="${MYSQL_BASEDIR:-C:/Program Files/MySQL/MySQL Server 8.0}"
BIN="$BASEDIR/bin"

ROOT_PASSWORD="${REPOPILOT_IT_ROOT_PASSWORD:-root_it_local_only}"
APP_USER="${REPOPILOT_IT_DB_USER:-repopilot}"
APP_PASSWORD="${REPOPILOT_IT_DB_PASSWORD:-repopilot_it_only}"

MYSQL="$BIN/mysql.exe"
MYSQLADMIN="$BIN/mysqladmin.exe"
MYSQLD="$BIN/mysqld.exe"

log() { printf '[it-mysql] %s\n' "$*"; }
die() { printf '[it-mysql] 错误：%s\n' "$*" >&2; exit 1; }

[ -x "$MYSQLD" ] || die "找不到 mysqld：$MYSQLD（用 MYSQL_BASEDIR 指定 MySQL 安装目录）"

is_up() {
  "$MYSQLADMIN" -h 127.0.0.1 -P "$PORT" -u root -p"$ROOT_PASSWORD" ping >/dev/null 2>&1 \
    || "$MYSQLADMIN" -h 127.0.0.1 -P "$PORT" -u root ping >/dev/null 2>&1
}

# 以 root 执行 SQL：优先带密码，失败则回退空密码（兼容首次初始化）
root_sql() {
  local sql="$1"
  "$MYSQL" -h 127.0.0.1 -P "$PORT" -u root -p"$ROOT_PASSWORD" -N -B -e "$sql" 2>/dev/null && return 0
  "$MYSQL" -h 127.0.0.1 -P "$PORT" -u root -N -B -e "$sql"
}

cmd_start() {
  if is_up; then
    log "已经在运行（127.0.0.1:$PORT），无需重复启动"
    cmd_status
    return 0
  fi

  if [ ! -d "$DATADIR/mysql" ]; then
    log "数据目录为空，执行初始化：$DATADIR"
    mkdir -p "$DATADIR"
    "$MYSQLD" --no-defaults --basedir="$BASEDIR" --datadir="$DATADIR" \
      --initialize-insecure --console >/dev/null 2>&1 \
      || die "初始化失败，检查数据目录权限"
  fi

  log "启动 mysqld（bind-address=127.0.0.1，port=$PORT）"
  "$MYSQLD" --no-defaults --basedir="$BASEDIR" --datadir="$DATADIR" \
    --port="$PORT" --bind-address=127.0.0.1 \
    --mysqlx=OFF --innodb-buffer-pool-size=128M --console >"$DATADIR/../stdout.log" 2>&1 &
  disown || true

  for _ in $(seq 1 40); do
    if "$MYSQLADMIN" -h 127.0.0.1 -P "$PORT" -u root ping >/dev/null 2>&1; then break; fi
    sleep 1
  done
  "$MYSQLADMIN" -h 127.0.0.1 -P "$PORT" -u root ping >/dev/null 2>&1 \
    || die "启动超时，查看 $DATADIR/../stdout.log"

  # 首次启动时 root 是空密码：立即设置，避免长期以空密码暴露在回环地址上
  if "$MYSQL" -h 127.0.0.1 -P "$PORT" -u root -e "SELECT 1" >/dev/null 2>&1; then
    log "检测到空密码 root，设置本地测试密码"
    root_sql "ALTER USER 'root'@'localhost' IDENTIFIED BY '$ROOT_PASSWORD';"
  fi

  log "准备测试库与专用账号"
  root_sql "
    CREATE DATABASE IF NOT EXISTS repopilot_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
    CREATE DATABASE IF NOT EXISTS repopilot_ddl_probe CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
    CREATE USER IF NOT EXISTS '$APP_USER'@'127.0.0.1' IDENTIFIED BY '$APP_PASSWORD';
    CREATE USER IF NOT EXISTS '$APP_USER'@'localhost' IDENTIFIED BY '$APP_PASSWORD';
    GRANT ALL PRIVILEGES ON \`repopilot_it\`.* TO '$APP_USER'@'127.0.0.1';
    GRANT ALL PRIVILEGES ON \`repopilot_it\`.* TO '$APP_USER'@'localhost';
    GRANT ALL PRIVILEGES ON \`repopilot_ddl_probe\`.* TO '$APP_USER'@'127.0.0.1';
    GRANT ALL PRIVILEGES ON \`repopilot_ddl_probe\`.* TO '$APP_USER'@'localhost';
    FLUSH PRIVILEGES;" >/dev/null

  cmd_status
}

cmd_status() {
  if ! is_up; then
    log "未运行（127.0.0.1:$PORT）"
    return 1
  fi
  local ver
  ver=$(root_sql "SELECT VERSION();" | head -1)
  log "运行中：MySQL $ver，端口 $PORT"
  log "数据目录：$DATADIR"

  # 监听地址必须只有回环地址；出现 0.0.0.0 / :: 说明实例暴露在网络上，必须停掉重开
  if command -v ss >/dev/null 2>&1; then
    local addr
    addr=$(ss -ltn 2>/dev/null | awk -v p=":$PORT" '$4 ~ p {print $4}' | paste -sd, -)
    log "监听地址：${addr:-未知}"
    case ",$addr," in
      *",0.0.0.0:$PORT,"*|*",*:$PORT,"*)
        log "警告：端口对全网开放，请 stop 后用 start 重新以 127.0.0.1 启动" ;;
    esac
  fi
}

cmd_stop() {
  if ! is_up; then
    log "未运行，无需停止"
    return 0
  fi
  log "停止实例"
  "$MYSQLADMIN" -h 127.0.0.1 -P "$PORT" -u root -p"$ROOT_PASSWORD" shutdown 2>/dev/null \
    || "$MYSQLADMIN" -h 127.0.0.1 -P "$PORT" -u root shutdown
  for _ in $(seq 1 30); do
    is_up || { log "已停止"; return 0; }
    sleep 1
  done
  die "停止超时"
}

cmd_env() {
  cat <<EOF
export REPOPILOT_IT_DB_HOST=127.0.0.1
export REPOPILOT_IT_DB_PORT=$PORT
export REPOPILOT_IT_DB_NAME=repopilot_it
export REPOPILOT_IT_DB_USER=$APP_USER
export REPOPILOT_IT_DB_PASSWORD=$APP_PASSWORD
EOF
}

# 为什么需要 reset：MySQL 的 DDL 不是事务性的，迁移中途被打断会留下"部分已完成"的
# 表与外键，而 flyway_schema_history 里没有对应记录，下一次迁移会报
# Duplicate foreign key 之类的错。遇到这种情况只能整库重建，不能手工补 DDL。
cmd_reset() {
  is_up || die "实例未运行，先执行 $0 start"
  log "重建测试库（会删除 repopilot_it / repopilot_ddl_probe 中的全部数据）"
  root_sql "
    DROP DATABASE IF EXISTS repopilot_it;
    DROP DATABASE IF EXISTS repopilot_ddl_probe;
    CREATE DATABASE repopilot_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
    CREATE DATABASE repopilot_ddl_probe CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  log "重建完成：两个库均为空"
}

case "${1:-}" in
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  env)    cmd_env ;;
  reset)  cmd_reset ;;
  *)      echo "用法：$0 {start|stop|status|env|reset}" >&2; exit 2 ;;
esac
