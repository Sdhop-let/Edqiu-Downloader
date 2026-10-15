package com.ed.edqiu.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * WebDAV 网盘预设数据。
 *
 * 预设只是「自动填模板」：点击后会把 serverUrlTemplate / remotePathTemplate 填入配置，
 * 用户仍可手动修改地址、路径、账号与密码。
 *
 * @property needsMembership 该网盘的 WebDAV 功能是否需要付费会员/VIP（仅作 UI 提示，不阻断配置）
 */
data class WebDavProvider(
    val id: String,
    val name: String,
    val serverUrlTemplate: String,
    val remotePathTemplate: String,
    val description: String,
    val needsMembership: Boolean = false,
)

/**
 * 网盘预设列表（最后一个为自定义，模板为空表示不覆盖用户已填内容）。
 *
 * 2026-10 统一 WebDAV 通道：移除百度网盘 / 阿里云盘预设——两者无官方 WebDAV，
 * 模板里的 `<内网IP>` 占位属"配置不完整"误导源，用户照填必然同步失败。
 * 此类网盘可经 CloudDrive2 / alist 挂载出 WebDAV 后选「自定义」接入（描述已注明）。
 * 新增城通网盘预设（官方支持 WebDAV 的国产老牌网盘）。
 */
val webDavProviders: List<WebDavProvider> = listOf(
    WebDavProvider(
        id = "123pan",
        name = "123网盘",
        serverUrlTemplate = "https://webdav-{账号ID}.pd1.123pan.cn/webdav",
        remotePathTemplate = "/",
        description = "123 网盘官方 WebDAV：网页端「第三方挂载 → WebDAV 授权管理 → 添加应用」生成应用密码（只显示一次，非登录密码，勾选读写权限）。把地址中的 {账号ID} 替换为你的账号 ID，或直接粘贴控制台给出的完整地址。",
        needsMembership = true,
    ),
    WebDavProvider(
        id = "ctfile",
        name = "城通网盘",
        serverUrlTemplate = "https://dav.ctfile.com/ws<用户ID>",
        remotePathTemplate = "/",
        description = "城通网盘（ctfile.com），免费注册即享 5TB 共享空间，官方支持 WebDAV。登录网页版切回旧版界面 → 左侧「WebDAV」查看你的专属地址（形如 https://dav.ctfile.com/ws数字ID，直接覆盖本模板即可）；账号为注册邮箱，密码为账号密码。免费文件连续 6 个月无下载可能被清理，媒体库定期访问即续期。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "clouddrive2",
        name = "CloudDrive2",
        serverUrlTemplate = "http://<内网IP>:19798/dav",
        remotePathTemplate = "/",
        description = "CloudDrive2 安装后默认 WebDAV 端口 19798，填入安装机器的局域网 IP，账号密码在 CloudDrive2 设置里配置。可把百度/阿里等无官方 WebDAV 的网盘挂载成本地目录后统一接入。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "migu",
        name = "移动云盘",
        serverUrlTemplate = "https://webdav.139.com",
        remotePathTemplate = "/",
        description = "移动云盘官方 WebDAV，账号为手机号，密码为 WebDAV 密码（在移动云盘设置中开启 WebDAV 后生成）。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "cstcloud",
        name = "数据胶囊",
        serverUrlTemplate = "https://data.cstcloud.cn/webdav",
        remotePathTemplate = "/XInvox",
        description = "中科院中国科技云数据胶囊，实名认证后免费 20GB 永久空间，不限流量不限速，原生支持 WebDAV。账号为中国科技云通行证，密码在数据胶囊网页端「设置」中生成。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "infinicloud",
        name = "InfiniCloud",
        serverUrlTemplate = "https://<用户名>.teracloud.jp/dav/",
        remotePathTemplate = "/XInvox",
        description = "日本老牌免费网盘（原 TeraCloud），注册 20GB + 邀请码 5GB = 25GB 永久免费，不限流量。服务器地址在 My Page → Apps Connection 中查看，密码为 Apps Password（非登录密码）。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "jianguoyun",
        name = "坚果云",
        serverUrlTemplate = "https://dav.jianguoyun.com/dav/",
        remotePathTemplate = "/XInvox",
        description = "坚果云官方 WebDAV，账号为注册邮箱，密码为应用密码（在「账户信息」→「安全选项」→「第三方应用管理」中添加应用生成）。免费版 3GB 空间，月上传 1GB / 下载 3GB，适合文档同步。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "custom",
        name = "自定义",
        serverUrlTemplate = "",
        remotePathTemplate = "",
        description = "自定义：手动填写 WebDAV 地址、账号、密码与远程目录，适用于任何支持 WebDAV 的云盘或自建服务（NAS / alist / Nextcloud / 群晖等）。百度网盘、阿里云盘等无官方 WebDAV 的网盘可用 CloudDrive2 或 alist 挂载后从本项接入。",
        needsMembership = false,
    ),
)

/**
 * 横向滚动的网盘预设胶囊 chips。
 *
 * 视觉风格参考库内 FilterTabs：选中时 primaryContainer 底色 + 内描边 + 语义点，
 * 未选中时 surfaceContainer 半透明底 + 淡描边。
 * 需要会员的网盘名后缀「*」角标（选中有 description 说明，不作阻断）。
 */
@Composable
fun WebDavProviderSelector(
    selectedProviderId: String,
    onProviderSelected: (WebDavProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        webDavProviders.forEach { provider ->
            val selected = provider.id == selectedProviderId
            Surface(
                modifier = Modifier.clickable { onProviderSelected(provider) },
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 选中态语义点：保留双通道辨识（无障碍要求）
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = if (provider.needsMembership) "${provider.name}✻" else provider.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}
