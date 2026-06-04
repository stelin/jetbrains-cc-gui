#!/bin/sh
# 启动沙盒 IDE 进行插件调试 (Run sandbox IDE for plugin debugging)
# 等价命令: ./gradlew clean runIde

# 切换到脚本所在目录, 这样从任意位置执行都能工作
cd "$(dirname "$0")" || exit 1

echo "==> 正在启动沙盒 IDE (./gradlew clean runIde) ..."
exec ./gradlew clean runIde "$@"
