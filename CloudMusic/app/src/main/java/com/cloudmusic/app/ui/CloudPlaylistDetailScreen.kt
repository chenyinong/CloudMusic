package com.cloudmusic.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudmusic.app.App
import com.cloudmusic.app.data.Track
import com.cloudmusic.app.data.cloud.CloudPlaylistDetail
import com.cloudmusic.app.data.cloud.CloudTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云端歌单详情。
 *
 * 跟本机歌单详情分开：云端的歌没有本地文件，播不了「导入到网盘」那套，
 * 只做「在线解析直链 + 播放」。
 */
class CloudPlaylistDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef: App = app as App

    var playlist by mutableStateOf<CloudPlaylistDetail?>(null)
        private set

    var tracks by mutableStateOf<List<CloudTrack>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf("")
        private set

    val player get() = appRef.player

    private val controller get() = appRef.cloudPlayback

    fun load(id: Long) {
        if (loading) return
        // 进页面时把「点占位歌」的接管者抢回来（歌单页也注册过同一个回调）
        player.onUnresolvedPick = { track -> controller.resolvePicked(track) }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val detail = appRef.cloudSync.pullPlaylistDetail(id)
                playlist = detail
                tracks = detail.tracks
            } catch (e: Exception) {
                // 云端拉不到就先看本地缓存，别让用户对着空白页
                val cached = withContext(Dispatchers.IO) { appRef.cloudStore.playlistDetail(id) }
                if (cached != null) {
                    playlist = cached
                    tracks = cached.tracks
                    error = "云端拉取失败，先看本地缓存：${e.message ?: "网络异常"}"
                } else {
                    error = e.message ?: "打不开这张云端歌单"
                }
            } finally {
                loading = false
            }
        }
    }

    fun playTrack(t: CloudTrack) {
        val idx = tracks.indexOfFirst { it.key == t.key }
        if (idx < 0) return
        viewModelScope.launch {
            message = "正在找《${t.title}》的可播直链…"
            val err = controller.play(tracks, idx)
            message = ""
            if (err != null) error = err
        }
    }

    fun playAll() {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            message = "正在找第一首的可播直链…"
            val err = controller.play(tracks, 0)
            message = ""
            if (err != null) error = err
        }
    }
}

/**
 * 云端歌单详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudPlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
) {
    val vm: CloudPlaylistDetailViewModel = viewModel()
    LaunchedEffect(playlistId) { vm.load(playlistId) }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    vm.playlist?.name ?: "云端歌单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        if (vm.loading && vm.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (vm.error != null && vm.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    vm.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalScreenBottomPad.current)
        ) {
            item(key = "header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = vm.tracks.isNotEmpty()) { vm.playAll() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "播放全部",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "播放全部",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${vm.tracks.size} 首 · 云端歌单",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "云端",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (vm.message.isNotBlank()) {
                item(key = "msg") {
                    Text(
                        vm.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                    )
                }
            }

            items(vm.tracks, key = { it.key }) { t ->
                SongRow(
                    coverSeed = t.album.ifBlank { t.title },
                    coverTrack = t.toCoverTrack(),
                    title = t.title,
                    subtitle = t.artist.ifBlank { "未知歌手" },
                    playing = vm.player.currentTrack?.key == "cloud:${t.key}",
                    showDivider = false,
                    onClick = { vm.playTrack(t) }
                )
            }
        }
    }
}

/** 云端歌曲借一张本地曲库身份去查封面（按歌名歌手检索）。 */
private fun CloudTrack.toCoverTrack(): Track = Track(
    key = "cloud-cover:$key",
    id = key,
    url = "",
    fileName = "",
    title = title,
    artist = artist,
    album = album,
    folder = "",
    size = 0L,
    mime = "audio/*",
    durationSec = durationSec.toDouble(),
    provider = "cloud",
)
