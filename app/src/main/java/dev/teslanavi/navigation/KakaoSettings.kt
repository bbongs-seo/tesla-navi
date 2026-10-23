package dev.teslanavi.navigation

import android.content.Context
import dev.teslanavi.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class KakaoSettings(context: Context, bundledNativeKey: String = BuildConfig.KAKAO_NATIVE_APP_KEY) {
    private val preferences = context.getSharedPreferences("kakao-settings", Context.MODE_PRIVATE)
    val nativeKey: String? = bundledNativeKey.takeIf { it.isNotBlank() }
    val hasKey = MutableStateFlow(nativeKey != null).asStateFlow()

    init {
        // Retire the former key editor while preserving the SDK user ID.
        preferences.edit().remove("native-key").remove("disabled").apply()
    }

    val userId: String get() = preferences.getString("user-id", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("user-id", it).commit()
    }
}
