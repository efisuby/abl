# Project-specific ProGuard rules.
# Media3/ExoPlayer ships consumer rules inside its AARs, so only
# project-specific keeps belong here.

# The Yandex ID SDK references the Parcelize annotation, which is compile-time only.
-dontwarn kotlinx.parcelize.Parcelize
