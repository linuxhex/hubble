#!/bin/bash
# Hubble 启动脚本
# 用法：./start.sh [start|stop|restart|logs]

set -e

APP_NAME="hubble"
JAR_FILE="hubble-server/target/hubble-1.0.0.jar"
PID_FILE="/tmp/$APP_NAME.pid"
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/$APP_NAME.log"

# 加载环境变量
if [ -f .env ]; then
    echo "加载环境变量从 .env..."
    set -a
    source .env
    set +a
else
    echo "警告：.env 文件不存在，复制 .env.example 并填入配置"
    echo "  cp .env.example .env"
    exit 1
fi

start() {
    if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
        echo "$APP_NAME 已在运行 (PID: $(cat $PID_FILE))"
        exit 1
    fi
    
    echo "启动 $APP_NAME..."
    mkdir -p "$LOG_DIR"
    
    nohup java -jar "$JAR_FILE" \
        --server.port=18081 \
        > "$LOG_FILE" 2>&1 &
    
    echo $! > "$PID_FILE"
    echo "$APP_NAME 已启动 (PID: $!)"
    echo "日志：tail -f $LOG_FILE"
}

stop() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            echo "停止 $APP_NAME (PID: $PID)..."
            kill "$PID"
            sleep 5
            if kill -0 "$PID" 2>/dev/null; then
                kill -9 "$PID"
            fi
            echo "$APP_NAME 已停止"
        else
            echo "$APP_NAME 未运行"
        fi
        rm -f "$PID_FILE"
    else
        echo "$APP_NAME 未运行（无 PID 文件）"
    fi
}

restart() {
    stop
    sleep 2
    start
}

logs() {
    tail -f "$LOG_FILE"
}

status() {
    if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
        echo "$APP_NAME 运行中 (PID: $(cat $PID_FILE))"
    else
        echo "$APP_NAME 未运行"
    fi
}

case "${1:-start}" in
    start)   start ;;
    stop)    stop ;;
    restart) restart ;;
    logs)    logs ;;
    status)  status ;;
    *)
        echo "用法：$0 {start|stop|restart|logs|status}"
        exit 1
        ;;
esac
