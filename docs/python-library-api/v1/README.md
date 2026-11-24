# Python Library APK API v1

可独立签名、安装和选择启用的 Python 库资源 APK。由 Python 语言包发现和加载；Language Pack API v1 的 Binder 接口保持不变。库管理入口为 IDE「语言包 → Python 库扩展」，也可直接打开 Python 语言包。新版 Python 包必需；旧 Python 包不会加载这些库。

## 当前实现

- `cp314-pure-v1`：CPython 3.14 的纯 Python、ZIP-safe 源码库。使用系统包管理器安装 APK，管理页查看来源、签名、权限、APK SHA-256，再执行导入自检并启用；多个不同顶层模块的库可同时启用。
- `assets/python-library.json` 满足本目录 Schema；`assets/library.zip` 为模块根目录 ZIP，内容摘要对应 `sha256`；许可证放 `assets/licenses/`。依赖必须内置并逐项列入 imports，dependencies 必须为空。当前不执行 setup.py，不解析 PyPI，不运行 .pth。
- APK 有一个 exported Activity，intent action 为 `org.pocketide.python.library.v1.LIBRARY`。发现时只读取资源，不加载 APK 的 Android 类。可用框架 Activity 作为无启动器标记，见示例。
- 安装不等于启用。批准绑定 APK 摘要及安装/更新时间；包更新、重新安装或卸载后原批准不适用。运行前重新发现，只有仍匹配的批准参与加载。禁用在下一任务生效，当前任务保留已取得的库快照。
- 最多同时启用 16 个库包。纯 Python ZIP 压缩体积上限 16 MiB，解压后 32 MiB，单文件 4 MiB，4000 条目；纯 Python 模式拒绝原生模块。所有模式都拒绝路径逃逸、重复路径、pyc 和 pth。imports 为顶层模块名；保留标准库、桥接模块和调试适配器名称。同名模块冲突的导入自检失败。
- 库添加在标准库搜索路径之后；运行器照常将项目目录放在优先位置，项目可遮蔽同名库。每次运行/调试完成后移除库搜索路径及纯 Python ZIP 库模块；原生模块按下文保留到进程退出。库更改/删除操作不会回写工作区。
- 自检在解释器专用线程执行，以实际导入和模块来源为准；这不等同全部功能测试。验证等待 60 秒超时会取消批准流程，但不能保证中止任意 Python 库代码；恶意或死循环库需停止 Python 包进程。库代码在 Python 语言包权限下运行，导入也可能执行代码，必须由用户先确认。

纯 Python 模式使用 ZIP 导入；原生模式先解压到经过摘要核验的私有目录，再加载 Android 扩展。NumPy 等原生库须使用下文的实验模式，并审核 ABI、依赖、加载路径、许可证和设备结果；不能把桌面 wheel 改名后直接使用。

## 制包

复制 `packs/python-library-template` 为新模块，修改 applicationId、namespace、版本和 library.json，将可 ZIP 导入的纯 Python 模块放 payload。包名必须匹配 packId，第三方自行持有并保持签名连续性，不需要项目作者密钥。

```powershell
python tools/package-python-library.py packs/python-library-template
# 本地先按 build-local.ps1 设置 JDK17、ANDROID_HOME 和 Gradle 缓存
.\.toolchain\gradle-8.13\bin\gradle.bat --offline --no-daemon :packs:python-library-template:assembleDebug
python tools/test_python_libraries.py
```

制包器固定 ZIP 时间戳、生成摘要及清单；构建前校验摘要和清单一致性。示例 `pocket_demo` 为接口验证模块，不是已发布的第三方库。无需为每个库修改 Python 运行时或 IDE。上游库有文件系统资源访问、entry points、distribution metadata 或可选依赖时，必须专门测试；ZIP 可导入不代表完整兼容。

## 实机验收

覆盖安装新版 IDE/Python 包（不卸载），再安装示例库 APK。重新验证 Python 语言包后，进入 Python 库扩展启用 Pocket Demo Library。运行 `from pocket_demo import twice; print(twice(21))` 应输出 42；在 print 处设断点，单步/继续与重复调试应正常。禁用库后同一程序应报 ModuleNotFoundError；再次启用可恢复。仅卸载库后项目仍在且导入失败。更换库 APK 版本后须重新验证；冲突模块、错摘要、不兼容运行时、原生模块及导入失败均不得成为已启用库。保留原有标准库和 SAF 写回回归。

当前设备反馈：示例纯 Python 库已导入成功；独立 NumPy 已通过计算、连续运行和调试验证。Matplotlib 自带 NumPy，用户关闭独立 NumPy 并重启系统后成功生成图像。Matplotlib 的重复调试、卸载回归，以及 API 26 和 16 KB 内存页设备尚未完成验证。导入自检通过不代表这些场景通过。


## 原生库实验扩展（2026-11-23）

新增 `kind=android-native`、`runtime=cp314-native-aarch64-v1` 与必填 `preload` 数组，供 ARM64 CPython 3.14 Android 库候选验证。`preload` 为 `native-libs/` 下需要按序预加载的 `.so` 或 `.so.N` 文件名；其 ELF 闭包须在制包时审核，宿主还检查 ELF64 little-endian AArch64。库 ZIP 可包含模块、对应 .dist-info 和 native-libs；资源需保留真实目录以支持 NumPy/Matplotlib。单包压缩 64 MiB、解压 256 MiB、单文件 64 MiB、12000 条目；纯 Python 原有约束不变。

原生载荷按已安装 APK 摘要放入 Python 包 files/native-libraries 的独立目录，加载前核验 ZIP 摘要。不能因此宣称原生库已在实机工作。Native 模块及 CDLL 句柄保留到进程退出，防止 NumPy C 类型重新导入损坏；同进程内原生库选择发生变化时拒绝进一步任务，需由用户在库管理页显式「重启 Python 进程」。重启将终止运行/调试任务，界面先提示。纯 Python 库仍按任务清理。

NumPy 候选从固定、已验收运行时拆包，包含 OpenBLAS 和 libc++ 闭包，原始归档摘要及生成脚本随源码保存。它不代表重编了新的 NumPy 版本。Matplotlib 从同一固定 Termux 源码提交交叉构建，初期目标为 Agg 后端生成图片文件；暂不承诺交互绘图窗口。制包 APK 仍需要自检、核心计算、重复运行、调试及禁用/重启的实机验收。

原生库不能同时提供同名顶层模块。使用 Matplotlib + NumPy 组合包前，先禁用独立 NumPy，再重启 Python 进程，最后只验证组合包。若仍报 `Module conflict: numpy`，确认独立包处于禁用状态并重启设备后重试；不要跳过来源检查。IDE 可从文件树打开生成的 PNG 预览，`plt.show()` 交互窗口不在当前范围内。
