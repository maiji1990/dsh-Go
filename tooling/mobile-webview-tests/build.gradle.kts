plugins { id("com.android.application") version "9.1.0" }

android {
    namespace = "io.github.dshgo.mobiletest"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.dshgo.mobiletest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("mobileAssets").get().asFile)
}
val mobileAssets by tasks.registering(Sync::class) {
    from("../../app/assets/islands") { include("*.js", "*.css") }
    from("../../app/assets/web") { include("plugin_mobile.js") }
    from("../../app/test/plugins/islands/fixtures") { include("mobile_*.html") }
    into(layout.buildDirectory.dir("mobileAssets"))
}
tasks.named("preBuild").configure { dependsOn(mobileAssets) }
