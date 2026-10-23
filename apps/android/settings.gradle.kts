pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "QingStudyPrototype"
include(":app")
// 独立、无网络权限的离线语音实验，不将模型与依赖加入正式输入法。
if (providers.gradleProperty("voiceLab").orNull == "true") include(":speech-lab")
// 独立在线语音链路实验；正式输入法不依赖此模块。
if (providers.gradleProperty("funasrLab").orNull == "true") include(":online-speech-lab")
// 整理/翻译独立实验，不将模型或运行库加入正式输入法。
if (providers.gradleProperty("textLab").orNull == "true") include(":text-lab")
