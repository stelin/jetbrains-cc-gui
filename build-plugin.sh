#!/bin/sh
# 打包插件 (Build plugin package)
# 等价命令: ./gradlew clean buildPlugin
# 产物输出目录: build/distributions/  (zip 包大小约 40MB)

# 切换到脚本所在目录, 这样从任意位置执行都能工作
cd "$(dirname "$0")" || exit 1

echo "==> 正在打包插件 (./gradlew clean buildPlugin) ..."
./gradlew clean buildPlugin "$@" || exit 1

echo ""
echo "==> 打包完成, 产物位于 build/distributions/ :"
ls -lh build/distributions/*.zip 2>/dev/null
