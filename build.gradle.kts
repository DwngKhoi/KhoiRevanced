// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// KhoiRevanced keeps the product runtime in separate modules. The
// :nexalloy-payload module is a build-time compatibility payload only; the
// standalone manager APK and the root runtime do not depend on LSPosed.
