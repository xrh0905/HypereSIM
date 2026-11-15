# HypereSIM

Xiaomi HyperOS 4（Android 15–17）可拆卸 eUICC 系统托管：KernelSU/SukiSU/APatch/Magisk 模块实现计划。

## 内容

| 路径 | 说明 |
| --- | --- |
| `docs/lpa/` | 小米原生 LPA `com.miui.euicc` 4.1.0.1 的完整逆向记录（组件、ES10/ES9+、profile 生命周期、MEP 双路线、门禁与特权、端口模型） |
| `docs/module-plan.md` | HypereSIM 模块实现计划（结构、脚本、路线选择、验证矩阵、风险） |
| `work/MIUIEsimLPA.apk` | 分析目标（9,096,417 B，md5 `4bd1e9b59606f84e075afed293a9dcaa`） |
| `work/jadx/sources/` | jadx 1.5.6 反编译源码（5,030 个 java 文件） |
| `work/prior/` | 前序成果：设备实测报告、LSPilot 插件、v5 模块、4.0.2.1 反编译 |
| `work/repo/` | 上游模块工程（XiaomiEsimLPA v0.6.1）；仅作 `libxposed` 工程外形参考，**不作为实现依据** |
| `tools/check_evidence.py` | 文档 `文件:行` 引用校验器（`python tools/check_evidence.py`） |

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
