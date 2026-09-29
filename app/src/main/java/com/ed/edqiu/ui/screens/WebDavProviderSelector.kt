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
 */
data class WebDavProvider(
    val id: String,
    val name: String,
    val serverUrlTemplate: String,
    val remotePathTemplate: String,
    val description: String
)

/** 网盘预设列表（最后一个为自定义，模板为空表示不覆盖用户已填内容）。 */
val webDavProviders: List<WebDavProvider> = listOf(
    WebDavProvider(
        id = "baidu",
        name = "百度网盘",
        serverUrlTemplate = "http://<内网IP>:19798/dav",
        remotePathTemplate = "/百度网盘",
        description = "百度网盘无官方 WebDAV，需先用 CloudDrive2/alist 挂载后通过本地 WebDAV 同步，端口默认 19798，可按实际修改。"
    ),
    WebDavProvider(
        id = "123pan",
        name = "123网盘",
        serverUrlTemplate = "https://webdav.123pan.cn/webdav",
        remotePathTemplate = "/",
        description = "123 网盘官方 WebDAV，账号为手机号/邮箱，密码为 WebDAV 密码（非登录密码，需在 123 网盘网页端设置里生成）。也可在「我的 → 网盘备份」走直连授权新入口，无需手动填写服务器地址。"
    ),
    WebDavProvider(
        id = "clouddrive2",
        name = "CloudDrive2",
        serverUrlTemplate = "http://<内网IP>:19798/dav",
        remotePathTemplate = "/",
        description = "CloudDrive2 安装后默认 WebDAV 端口 19798，填入安装机器的局域网 IP，账号密码在 CloudDrive2 设置里配置。"
    ),
    WebDavProvider(
        id = "aliyun",
        name = "阿里云盘",
        serverUrlTemplate = "http://<内网IP>:5244/dav",
        remotePathTemplate = "/阿里云盘",
        description = "阿里云盘无官方 WebDAV，推荐用 alist（默认端口 5244）或 CloudDrive2 挂载后同步。"
    ),
    WebDavProvider(
        id = "migu",
        name = "移动云盘",
        serverUrlTemplate = "https://webdav.139.com",
        remotePathTemplate = "/",
        description = "移动云盘官方 WebDAV，账号为手机号，密码为 WebDAV 密码（在移动云盘设置中开启 WebDAV 后生成）。"
    ),
    WebDavProvider(
        id = "cstcloud",
        name = "数据胶囊",
        serverUrlTemplate = "https://data.cstcloud.cn/webdav",
        remotePathTemplate = "/XInvox",
        description = "中科院中国科技云数据胶囊，实名认证后免费 20GB 永久空间，不限流量不限速，原生支持 WebDAV。账号为中国科技云通行证，密码在数据胶囊网页端「设置」中生成。"
    ),
    WebDavProvider(
        id = "infinicloud",
        name = "InfiniCloud",
        serverUrlTemplate = "https://<用户名>.teracloud.jp/dav/",
        remotePathTemplate = "/XInvox",
        description = "日本老牌免费网盘（原 TeraCloud），注册 20GB + 邀请码 5GB = 25GB 永久免费，不限流量。服务器地址在 My Page → Apps Connection 中查看，密码为 Apps Password（非登录密码）。"
    ),
    WebDavProvider(
        id = "jianguoyun",
        name = "坚果云",
        serverUrlTemplate = "https://dav.jianguoyun.com/dav/",
        remotePathTemplate = "/XInvox",
        description = "坚果云官方 WebDAV，账号为注册邮箱，密码为应用密码（在「账户信息」→「安全选项」→「第三方应用管理」中添加应用生成）。免费版 3GB 空间，月上传 1GB / 下载 3GB，适合文档同步。"
    ),
    WebDavProvider(
        id = "custom",
        name = "自定义",
        serverUrlTemplate = "",
        remotePathTemplate = "",
        description = "自定义：手动填写 WebDAV 地址、账号、密码与远程目录，适用于任何支持 WebDAV 的云盘或自建服务。"
    )
)

/**
 * 横向滚动的网盘预设胶囊 chips。
 *
 * 视觉风格参考库内 FilterTabs：选中时 primaryContainer 底色 + 内描边 + 语义点，
 * 未选中时 surfaceContainer 半透明底 + 淡描边。
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
                        text = provider.name,
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
