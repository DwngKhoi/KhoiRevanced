# Android manifest components are retained automatically. Keep the profile
# launcher intent extra stable while R8 minifies the manager implementation.
-keepclassmembers class com.dwngkhoi.revanced.MainActivity {
    <methods>;
}
