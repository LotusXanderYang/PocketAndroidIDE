# IDE 图像预览候选验收记录

## 当前结果

用户确认：关闭独立 NumPy 库并重启系统后，Matplotlib 成功生成图像。此结果证明该设备上的绘图输出可用。IDE 内预览的实机验证仍待完成。

本轮新增只读预览窗口。点击工作区文件树中的图片，或从“文件 → 打开文件”选择图片，即可打开。窗口保留当前代码文件和未保存内容，不把图片载入文本编辑器。

支持 PNG、JPEG、WebP、BMP 和 GIF 首帧。提供双指缩放、双击缩放、拖动平移，以及放大、缩小、适应、1:1、刷新和关闭按钮。外接键盘可用加减号缩放、数字 0 适应、方向键平移。

图片读取、SAF 查询和解码在后台执行。文件上限为 32 MiB。解码结果最多约 400 万像素，单边最多 4096 像素。大图按二次幂降采样；此时 1:1 指预览像素，窗口会显示说明。重新打开或点击“刷新”会重新读取工作区文件。

## 产物与检查

候选文件：`artifacts/ide-image-preview-ea02da2e.apk`。

- 大小：301,222 字节。
- SHA-256：`ea02da2e0aab57d8c7b54e52aae284e7c9085d0c065a9b56afa476f6319768b3`。
- 应用 ID：`com.example.pocketandroidide`。
- 版本沿用当前开发候选：`0.3.0-alpha.1`，versionCode 为 3。
- 签名 SHA-256：`f1fa1170a83b9ac5d8436b25d9c163a07b68ad9a5567492d568906edeaa888e6`。

`build-local.ps1 -Product ide` 构建通过。新旧 IDE 的签名证书相同，可以覆盖安装。无需重装 Python 或 Matplotlib 库。

`ImagePreviewSmokeTest` 的 6 组尺寸边界检查通过，覆盖正常图像、超大正方形、极长图像和整数上限，另检查图片扩展名分流。此测试不验证 Android 实际解码或触屏效果。

`:ide:lintDebug` 检查通过，报告只有原有的 `DataExtractionRules` 警告。首次离线执行因缺少 Lint 缓存失败，随后从工程现有仓库下载依赖后完成。`git diff --check` 通过。

## 实机操作

1. 直接覆盖安装候选 IDE，不要卸载旧版。
2. 在原工作区点击 `matplotlib_probe.png`。确认显示图像及尺寸。
3. 试双指缩放、拖动，以及“适应”和“1:1”。关闭后确认代码内容和当前文件保留。
4. 重新绘图并保存到同一文件，再打开预览；也可在文件变化后点击“刷新”。确认显示新内容。
5. 可选：打开一张损坏的 PNG，确认提示预览失败，关闭后仍能编辑代码。

本轮 ADB 未发现已连接设备，因此以上实机结果尚未确认。旋转时预览窗口关闭，原编辑器仍按已有机制恢复；本轮没有恢复此前舍弃的旋转过渡修改。

## 修改与续作

新增 `workbench/.../ImagePreview.java`，负责窗口、后台解码和缩放交互。修改 `WorkbenchActivity.java` 的文件打开入口、私有目录列表和生命周期清理。新增 `tools/ImagePreviewSmokeTest.java`。

开始前已检查 Git 状态。此前 UI、Python 库接口和科学计算库的未提交、未跟踪文件全部保留。未提交、推送或发布 Release。新文件属于本轮改动，后续提交时应明确选择文件。

独立 NumPy 与 Matplotlib 自带 NumPy 的重叠选择仍须避免。旧记录中的来源冲突详情增强，以及 Python 引导阶段异常捕获，未纳入此次预览功能修改。继续相关任务前，先读 `SCIENTIFIC-LIBRARIES-CHECKPOINT-2026-11-23.md` 并检查 Git 状态。
