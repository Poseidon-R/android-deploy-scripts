package io.github.poseidon_r.deploy

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

/**
 * 构建 release APK 后,把 APK + 配置 JSON + 应用图标 + 下载页 + 版本清单
 * 一起通过 SFTP(SSH)上传到服务器。支持多版本:每次构建按版本号归档,同版本号覆盖。
 * 配置从 gradle.properties 的 upload.ftp.* / upload.version.* 读取(与脚本版本向后兼容)。
 */
class UploadReleaseApkTask extends DefaultTask {

    @TaskAction
    void upload() {
        def project = this.project

        // ====== 配置(各项目可在 gradle.properties 用 upload.ftp.* 覆盖)======
        // 隐私配置(host/port/user)不写死默认值,从 gradle.properties 或环境变量读,未配置则执行时报错
        def CFG_HOST      = project.findProperty('upload.ftp.host') ?: System.getenv('UPLOAD_FTP_HOST')
        def CFG_PORT      = (project.findProperty('upload.ftp.port') ?: System.getenv('UPLOAD_FTP_PORT'))?.toString()?.toInteger()
        def CFG_USER      = project.findProperty('upload.ftp.user') ?: System.getenv('UPLOAD_FTP_USER')
        def CFG_PWD_ENV   = project.findProperty('upload.ftp.passwordEnv')  ?: 'FTP_PASSWORD'
        def CFG_KEEP_MAX  = ((project.findProperty('upload.version.keepMax')) ?: '5').toString().toInteger()

        def android = project.extensions.findByName('android')
        if (android == null) {
            throw new GradleException("未检测到 android 扩展,本任务仅用于 Android 项目")
        }

        def resolveAppName = {
            def stringsXml = project.file("src/main/res/values/strings.xml")
            if (stringsXml.exists()) {
                return (new XmlSlurper().parse(stringsXml).string.find { it.@name == 'app_name' })?.text() ?: null
            }
            return null
        }
        def APP_NAME = resolveAppName()
        def CFG_BASE_DIR = project.findProperty('upload.ftp.baseDir') ?: "/root/upload/app/${(APP_NAME ?: android.defaultConfig.applicationId).toString().toLowerCase()}"
        def CFG_APK_DIR  = "${CFG_BASE_DIR}/apk"
        def CFG_ARCHIVE_DIR = "${CFG_APK_DIR}/archive"

        // 自动探测应用图标:优先 upload.iconPath;否则读 AndroidManifest 的 android:icon,找最高密度 mipmap/drawable 位图
        def resolveIconFile = {
            def configured = project.findProperty('upload.iconPath')
            if (configured) {
                def f = project.file(configured)
                if (f.exists()) return f
            }
            def manifest = project.file("src/main/AndroidManifest.xml")
            if (manifest.exists()) {
                def matcher = (manifest.text =~ /android:icon="([^"]+)"/)
                if (matcher) {
                    def ref = matcher[0][1]
                    if (ref.startsWith('@')) {
                        def seg = ref.substring(1).split('/')
                        if (seg.length == 2 && (seg[0] == 'mipmap' || seg[0] == 'drawable')) {
                            def type = seg[0], name = seg[1]
                            for (d in ['xxxhdpi', 'xxhdpi', 'xhdpi', 'hdpi', 'mdpi', '']) {
                                def dirName = d ? "${type}-${d}" : type
                                for (ext in ['png', 'webp']) {
                                    def f = project.file("src/main/res/${dirName}/${name}.${ext}")
                                    if (f.exists()) return f
                                }
                            }
                        }
                    }
                }
            }
            return null
        }

        // 隐私配置必填校验(不写死默认值)
        if (CFG_HOST == null || CFG_HOST.toString().trim().isEmpty()) {
            throw new GradleException("未配置 SFTP 地址。请在 gradle.properties 设 upload.ftp.host,或设环境变量 UPLOAD_FTP_HOST。")
        }
        if (CFG_PORT == null) {
            throw new GradleException("未配置 SFTP 端口。请在 gradle.properties 设 upload.ftp.port,或设环境变量 UPLOAD_FTP_PORT。")
        }
        if (CFG_USER == null || CFG_USER.toString().trim().isEmpty()) {
            throw new GradleException("未配置 SFTP 用户名。请在 gradle.properties 设 upload.ftp.user,或设环境变量 UPLOAD_FTP_USER。")
        }

        def password = System.getenv(CFG_PWD_ENV)
        if (password == null || password.trim().isEmpty()) {
            throw new GradleException(
                "未找到环境变量 ${CFG_PWD_ENV}。请先执行: setx ${CFG_PWD_ENV} \"你的密码\" 然后重开终端再运行本任务。"
            )
        }

        // 1. release APK(在 release 输出目录自动查找,兼容多 flavor 自定义文件名)
        def buildDir = project.layout.buildDirectory.get().asFile
        def releaseDir = new File(buildDir, "outputs/apk/release")
        def apkFile = releaseDir.listFiles()?.find { it.name.endsWith('.apk') }
        if (apkFile == null) {
            apkFile = new File(releaseDir, "app-release.apk")
        }
        if (!apkFile.exists()) {
            throw new GradleException("未找到 release APK: ${releaseDir.absolutePath}，请确认 assembleRelease 已成功执行。")
        }
        logger.lifecycle("[SFTP] APK: ${apkFile.name}")

        // 2. 图标(自动探测)
        def iconFile = resolveIconFile()
        if (iconFile == null) {
            throw new GradleException("未找到应用图标。可在 gradle.properties 配置 upload.iconPath，或确保 AndroidManifest 的 android:icon 指向存在的 mipmap/drawable 位图。")
        }

        // 3. 版本信息 + 归档目录名(按版本号归档,同版本号覆盖)
        def gitInfo = { String cmd ->
            try {
                def p = cmd.execute(null, project.rootDir)
                p.waitFor()
                return p.text.trim()
            } catch (Exception ignored) {
                return ""
            }
        }
        def now = new Date()
        def buildTimeStr = now.format('yyyy-MM-dd HH:mm:ss')
        def versionName = android.defaultConfig.versionName ?: 'unknown'
        def safeVersion = versionName.replaceAll('[^a-zA-Z0-9.-]', '_')
        def versionDir = "v${safeVersion}"
        def commit = gitInfo("git rev-parse --short HEAD")
        def branch = gitInfo("git rev-parse --abbrev-ref HEAD")

        // 最新版元信息(根目录 app-release.json + versions.json.current)
        def currentInfo = [
            appName     : APP_NAME ?: "Unknown",
            versionName : versionName,
            versionCode : android.defaultConfig.versionCode,
            buildTime   : buildTimeStr,
            commit      : commit,
            branch      : branch,
            apkPath     : "apk/app-release.apk"
        ]
        // 归档记录(versions.json.versions 的一条,apkPath 指向归档 APK)
        def versionEntry = [
            appName     : currentInfo.appName,
            versionName : currentInfo.versionName,
            versionCode : currentInfo.versionCode,
            buildTime   : buildTimeStr,
            commit      : commit,
            branch      : branch,
            archiveDir  : "apk/archive/${versionDir}",
            apkPath     : "apk/archive/${versionDir}/app-release.apk"
        ]

        def tmpDir = new File(buildDir, "tmp/deploy")
        tmpDir.mkdirs()
        def jsonTmp = new File(tmpDir, "app-release.json")
        jsonTmp.write(JsonOutput.prettyPrint(JsonOutput.toJson(currentInfo)), "UTF-8")
        def archiveJsonTmp = new File(tmpDir, "app-release-archive.json")
        archiveJsonTmp.write(JsonOutput.prettyPrint(JsonOutput.toJson(versionEntry)), "UTF-8")
        logger.lifecycle("[SFTP] 配置JSON: ${jsonTmp.text}")
        logger.lifecycle("[SFTP] 本次归档: ${versionDir}")

        // 4. 下载页(默认内置;可用 upload.pageTemplate 指向自定义 html 覆盖)
        def pageTemplate = project.findProperty('upload.pageTemplate')
        def indexTmp = new File(tmpDir, "index.html")
        indexTmp.write(pageTemplate ? project.file(pageTemplate).getText("UTF-8") : DefaultPage.HTML, "UTF-8")

        // 5. 连接 SFTP 并上传
        logger.lifecycle("[SFTP] 目标: ${CFG_USER}@${CFG_HOST}:${CFG_PORT}  base=${CFG_BASE_DIR}")

        Session session = null
        ChannelSftp channel = null
        try {
            JSch jsch = new JSch()
            session = jsch.getSession(CFG_USER, CFG_HOST, CFG_PORT)
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password")
            session.connect(15000)
            logger.lifecycle("[SFTP] SSH 连接成功")

            channel = (ChannelSftp) session.openChannel("sftp")
            channel.connect(15000)
            logger.lifecycle("[SFTP] SFTP 通道已建立")

            def ensureRemoteDir = { String dir ->
                channel.cd("/")
                dir.split("/").findAll { it }.each { part ->
                    try {
                        channel.cd(part)
                    } catch (SftpException ignored) {
                        channel.mkdir(part)
                        channel.cd(part)
                    }
                }
            }
            def uploadOne = { File local, String remoteDir, String remoteName ->
                ensureRemoteDir(remoteDir)
                long localSize = local.length()
                local.withInputStream { is ->
                    channel.put(is, remoteName, ChannelSftp.OVERWRITE)
                }
                long remoteSize = channel.stat(remoteName).getSize()
                if (remoteSize != localSize) {
                    throw new GradleException("上传大小不一致: ${remoteDir}/${remoteName} local=${localSize}, remote=${remoteSize}")
                }
                logger.lifecycle("[SFTP] 已上传: ${remoteDir}/${remoteName} (${localSize} bytes)")
            }

            // 5.1 读取远程已有的版本清单(首次上传时不存在)
            def remoteVersionsJson = "${CFG_BASE_DIR}/versions.json"
            List existingVersions = []
            try {
                def out = new ByteArrayOutputStream()
                channel.get(remoteVersionsJson, out)
                def parsed = new JsonSlurper().parseText(out.toString("UTF-8"))
                if (parsed instanceof Map && parsed.versions instanceof List) {
                    existingVersions = (List) parsed.versions
                }
            } catch (SftpException ignored) {
                // 首次上传,远程尚无 versions.json
            } catch (Exception e) {
                // versions.json 存在但解析失败(可能损坏),忽略并从空清单重建
                logger.warn("[SFTP] 远程 versions.json 解析失败,将重建清单: ${e.message}")
            }

            // 5.2 合并:同版本号覆盖(移除与本次相同的旧记录),本次插到头部,按 keepMax 截断
            def mergedVersions = [versionEntry] + existingVersions.findAll { it.versionName != versionEntry.versionName }
            def removedEntries = []
            if (CFG_KEEP_MAX > 0 && mergedVersions.size() > CFG_KEEP_MAX) {
                removedEntries = mergedVersions.drop(CFG_KEEP_MAX)
                mergedVersions = mergedVersions.take(CFG_KEEP_MAX)
            }

            // 5.3 上传文件
            //    根目录:最新版 APK / 最新版 JSON / 图标 / 下载页 / 版本清单
            //    归档目录:本次 APK + 本次 JSON(保留各版本快照)
            uploadOne(apkFile,        CFG_APK_DIR,                        "app-release.apk")
            uploadOne(apkFile,        "${CFG_ARCHIVE_DIR}/${versionDir}", "app-release.apk")
            uploadOne(archiveJsonTmp, "${CFG_ARCHIVE_DIR}/${versionDir}", "app-release.json")
            uploadOne(jsonTmp,        CFG_BASE_DIR,                       "app-release.json")
            uploadOne(iconFile,       CFG_BASE_DIR,                       "icon_launcher.png")

            // 5.4 清理超出 keepMax 的旧归档目录(递归删除)
            if (removedEntries) {
                def rmrfDir
                rmrfDir = { String absDir ->
                    try {
                        def entries = channel.ls(absDir)
                        entries.each { e ->
                            if (e.filename != "." && e.filename != "..") {
                                def child = absDir + "/" + e.filename
                                if (e.attrs.isDir()) {
                                    rmrfDir(child)
                                } else {
                                    channel.rm(child)
                                }
                            }
                        }
                        channel.rmdir(absDir)
                        logger.lifecycle("[SFTP] 已清理归档目录: ${absDir}")
                    } catch (SftpException ignored) {
                        // 目录可能已不存在,忽略
                    }
                }
                removedEntries.each { e ->
                    rmrfDir("${CFG_BASE_DIR}/${e.archiveDir}")
                }
            }

            // 5.5 版本清单(清理后落盘,反映最终状态)
            def versionsObj = [
                current : currentInfo,
                versions: mergedVersions
            ]
            def versionsTmp = new File(tmpDir, "versions.json")
            versionsTmp.write(JsonOutput.prettyPrint(JsonOutput.toJson(versionsObj)), "UTF-8")
            uploadOne(versionsTmp, CFG_BASE_DIR, "versions.json")

            // 5.6 下载页
            uploadOne(indexTmp, CFG_BASE_DIR, "index.html")

            logger.lifecycle("[SFTP] 全部上传完成,历史共 ${mergedVersions.size()} 个版本" +
                (removedEntries ? "(本次清理 ${removedEntries.size()} 个,keepMax=${CFG_KEEP_MAX})" : ""))
        } catch (Exception e) {
            throw new GradleException("SFTP 上传失败: ${e.message}", e)
        } finally {
            try { if (channel != null && channel.isConnected()) channel.disconnect() } catch (Exception ignored) {}
            try { if (session != null && session.isConnected()) session.disconnect() } catch (Exception ignored) {}
        }
    }
}
