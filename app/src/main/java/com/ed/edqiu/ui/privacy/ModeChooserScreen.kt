package com.ed.edqiu.ui.privacy

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ed.edqiu.BuildConfig
import com.ed.edqiu.data.preferences.PrivacyMode
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.WallpaperProvider
import com.ed.edqiu.ui.util.pressableNoRipple

/**
 * 浏览模式选择器（2026-11-07 隐私模式定案；同日改版：命名对齐直觉 + 卡片等高自适应）：
 * 每次冷启动进入主界面前出现，两张模式卡（上次选择边框高亮）+「不再每次询问」勾选，
 * 点卡片即进入。
 *
 * 命名（v1.9.16 起）：「标准模式」= 媒体正常显示；「隐私模式」= 模糊防偷看——
 * 模糊保护隐私，与多数 App 的"隐私"心智一致，不再用场合语义反推。
 *
 * 「上次选择」外置为卡片下方单行小字：旧版放卡内第三行，固定卡高压不住直接裁切。
 *
 * 舞台语言与 SplashContent 同源：壁纸虚化铺底 + 深色 scrim（0.35 = 可读性基准），
 * 固定白色系文字——壁纸不可控，选择器文字必须与主题无关地可读。
 */
@Composable
fun ModeChooserScreen(
    lastMode: PrivacyMode,
    onChoose: (mode: PrivacyMode, rememberChoice: Boolean) -> Unit,
) {
    val context = LocalContext.current
    // 舞台：壁纸异步加载（进程缓存未热时首次 ~100ms），到位前先纯色场
    val wallpaper by produceState<ImageBitmap?>(initialValue = WallpaperProvider.current) {
        value = WallpaperProvider.load(context)
    }
    var rememberChoice by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F0D))
    ) {
        // 舞台层：壁纸（Crop + 压暗）
        wallpaper?.let { wp ->
            Image(
                bitmap = wp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.45f)
            )
        }
        // scrim：可读性基准与主界面一致（深 0.35）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // 2026-11-07：补导航栏避让——3-button 导航设备上版本号曾被压半截
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            // 品牌徽记（与开屏/我的页头像同构：玻璃环 + E）
            GlassSurface(
                tier = GlassTier.L2,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "E",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "选择浏览模式",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "随时可在 设置 · 外观 中更改",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeCard(
                    title = "标准模式",
                    description = "媒体正常显示\n适合独处时浏览",
                    icon = Icons.Outlined.Visibility,
                    selected = lastMode == PrivacyMode.PRIVATE,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 148.dp),
                ) {
                    onChoose(PrivacyMode.PRIVATE, rememberChoice)
                }
                ModeCard(
                    title = "隐私模式",
                    description = "媒体模糊防偷看\n按住可临时查看",
                    icon = Icons.Outlined.VisibilityOff,
                    selected = lastMode == PrivacyMode.PUBLIC,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 148.dp),
                ) {
                    onChoose(PrivacyMode.PUBLIC, rememberChoice)
                }
            }

            // 上次选择外置单行（2026-11-07）：旧版卡内第三行被固定卡高裁掉半截
            Text(
                text = "上次选择：" + if (lastMode == PrivacyMode.PRIVATE) "标准模式" else "隐私模式",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(Modifier.height(16.dp))

            // 不再每次询问：勾选后记住本次选择，之后冷启动直达主界面（设置页可改回）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .pressableNoRipple { rememberChoice = !rememberChoice }
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = rememberChoice,
                    onCheckedChange = { rememberChoice = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color.White,
                        checkmarkColor = Color(0xFF0B0F0D),
                        uncheckedColor = Color.White.copy(alpha = 0.55f),
                    ),
                )
                Text(
                    text = "不再每次询问，记住此选择",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }

            Spacer(Modifier.weight(1.2f))

            Text(
                text = "Edqiu v${BuildConfig.VERSION_NAME}",
                color = Color.White.copy(alpha = 0.40f),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f)
    GlassSurface(
        tier = GlassTier.L2,
        shape = RoundedCornerShape(20.dp),
        // 等高自适应：高度由 Row(IntrinsicSize.Min) 统一取两卡内容较高者，不再固定 168dp
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .pressableNoRipple { onClick() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassSurface(
                tier = GlassTier.L1,
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
