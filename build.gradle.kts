// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // 添加下面这行（版本与你的 Kotlin 版本保持一致，例如 2.0.0 或当前使用的 kotlin 版本）：
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}