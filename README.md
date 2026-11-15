# HypereSIM

Xiaomi HyperOS 4（Android 15–17）可拆 eUICC（实体 eSIM 卡）系统托管模块。

面向 **Xiaomi 14 等“海外版存在 eSIM 型号”的小米机型**：这些机型的系统默认具备 eSIM 能力、
并启用 MEP，插上可拆 eUICC 后，系统 eSIM 开关会真的调用 `setSimType` 把 SIM2 切走；
仅部署 LPA APK 的传统模块因此不工作。HypereSIM 按小米国际版 ROM 的双 LPA 布置部署官方
LPA（小米 + Google），再用最小范围的 LSPosed hook 截断切卡、补齐 EID、修掉首个 profile 的刷新等待，并给小米扫码器补上缺失的激活通道。

## 背景

传统 LPA 模块（把 `MIUIEsimLPA.apk` 放进 `/product/priv-app` 并授予特权）解决的是
“系统没有 eSIM 管理能力”。Xiaomi 14 这类机型不同：

- 同型号海外版带 eSIM，HyperOS 4 固件因此保留 eSIM 能力与 MEP 适配；
- 开启 eSIM 会走 `setEsimState → IExtTelephony.setSimType`，把 SIM2 从物理卡切成 eSIM，
  可拆 eUICC 紧接着掉卡，开关又退回关闭；
- LPA 的 `getProductEid()` 读内置 eUICC 的 NV，外置卡机型上为空，`*#06#` 无 EID；
- 无已启用 profile 时，LPA 等 `EUICC_REFRESHED` 广播，但可拆卡不会产生这个状态变化，
  首次启用会卡在“正在启用 eSIM 卡…”。

这些都属于行为层差异，不是 APK 或权限问题。HypereSIM 只针对这三点做修复。

## 组成

| 部分 | 负责 | 不负责 |
| --- | --- | --- |
| `module/`（KernelSU zip） | 部署官方 MIUI LPA 4.1.0.1 与 Google LPA D.2.1 到 `/product/priv-app`；两套 privapp 白名单与 Google LPA 的 default-permissions；声明 `telephony.euicc` 特性；用 `<unavailable-feature>` 关闭 `telephony.euicc.mep`；启动/操作按钮诊断 | 不写 `Settings.Secure`，不携带 LSPosed APK，不改 vendor 原声明 |
| `lsposed/`（行为层） | 4 组最小 hook：切卡拦截、外置 EID、首个 profile 跳过刷新等待、扫码入口补 `ACTIVATE_CHANNEL` | 无 Activity/资源；不 hook `hasSystemFeature`；不碰 MEP |

MEP 只由 zip 模块收口到非 MEP。LSPosed 仅在静态作用域 `com.android.phone`、
`com.miui.euicc` 中运行（libxposed API 102）。

## Hook 范围

| # | 行为 | 说明 |
| --- | --- | --- |
| 1 | eSIM 状态设置入口 | 拦截 `setEsimState` / `setEsimStateEx`，只翻转系统状态位并返回成功；不进入原方法、不调用 `setSimType`、不切 modem |
| 2 | EID 查询入口 | `getProductEid()` 改读 SIM2 槽位；取不到 32 位 EID 时回退原逻辑 |
| 3 | 首个 profile 启用 | 尚无已启用 profile 时走 LPA 自带的跳过分支，不再等待 `EUICC_REFRESHED` |
| 4 | 扫码激活 | 小米扫码器只发 `ACTIVATE_TYPE/CODE/OPERATE_TYPE`，`ActiveStatusActivity` 因 channel 为空而回落 SM-DS 发现；hook 补 `ACTIVATE_CHANNEL=lpa` 后按激活码直连 SM-DP+ |

设计约束：

- 只 hook 上述方法与签名；类或方法缺失时直接跳过，保持原厂行为，不阻断电话功能。
- 不做 MEP 判定改写、OMAPI 桥、卡信息伪造、UI/资源改写、双驻留等扩展。
- 不做 `pSimToESim`：单端口可拆 eUICC 上该路径物理上不可行。
- Google LPA 只承接 UI 与激活链接，不承载 ES10，所以 hook 仍只落在 `com.android.phone`
  与 `com.miui.euicc`。

## 双 LPA 布置

模块复刻小米国际版 ROM（`OS3.0.337.0.XPAMIXM` 的 `mi_ext` 分区）出厂时的两套 LPA：

| | MIUI LPA | Google LPA |
| --- | --- | --- |
| 包名 / 版本 | `com.miui.euicc` 4.1.0.1 | `com.google.android.euicc` D.2.1.855215241-gms |
| 路径 | `/product/priv-app/MIUIEsimLPA` | `/product/priv-app/EuiccGoogle` |
| `EuiccService` | `com.miui.euicc.services.XmLPAService` | `com.android.euicc.service.EuiccServiceImpl` |
| priority | 101 | 100 |
| 权限 | `privapp-permissions-euicc.xml` | `privapp-permissions-euicc-google.xml`（GMS 白名单与 `EuiccGoogle-permissions.xml` 的并集）+ `EuiccGoogle-default-permissions-google.xml`（`POST_NOTIFICATIONS`） |

框架（`EuiccConnector.findBestComponent`）在**全部** `android.service.euicc.EuiccService`
候选中只取 priority 最大者，且要求候选包持有 `WRITE_EMBEDDED_SUBSCRIPTIONS`、服务声明
`BIND_EUICC_SERVICE`、filter `priority` 非 0。没有运行时 fallback：绑定失败不会自动换下一个。
因此：

- 承载 ES10（枚举/下载/启停 profile）的始终是小米 LPA（101 > 100）；
- Google LPA 承接小米 LPA 不做的事：`LPA:1$…` scheme（`QrDownloadActivity`）、
  `android.service.euicc.action.*` 的 Google 版 UI，以及 HyperOS 在 provisioning 阶段
  （`Settings.Global.device_provisioned == 0`）把 eSIM UI 强制路由到 `com.google.android.euicc`
  的那段定制；
- 只有小米 LPA 缺失或被判为无效组件时，Google LPA 才会成为绑定目标。

两份 LPA 都必须是 priv-app 并进入 privapp 白名单：`isValidEuiccComponent` 的
`checkPermission(WRITE_EMBEDDED_SUBSCRIPTIONS)` 对数据应用恒为拒绝，这也是此前 Play/GMS
装回的 Google LPA 会崩、且 `LPA:` 链接无人处理的原因。Google LPA 依赖 GMS/Phenotype：
没有 GMS/GSF 的环境里它读不到配置，激活链接路由到它会失败；ES10 与 eSIM 管理仍由小米 LPA
完成。

## 环境要求

- 小米 HyperOS 4 / Android 15+（SDK 35+）；低版本仅提示，不阻止安装。
- KernelSU / SukiSU / APatch / Magisk 任一。
- KernelSU/SukiSU 需启用系统挂载元模块（**Hybrid Mount** 或 **meta-overlayfs**），
  否则 `/product` 覆盖不生效。
- LSPosed（支持 libxposed API 102）。
- 可拆 eUICC 放在 SIM2。

## 安装

1. KernelSU/SukiSU：先安装并启用 Hybrid Mount 或 meta-overlayfs，重启。
2. 刷入 `HypereSIM-<version>.zip`。
3. 安装 `HypereSIM-LSPosed-<version>.apk`，在 LSPosed 中启用模块，勾选
   `com.android.phone` 与 `com.miui.euicc`。
4. 重启。
5. 在系统设置中打开 eSIM，正常写卡/启用 profile。
6. 不要再执行 `pm uninstall com.google.android.euicc`：激活链接与开机向导阶段的 eSIM 界面
   依赖模块自带的特权副本（见「双 LPA 布置」）。安装时 `customize.sh` 会卸掉 `/data/app`
   的非特权副本，重启后由 `/product/priv-app` 的副本接管；若 Play/GMS 之后把它装回并通过
   `UPDATED_SYSTEM_APP` 覆盖，保留即可，签名一致时仍持有特权权限。

升级 zip 与 APK 时保持两者版本一致。卸载 zip 模块会移除 `/product`、`/vendor` 覆盖与
`system.prop`，不改 `Settings.Secure`，不删 profile，也不卸载 LSPosed 模块。

## 诊断

停用模块直接用 Root 管理器的模块开关（KernelSU 的 `disable` 文件），不需要额外脚本：
关闭后 `/product`、`/vendor` 覆盖与 `system.prop` 都不再生效。

诊断入口：

- Root 管理器的模块 Action 按钮：现场打印两套 LPA（`lpa_*` / `google_*`）的包名、路径、版本、
  `SYSTEM` 标志、eUICC 特性、近期 `Euicc-` 日志；不落盘。
- hook 行为：`logcat | grep HypereSIM`，或 LSPosed 日志
  `/data/adb/lspd/log/modules_*.log`。

## 构建

```bash
python tools/build_module.py
# outputs/HypereSIM-<version>.zip
# build/module/ 为可检查的暂存模块树

python tools/build_lsposed.py --offline
# outputs/HypereSIM-LSPosed-<version>.apk

python tools/build_lsposed.py --offline --debug
# outputs/HypereSIM-LSPosed-v1.1.0-debug.apk（debug 签名）
```

zip 构建会强校验随仓库入库的两份官方 LPA：`com.miui.euicc` 4.1.0.1，9,096,417 B，
md5 `4bd1e9b59606f84e075afed293a9dcaa`；`com.google.android.euicc` D.2.1.855215241-gms，
16,115,529 B，md5 `11d5a706742e45d98ef6b131d4106c27`。md5 或大小不一致直接失败，
避免误打包其他版本。

`--offline` 使用本机 Gradle 缓存；首次构建需联网拉依赖。release 默认使用
`lsposed/keystore/hyperesim-release.jks`（alias 与密码均为 `hyperesim`），保证本地与 CI
签名一致、APK 可覆盖升级；可用 `HYPERESIM_KEYSTORE`、`HYPERESIM_KEYSTORE_PASSWORD`、
`HYPERESIM_KEY_ALIAS`、`HYPERESIM_KEY_PASSWORD` 覆盖。该密钥公开，只用于签名一致性，
不构成安全边界。

版本号必须三处一致：

1. `module/module.prop`：`version` / `versionCode`
2. `lsposed/app/build.gradle.kts`：`versionName` / `versionCode`
3. `lsposed/app/src/main/resources/META-INF/xposed/module.prop`：`moduleVersion` / `moduleVersionCode`

## CI 与发布

`.github/workflows/build.yml`：

| 触发 | 行为 |
| --- | --- |
| push `main` / PR | 构建 zip + APK，上传 `hyperesim` artifact，不发布 |
| push `v*` tag | 校验 tag 与 `version` 一致，构建后创建 Release，附 zip、APK、`SHA256SUMS.txt` |
| `workflow_dispatch` | 手动构建；勾选 `release` 才发布 |

```bash
git tag v1.1.0
git push origin main v1.1.0
```

私有签名：配置仓库 secrets `HYPERESIM_KEYSTORE_BASE64`（`base64 -w0 release.jks`）、
`HYPERESIM_KEYSTORE_PASSWORD`、`HYPERESIM_KEY_ALIAS`、`HYPERESIM_KEY_PASSWORD`。

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `module/` | KernelSU zip 模块源文件：两套 LPA payload、privapp 白名单、Google LPA 默认权限、`telephony.euicc` 特性、MEP 禁用、安装/启动/诊断脚本 |
| `lsposed/` | LSPosed 行为层工程（Gradle wrapper + libxposed API 102） |
| `tools/build_module.py` | 构建并校验 zip |
| `tools/build_lsposed.py` | 构建并校验 LSPosed APK |
| `module/system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk` | 随仓库入库的官方未修改 MIUI LPA 4.1.0.1 |
| `module/system/product/priv-app/EuiccGoogle/EuiccGoogle.apk` | 随仓库入库的官方未修改 Google LPA D.2.1.855215241-gms |

逆向分析、真机取证与历史参考资料仅本地保留，不随仓库发布。

## 鸣谢

- [jabe1122/HyperOS4-ESTKme-eSIM-LPA](https://github.com/jabe1122/HyperOS4-ESTKme-eSIM-LPA)：
  提供官方未修改 `MIUIEsimLPA.apk`（4.1.0.1）与 HyperOS 4 实体 eSIM 真机结论。
  HypereSIM 不采用其 OMAPI 桥、卡信息伪造、UI 改写与双驻留方案。
- [tehcneko/XiaomiEsimLPA](https://github.com/tehcneko/XiaomiEsimLPA)：
  模块目录布局与 `priv-app` + 权限白名单思路的上游参考。

## 免责声明

非官方模块，与小米、LSPosed、上述上游作者无隶属关系。刷机、改系统分区有变砖与丢卡风险；
先在可恢复的设备上验证。模块不会写入 eUICC profile，也不会修改 `Settings.Secure`，但下载/
切换 profile 的行为仍取决于运营商、eUICC 与 LPA 本身。
