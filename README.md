# Android Release 上传脚本 / Gradle 插件

构建 release APK 后,通过 SFTP(SSH)把 **APK + 配置 JSON + 应用图标 + 下载页 + 版本清单** 一起上传到服务器。支持多版本:按版本号归档,同版本号覆盖,下载页展示最新版 + 历史版本列表,可配置保留数量。

提供两种引入方式:
- **Gradle 插件**(推荐):从 Maven 仓库引入,`plugins {}` 一行接入,jsch 随插件走
- **脚本 apply from**(兼容/本地开发):git submodule,单文件自包含

## 引入方式

### 方式一:Gradle 插件(推荐)

`settings.gradle`(或 `.kts`)的 pluginManagement:
```groovy
pluginManagement {
    repositories {
        mavenCentral{
            content {
                includeGroup 'io.github.poseidon-r'
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
```

`app/build.gradle`:
```groovy
plugins {
    id 'com.android.application'
    id 'io.github.poseidon-r.sftp-upload' version '1.0.0'
}
```
Kotlin DSL:`id("io.github.poseidon-r.sftp-upload") version "1.0.0"`

无需 submodule、无需 buildscript。**本地开发验证**时,pluginManagement 的 repositories 加 `mavenLocal()`,并用仓库 `./gradlew publishToMavenLocal` 发布的版本。

### 方式二:脚本 apply from(兼容/本地开发)

```bash
git submodule add <本仓库地址> .deploy
```
`app/build.gradle` 末尾:
```groovy
apply from: "$rootDir/.deploy/ftp-upload.gradle"
```
Kotlin DSL:`apply(from = "$rootDir/.deploy/ftp-upload.gradle")`

克隆已包含本脚本的项目(否则 `.deploy/` 是空的):
```bash
git clone --recurse-submodules <项目url>
# 或已 clone 的:
git submodule update --init --recursive
```

## 配置(两种方式通用)

host/port/user 不写死默认值,从 `gradle.properties` 或环境变量读取,未配置执行时报错。其余可选项不配走默认。

| 属性 | 默认值 | 说明 |
|---|---|---|
| `upload.ftp.host` | **必填** | SFTP 服务器地址(或环境变量 `UPLOAD_FTP_HOST`) |
| `upload.ftp.port` | **必填** | 端口(或环境变量 `UPLOAD_FTP_PORT`) |
| `upload.ftp.user` | **必填** | 用户名(或环境变量 `UPLOAD_FTP_USER`) |
| `upload.ftp.passwordEnv` | `FTP_PASSWORD` | 密码所在的环境变量名 |
| `upload.ftp.baseDir` | `/root/upload/app/{appName 小写}` | 上传根目录 |
| `upload.iconPath` | 自动探测 | 图标本地路径(默认读 manifest 的 `android:icon`,取最高密度位图) |
| `upload.pageTemplate` | 内置下载页 | 自定义下载页 html 路径 |
| `upload.version.keepMax` | `5` | 保留最近多少个历史归档版本,`0` = 保留全部不清理 |

隐私信息(host/port/user/密码)不入库,从本机配置读:

```bash
# 方式一:gradle.properties(项目级,或 ~/.gradle/gradle.properties 本机级)
#   upload.ftp.host=192.168.x.x
#   upload.ftp.port=22
#   upload.ftp.user=your-user
# 方式二:环境变量
setx UPLOAD_FTP_HOST "192.168.x.x"
setx UPLOAD_FTP_PORT "22"
setx UPLOAD_FTP_USER "your-user"
setx FTP_PASSWORD "你的密码"   # Windows,设完重开终端
```

> 注意:Gradle daemon 缓存启动时的环境变量。`setx` 后需重开终端,或 `./gradlew --stop` 后重跑。

## 运行

```bash
./gradlew uploadReleaseApkToFtp
```
自动先执行 `assembleRelease`,再上传。

## 上传结果

```
{baseDir}/
  ├── index.html          下载页(显示最新版 + 历史版本列表 + 二维码)
  ├── app-release.json    最新版元信息(应用名/版本号/构建时间/git提交/分支)
  ├── icon_launcher.png   应用图标
  ├── versions.json       全部版本清单(最新版 + 历史归档)
  └── apk/
      ├── app-release.apk           最新版安装包(始终指向最新,旧二维码/链接仍可用)
      └── archive/
          └── v{version}/
              ├── app-release.apk   该版本归档安装包
              └── app-release.json  该版本元信息快照
```

访问 `{baseDir}/index.html` 即可看到下载页:顶部为最新版(下载按钮 + 二维码),下方列出历史版本可点选下载。

## 多版本机制

- 每次上传会把当前 APK + 元信息归档到 `apk/archive/v{版本号}/`(如 `v1.0.5/`),并在根目录覆盖最新版 `apk/app-release.apk`(旧二维码和下载链接始终指向最新版,不会失效)。
- 同版本号再次构建会覆盖该版本号的归档目录(不产生重复历史),版本历史按版本号区分:如 1.0.5 / 1.0.4 / 1.0.3 / 1.0.2。
- 维护一份 `versions.json` 清单,记录最新版与全部历史版本(按版本号去重)。下载页读取该清单动态渲染。
- 超过 `upload.version.keepMax` 的最旧归档会被自动删除(含目录和清单记录);设为 `0` 则永久保留全部版本。

## 前提
release 构建。APK 在 `outputs/apk/release/` 自动查找(支持 `productFlavors` 自定义文件名);多 ABI splits 仍只传第一个。

## 发布到 Maven Central(本仓库开发者用)

本仓库本身是一个 Gradle 插件项目,可发布到 Maven Central。

### 前置准备(一次性)
1. 注册 Sonatype Central Portal: https://central.sonatype.com/
2. 验证 namespace:`io.github.poseidon-r` 需对应你的 GitHub 用户名(在 GitHub 建同名仓库验证);或改为自有域名反向
3. 生成 GPG 密钥并上传公钥到 keyserver
4. 凭证放 `~/.gradle/gradle.properties`(不入库):
   ```properties
   sonatypeUsername=...
   sonatypePassword=...
   signing.useInMemoryPgpKeys=true
   signing.keyId=...
   signing.password=...
   signing.key=...(ASCII armored private key)
   ```

### 发布
```bash
./gradlew publishToMavenLocal   # 本地 ~/.m2 验证
./gradlew publish                # 发布到 Sonatype staging
```
然后在 Central Portal 点 Release(首次需审核)。

> **namespace(group ID)若非 `io.github.poseidon-r`**,需全局替换:`gradle.properties` 的 `GROUP`/`PLUGIN_ID`/`POM_*`,`build.gradle` 的 `implementationClass`,`src/main/groovy/` 目录结构,以及三个源码文件的 `package` 声明。

> Maven Central 版本**不可覆盖**,改了发新版本号(改 `gradle.properties` 的 `VERSION`)。

## 更新脚本

- 改了**插件源码**:`./gradlew publish` 发新版本,各项目更新 `version`
- 改了**脚本(apply from 方式)**:在 `.deploy` 提交推送,各项目 `git submodule update --remote .deploy`
```bash
cd .deploy
git add . && git commit -m "update script" && git push
cd ..
git submodule update --remote .deploy
git add .deploy && git commit -m "bump deploy submodule"
```
