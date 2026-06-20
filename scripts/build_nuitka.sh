#!/usr/bin/env bash
# ============================================================================
# Hekuo's Mod - Nuitka编译脚本
# 将Python前端服务器编译为独立可执行文件
#
# 依赖:
#   - Python 3.14+ (需要安装到系统或虚拟环境)
#   - Nuitka: pip install nuitka
#   - C编译器: gcc (Linux) / MSVC (Windows) / Xcode CLI (macOS)
#
# 用法:
#   ./scripts/build_nuitka.sh          # 编译当前平台
#   ./scripts/build_nuitka.sh --clean  # 清理后重新编译
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PYTHON_SRC="$PROJECT_DIR/python/status_server.py"
BUILD_DIR="$PROJECT_DIR/build/nuitka"
OUTPUT_DIR="$PROJECT_DIR/dist"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# 检查Python版本
check_python() {
    local py_cmd="${1:-python3}"
    if ! command -v "$py_cmd" &> /dev/null; then
        return 1
    fi
    local version
    version=$("$py_cmd" -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
    log_info "找到 $py_cmd: Python $version"
    return 0
}

# 查找Python 3.14+
find_python() {
    local candidates=("python3.14" "python3.13" "python3.12" "python3" "python")
    for cmd in "${candidates[@]}"; do
        if check_python "$cmd"; then
            echo "$cmd"
            return 0
        fi
    done
    log_error "未找到Python 3解释器"
    exit 1
}

# 清理构建目录
clean_build() {
    log_info "清理构建目录..."
    rm -rf "$BUILD_DIR"
    rm -rf "$OUTPUT_DIR/hekuos-mod-web"*
}

# 编译Nuitka二进制
build_nuitka() {
    local py_cmd="$1"
    local os_name
    os_name="$(uname -s 2>/dev/null || echo "Unknown")"

    log_info "使用 $py_cmd 编译Nuitka二进制..."

    # 确保Nuitka已安装
    "$py_cmd" -m pip install nuitka --quiet 2>/dev/null || true

    # 确定输出文件名
    local output_name="hekuos-mod-web"
    if [[ "$os_name" == MINGW* || "$os_name" == MSYS* || "$os_name" == CYGWIN* || "$os_name" == Windows* ]]; then
        output_name="hekuos-mod-web.exe"
    fi

    mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"

    # Nuitka编译
    "$py_cmd" -m nuitka \
        --standalone \
        --onefile \
        --output-filename="$output_name" \
        --output-dir="$BUILD_DIR" \
        --python-flag=no_site \
        --assume-yes-for-downloads \
        --remove-output \
        --follow-imports \
        --enable-plugin=no-implicit-optional \
        "$PYTHON_SRC"

    # 复制到输出目录
    if [[ -f "$BUILD_DIR/$output_name" ]]; then
        cp "$BUILD_DIR/$output_name" "$OUTPUT_DIR/"
        chmod +x "$OUTPUT_DIR/$output_name" 2>/dev/null || true
        log_info "编译成功: $OUTPUT_DIR/$output_name"
        log_info "文件大小: $(du -h "$OUTPUT_DIR/$output_name" | cut -f1)"
    else
        log_error "编译失败: 未找到输出文件 $BUILD_DIR/$output_name"
        exit 1
    fi
}

# 主流程
main() {
    if [[ "${1:-}" == "--clean" ]]; then
        clean_build
    fi

    log_info "=== Hekuo's Mod Nuitka编译 ==="

    local py_cmd
    py_cmd="$(find_python)"

    build_nuitka "$py_cmd"

    log_info "=== 编译完成 ==="
    log_info "将 $OUTPUT_DIR/hekuos-mod-web 放入服务器的 hekuos-mod-web/ 目录即可使用"
}

main "$@"
