# EnterSend release keep rules (R8). Accessibility service + Room must survive minify.

-keep class com.meowgi.entersend.service.EnterSendService { *; }
-keep class com.meowgi.entersend.ServiceWatch { *; }
-keep class com.meowgi.entersend.ServiceWatch$* { *; }
-keep class com.meowgi.entersend.EnterSendApp { *; }

# Room entities / DAOs
-keep class com.meowgi.entersend.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# Kotlin metadata used by Room / serialization
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
