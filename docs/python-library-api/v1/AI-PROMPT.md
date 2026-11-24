# 通用 Python 库扩展制包提示词

请为当前 Pocket Android IDE 的 Python 库扩展 API v1 制作独立 APK。

输入由用户填写：
- 上游库名、固定版本、源码地址与期望 import 名：<填写>
- 使用场景及实际验证脚本：<填写>
- Android APK 包名与显示名称：<填写>
- 必须支持的设备/系统：<填写>

先阅读 docs/python-library-api/v1/README.md、manifest.schema.json、PythonLibraries.java 和 packs/python-library-template。检查 Git 状态，保留已有改动，检查额度并按 AGENTS.md 留续作记录。不要假设 README 中的历史验收等于新库通过。

1. 固定上游版本、源码摘要和完整许可证；检查依赖、资源文件访问、distribution metadata、动态导入及原生扩展。核对包的真实源码；不得为通过验证而用同名空模块代替目标库。
2. 先选择模式：ZIP-safe 纯 Python 源码使用 `kind=pure-python`、`runtime=cp314-pure-v1`；Android ARM64 原生移植使用实验性的 `kind=android-native`、`runtime=cp314-native-aarch64-v1` 和必填 `preload` 数组。依赖闭包内置，列出所有 imports，dependencies=[]。纯 Python 模式不能装入原生文件；原生模式须满足下方附加规则。必须执行安装脚本或布局尚未适配的库应报告阻断，不伪造兼容、不把 PC wheel 当 Android 包。
3. 以模板构建资源 APK：唯一 applicationId、版本码、发现 action、assets/python-library.json、library.zip 和 licenses。不要加入网络/存储权限或任意启动钩子。第三方使用自己的签名，不读取或复用作者私有密钥。
4. 执行通用制包器、Schema 检查、编译、APK 内摘要复核，并运行用户验证脚本。至少覆盖导入、核心功能、依赖/资源读取、异常、重复任务和调试；确认禁用/卸载后导入失败且用户项目保留，升级须重验，模块冲突须明确失败。
5. 输出源码位置、锁定版本/许可、APK 大小/SHA-256/证书、安装步骤、实际测试结果及未测项。无 ARM64 实机则标记待验收。不要自动推送 GitHub 或发布 Release。

交付应让用户只安装目标库 APK 并在 Python 库扩展入口选择启用，无需重装整个 Python 运行时。不得宣称任意 pip 库均可使用。


原生科学计算库的附加规则：若用户明确要求 Android 原生移植，可使用实验性的 cp314-native-aarch64-v1，先读接口新增章节。必须固定可复现 Android 源码构建或已审计 Android 归档，核验 e_machine、CPython ABI、DT_NEEDED 闭包、绝对前缀、许可证及 preload 顺序。不能把 Linux glibc/Windows wheel 直接打包。原生库变更需显式重启 Python 进程；未通过 ARM64 设备实际计算和重复任务只能交付为候选。Matplotlib 初期使用 Agg 生成 PNG，并测试字体和数据资源，避免只测 import。

`preload` 允许 `.so` 和版本化 `.so.N` 文件名，资源放在实际目录中，按依赖顺序预加载。原生 C 类型及句柄在进程生命周期内保留，不能要求宿主每轮强制卸载。若组合包已含 NumPy，必须说明先禁用独立 NumPy、重启 Python，再验证组合包，并验证同名模块冲突路径。交付时区分导入成功、计算/绘图成功和完整设备回归，不能复用其他包的实测结论。
