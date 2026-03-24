package com.tigerduck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val courseColorPalette: List<Color> = listOf(
    Color(0xFFFF6B6B), // 珊瑚紅
    Color(0xFF4ECDC4), // 青綠
    Color(0xFF45B7D1), // 天藍
    Color(0xFFF39C12), // 橘橙
    Color(0xFFDDA0DD), // 梅紫
    Color(0xFF2ECC71), // 翡翠綠
    Color(0xFFE74C3C), // 磚紅
    Color(0xFF3498DB), // 寶藍
    Color(0xFFF7DC6F), // 金黃
    Color(0xFF9B59B6), // 紫羅蘭
    Color(0xFF1ABC9C), // 碧綠
    Color(0xFFE67E22), // 南瓜橘
    Color(0xFF85C1E9), // 淺藍
    Color(0xFFD35400), // 焦橙
    Color(0xFF27AE60), // 森林綠
    Color(0xFFC0392B), // 酒紅
    Color(0xFF8E44AD), // 深紫
    Color(0xFF16A085), // 松綠
    Color(0xFFF1C40F), // 向日葵黃
    Color(0xFF2980B9), // 鈷藍
)

object TigerDuckTheme {
    private var courseColorMap: Map<String, Color> = emptyMap()

    fun buildCourseColorMap(courseNos: List<String>) {
        val sorted = courseNos.sorted()
        courseColorMap = sorted.mapIndexed { index, courseNo ->
            courseNo to courseColorPalette[index % courseColorPalette.size]
        }.toMap()
    }

    fun courseColor(courseNo: String): Color {
        courseColorMap[courseNo]?.let { return it }
        val hash = courseNo.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
        return courseColorPalette[hash % courseColorPalette.size]
    }
}

object Spacing {
    const val xs = 4
    const val sm = 8
    const val md = 12
    const val lg = 16
    const val xl = 24
    const val xxl = 32
}

object CornerRadius {
    const val sm = 8
    const val md = 12
    const val lg = 18
    const val xl = 24
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF5AC8FA),
    background = Color(0xFF1C1C1E),
    surface = Color(0xFF2C2C2E),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF5AC8FA),
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
)

@Composable
fun TigerDuckAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = Color(0xFF007AFF),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme.copy(primary = accentColor)
    } else {
        LightColorScheme.copy(primary = accentColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
