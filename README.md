# HypereSIM

Xiaomi HyperOS 4（Android 15–17）可拆卸 eUICC 系统托管：KernelSU/SukiSU/APatch/Magisk 模块实现。

## 内容

| 路径 | 说明 |
| --- | --- |
| `module/` | KernelSU zip 模块源文件：LPA payload、privapp 权限、`telephony.euicc` 特性、MEP 禁用、诊断脚本 |
| `lsposed/` | LSPosed 行为层（libxposed API 102，静态作用域 `com.android.phone` + `com.miui.euicc`）：切卡拦截、EID、首个 profile 刷新等待；无 Activity/资源 |
| `tools/build_module.py` | 构建完整 zip（校验官方 APK → 暂存 `build/module/` → 输出 `outputs/HypereSIM-*.zip`） |
| `tools/build_lsposed.py` | 构建并校验 LSPosed APK（Gradle wrapper → 校验 `META-INF/xposed`、入口类、清单 → 输出 `outputs/HypereSIM-LSPosed-*.apk`） |
| `docs/lpa/` | 小米原生 LPA `com.miui.euicc` 4.1.0.1 的完整逆向记录（组件、ES10/ES9+、profile 生命周期、MEP 双路线、门禁与特权、端口模型） |
| `docs/module-plan.md` | HypereSIM 模块实现计划（结构、脚本、路线选择、验证矩阵、风险） |
| `module/system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` | 随仓库入库的官方未修改 LPA 4.1.0.1（9,096,417 B，md5 `4bd1e9b59606f84e075afed293a9dcaa`），仿上游 `tehcneko/XiaomiEsimLPA` 布局 |
| `work/MIUIEsimLPA.apk` | 分析目标（与 `module/` 内同一份 APK） |
| `work/jadx/sources/` | jadx 1.5.6 反编译源码（5,030 个 java 文件） |
| `work/prior/` | 前序成果：设备实测报告、LSPilot 插件、v5 模块、4.0.2.1 反编译 |
| `work/repo/` | 上游模块工程（XiaomiEsimLPA v0.6.1）；仅作 `libxposed` 工程外形参考，**不作为实现依据** |
| `tools/check_evidence.py` | 文档 `文件:行` 引用校验器（`python tools/check_evidence.py`） |

## 构建 zip 模块

```bash
python tools/build_module.py
# 输出：outputs/HypereSIM-v0.1.0.zip
# 暂存：build/module/（完整可检查的模块树）
```

构建直接使用模块源树中的 `module/system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk`
（仿照上游 `tehcneko/XiaomiEsimLPA` 的布局，APK 随仓库入库）。脚本会强校验 md5
`4bd1e9b59606f84e075afed293a9dcaa` 与 APK 内的 `com.miui.euicc` 清单，避免误打包其他版本。
模块内 `config` 的 `MODE=on/off` 控制 `skip_mount`：`off` 时不挂载任何 `/product`、`/vendor` 覆盖。

安装顺序：先在 LSPosed 中启用行为层模块并勾选 `com.android.phone`、`com.miui.euicc`，
再刷入本 zip 并重启。KernelSU/SukiSU 必须启用系统挂载元模块（Hybrid Mount 或
meta-overlayfs）；模块不写 `Settings.Secure`，也不安装 LSPosed APK。

## 构建 LSPosed 模块

```bash
python tools/build_lsposed.py --offline
# 输出：outputs/HypereSIM-LSPosed-v0.1.0.apk

python tools/build_lsposed.py --offline --debug
# 输出：outputs/HypereSIM-LSPosed-v0.1.0-debug.apk（debug 签名，可调试）
# 工程：lsposed/（Gradle wrapper 8.14.3 + AGP 8.12.0 + libxposed API 102.0.0）
```

`--offline` 使用本机 Gradle 缓存；首次构建需要网络拉取依赖。release 构建默认使用仓库内
`lsposed/keystore/hyperesim-release.jks`（alias 与密码均为 `hyperesim`），与 CI 产物签名一致；
可用 `HYPERESIM_KEYSTORE`、`HYPERESIM_KEYSTORE_PASSWORD`、`HYPERESIM_KEY_ALIAS`、
`HYPERESIM_KEY_PASSWORD` 覆盖。`--debug` 构建固定使用 Android debug keystore。

## CI 与发布

`.github/workflows/build.yml` 三档触发：

| 触发 | 行为 |
| --- | --- |
| push `main` / PR | 构建 zip + APK，上传 `hyperesim` artifact，不发布 |
| push `v*` tag | 构建后创建 GitHub Release，附 `HypereSIM-<version>.zip`、`HypereSIM-LSPosed-<version>.apk`、`SHA256SUMS.txt` |
| `workflow_dispatch` | 手动构建；勾选 `release` 才发布（tag 名取 `module/module.prop` 的 `version`，已存在则失败） |

版本号必须三处一致，tag 名必须等于 `version`：

1. `module/module.prop`：`version` / `versionCode`
2. `lsposed/app/build.gradle.kts`：`versionName` / `versionCode`
3. `lsposed/app/src/main/resources/META-INF/xposed/module.prop`：`moduleVersion` / `moduleVersionCode`

CI 默认用入库的 `lsposed/keystore/hyperesim-release.jks`（alias 与密码均为 `hyperesim`）签名，
保证每次构建签名一致、APK 可直接覆盖升级。该密钥公开，只用于签名一致性、不构成安全边界；
需要私有密钥时配置仓库 secrets：`HYPERESIM_KEYSTORE_BASE64`（`base64 -w0 release.jks`）、
`HYPERESIM_KEYSTORE_PASSWORD`、`HYPERESIM_KEY_ALIAS`、`HYPERESIM_KEY_PASSWORD`（可选）。

```bash
git tag v0.1.0
git push origin main v0.1.0   # tag 触发构建 + Release
```

## 快速结论

- 两条路线由 `v3.b.d()`（平台 `…euicc.mep` 特性 **或** 内置不可拆 eUICC 的 MEP 能力）决定：
  MEP → 框架 `EuiccCardManager`；非 MEP → LPA 自建通道 + 自拼 CAPDU。详见 `docs/lpa/04-mep-vs-nonmep.md`。
- 模块用文件级 `<unavailable-feature name="android.hardware.telephony.euicc.mep"/>` 统一收口到非 MEP，
  重启生效，不改 vendor 原声明。MEP 只由 zip 模块负责，LSPosed 不重复 hook `hasSystemFeature`。
- 行为层只移植 LSPilot 已验证的 3 组 hook，由独立 LSPosed 模块
  `io.github.xrh0905.hyperesim`（API 102，静态作用域）执行：
  1. `setEsimState → IExtTelephony.setSimType` 切卡（只翻状态位，不碰 modem）；
  2. `getProductEid()` 读内置 NV 导致 `*#06#` 为空（改读槽 1 外置 EID）；
  3. 无已启用 profile 时 `l.g()` 等不到 `EUICC_REFRESHED` 导致启用卡死（走 `k(true)` 分支）。
- KernelSU zip 模块只负责部署：把官方 LPA 覆盖到 `/product/priv-app`、覆盖
  `privapp-permissions-euicc.xml` 与 `telephony.euicc` 特性、挂载 `<unavailable-feature>`
  禁用 MEP。**不写 `is_enable_esim_for_user`，不携带 LSPosed APK**；启用 eSIM 直接在设置里
  打开，由 LSPosed hook 保证不切卡。
