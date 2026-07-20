// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// KhoiRevanced keeps its non-Xposed runtime in separate modules. The upstream
// :app module stays intentionally close to NexAlloy so merges remain reviewable.
