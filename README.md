# Android Release 上传脚本

`ftp-upload.gradle` —— 构建 release APK 后,通过 SFTP(SSH)把 **APK + 配置 JSON + 应用图标 + 下载页** 一起上传到服务器。单文件自包含,各项目用 `gradle.properties` 配置。

## 接入(作为 submodule 引入)

**新项目引入:**
```bash
git submodule add <本仓库地址> .deploy
```
在 `app/build.gradle` 末尾添加:
```groovy
apply from: "$rootDir/.deploy/ftp-upload.gradle"
```

**克隆已包含本脚本的项目**(否则 `.deploy/` 是空的,`apply from` 会找不到脚本):
```bash
git clone --recurse-submodules <项目url>
# 或已经 clone 过的:
git submodule update --init --recursive
```

## 配置(在项目 `gradle.properties` 中,全部可选,不配走默认)

| 属性 | 默认值 | 说明 |
|---|---|---|
| `upload.ftp.host` | `192.168.3.91` | SFTP 服务器地址 |
| `upload.ftp.port` | `22` | 端口 |
| `upload.ftp.user` | `root` | 用户名 |
| `upload.ftp.passwordEnv` | `FTP_PASSWORD` | 密码所在的环境变量名 |
| `upload.ftp.baseDir` | `/root/upload/app/{appName 小写}` | 上传根目录 |
| `upload.iconPath` | 自动探测 | 图标本地路径(默认读 manifest 的 `android:icon`,取最高密度位图) |
| `upload.pageTemplate` | 内置下载页 | 自定义下载页 html 路径 |

密码只从环境变量读取,不入库:
```bash
setx FTP_PASSWORD "你的密码"   # Windows,设完重开终端
```

> 注意:Gradle daemon 缓存启动时的环境变量。`setx` 后需重开终端,或执行
> `$env:FTP_PASSWORD = [Environment]::GetEnvironmentVariable('FTP_PASSWORD','User')`(PowerShell)后重跑,
> 或 `./gradlew --stop` 后重跑。

## 运行

```bash
./gradlew uploadReleaseApkToFtp
```

会自动先执行 `assembleRelease`,再上传。

## 上传结果

```
{baseDir}/
  ├── index.html          下载页(内置,显示应用信息 + 下载按钮 + 二维码)
  ├── app-release.json    应用名 / 版本号 / 构建时间 / git 提交 / 分支
  ├── icon_launcher.png   应用图标
  └── apk/
      └── app-release.apk  安装包
```

访问 `{baseDir}/index.html` 即可看到下载页。

## 前提
release 构建。APK 在 `outputs/apk/release/` 自动查找(支持 `productFlavors` 自定义文件名);多 ABI splits 仍只传第一个。

## 更新脚本

改了 `ftp-upload.gradle` 后,各项目要同步:

```bash
# 1. 在本仓库提交并推送(可在任意项目的 .deploy/ 目录里改)
cd .deploy
git add . && git commit -m "update script" && git push
cd ..

# 2. 各项目拉取最新(更新 submodule 引用)
git submodule update --remote .deploy
git add .deploy && git commit -m "bump deploy submodule"
```
