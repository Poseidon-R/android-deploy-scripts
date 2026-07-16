package io.github.poseidon_r.deploy

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Android release SFTP 上传插件。
 *
 * 各项目引入:
 *   plugins { id 'io.github.poseidon-r.sftp-upload' version '1.0.0' }
 *
 * 配置(在 gradle.properties,全部可选,与脚本版本完全兼容):
 *   upload.ftp.host / port / user / passwordEnv / baseDir
 *   upload.iconPath / pageTemplate
 *   upload.version.keepMax
 *
 * 运行: ./gradlew uploadReleaseApkToFtp
 */
class DeployPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        def hasAndroid = project.extensions.findByName('android') != null
        project.tasks.register('uploadReleaseApkToFtp', UploadReleaseApkTask) {
            group = 'deploy'
            description = '构建 release APK 并通过 SFTP 上传 APK/配置JSON/图标/下载页/版本清单'
            if (hasAndroid) {
                dependsOn 'assembleRelease'
            } else {
                project.logger.warn("[sftp-upload] 未检测到 android 扩展,task 已注册但执行时会失败(本插件仅用于 Android 项目)")
            }
        }
    }
}
