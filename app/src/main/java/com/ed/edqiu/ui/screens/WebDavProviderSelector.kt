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
 *
 * 2026-11 勘误：移除移动云盘预设（webdav.139.com 实测无效——DNS 解析到内网、502，
 * 移动云盘无官方 WebDAV，仅私有 API）；城通网盘 WebDAV 已收紧为仅付费会员可用。
 */
val webDavProviders: List<WebDavProvider> = listOf(
    WebDavProvider(
        id = "123pan",
        name = "123网盘",
        serverUrlTemplate = "https://webdav-{账号ID}.pd1.123pan.cn/webdav",
        remotePathTemplate = "/",
        description = "123 网盘官方 WebDAV（需 VIP）：网页端「第三方挂载 → WebDAV 授权管理 → 添加应用」生成应用密码（只显示一次，非登录密码，勾选读写权限）。⚠️ 服务器地址以控制台给出的完整地址为准，直接粘贴即可（含你的账号专属域名，勿照模板手改）。",
        needsMembership = true,
    ),
    WebDavProvider(
        id = "ctfile",
        name = "城通网盘",
        serverUrlTemplate = "https://dav.ctfile.com/ws<用户ID>",
        remotePathTemplate = "",
        description = "城通网盘（ctfile.com）。⚠️ 2026 年起 WebDAV 仅付费会员可用——免费账号即使凭证正确也返回 401，本预设仅供会员。登录网页版切回旧版界面 → 左侧「WebDAV」查看专属地址（形如 https://dav.ctfile.com/ws数字ID）；账号为注册邮箱，密码为账号密码。远程目录必须留空：地址本身即工作空间根，任何子目录（含中文）都返回 401。服务条款禁止将 WebDAV 用于同步/备份，建议手动同步、慎开自动备份。",
        needsMembership = true,
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
        id = "cstcloud",
        name = "数据胶囊",
        serverUrlTemplate = "https://data.cstcloud.cn/dav",
        remotePathTemplate = "",
        description = "中科院中国科技云数据胶囊，实名认证后免费 20GB 永久空间，原生支持 WebDAV。账号为中国科技云通行证，密码在数据胶囊网页端「客户端访问」中生成。⚠️ 其 WebDAV 按客户端 User-Agent 校验应用类型，且上传仅放行 .zip/.prop（Zotero 文献同步专用），本应用的视频/图片无法经此上传；如需传视频请改用其 S3 接口或其它网盘。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "infinicloud",
        name = "InfiniCloud",
        serverUrlTemplate = "https://webdav.teracloud.jp/dav/",
        remotePathTemplate = "/Edqiu",
        description = "日本老牌免费网盘（原 TeraCloud），注册 20GB + 邀请码 5GB = 25GB 永久免费，不限流量，条款允许同步。⚠️ 需先在 My Page → Apps Connection 开启「Turn on Apps Connection」，地址以 My Page 显示的「WebDAV Connection URL」为准（形如 https://webdav.teracloud.jp/dav/ 或 https://xxx.infini-cloud.net/dav/），密码为系统随机生成的 Apps Password（非登录密码，只显示一次）。",
        needsMembership = false,
    ),
    WebDavProvider(
        id = "jianguoyun",
        name = "坚果云",
        serverUrlTemplate = "https://dav.jianguoyun.com/dav/",
        remotePathTemplate = "/Edqiu",
        description = "坚果云官方 WebDAV，账号为注册邮箱，密码为应用密码（「账户信息」→「安全选项」→「第三方应用管理」添加应用生成，非登录密码）。免费版按流量计：月上传 1GB / 下载 3GB（流量受限，仅够小文档，视频慎用）。⚠️ WebDAV 不支持在根目录建文件，需先在网页端创建「Edqiu」文件夹；现在开启 WebDAV 需安装坚果云客户端。",
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
