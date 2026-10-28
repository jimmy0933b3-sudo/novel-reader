package com.novelreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Sort
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.ui.components.NovelCoverPlaceholder
import kotlinx.coroutines.launch

import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novel: NovelEntity?,
    chapters: List<ChapterEntity>,
    onBack: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onDownloadAll: () -> Unit,
    onRedownloadAll: () -> Unit,
    onSyncLatestChapters: (() -> Unit) -> Unit,
    onDownloadSingleChapter: (Int, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("novel_prefs", android.content.Context.MODE_PRIVATE) }
    var isSyncing by remember { mutableStateOf(false) }
    var showRedownloadConfirmation by remember { mutableStateOf(false) }
    var downloadingChapterIndices by remember { mutableStateOf(setOf<Int>()) }
    var isReversed by remember { mutableStateOf(false) }

    if (showRedownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showRedownloadConfirmation = false },
            title = { Text("重新下載全部章節？") },
            text = { Text("會逐章重新抓取網頁正文；某章抓取失敗時，原本已下載的文字不會被清除。") },
            confirmButton = {
                TextButton(onClick = {
                    showRedownloadConfirmation = false
                    onRedownloadAll()
                }) { Text("開始重新下載") }
            },
            dismissButton = {
                TextButton(onClick = { showRedownloadConfirmation = false }) { Text("取消") }
            }
        )
    }

    val workManager = androidx.work.WorkManager.getInstance(context)
    val novelId = novel?.id ?: ""
    val workInfoState = workManager.getWorkInfosByTagFlow("DOWNLOAD_$novelId").collectAsState(initial = emptyList())

    // Pick the most relevant work info (RUNNING/ENQUEUED/BLOCKED takes precedence over finished states)
    // UUID (it.id) comparison is unreliable for time-ordering.
    // However, since we use `enqueueUniqueWork` with `ExistingWorkPolicy.KEEP` or `REPLACE`,
    // the list should generally contain the latest work for this unique name.
    // We prioritize unfinished work.
    val workInfo = remember(workInfoState.value) {
        val list = workInfoState.value
        list.firstOrNull { !it.state.isFinished } ?: list.lastOrNull()
    }

    val workerState = workInfo?.state
    val isWaiting = workerState == androidx.work.WorkInfo.State.ENQUEUED || workerState == androidx.work.WorkInfo.State.BLOCKED
    val isFailed = workerState == androidx.work.WorkInfo.State.FAILED
    val isWorkerDownloading = workerState == androidx.work.WorkInfo.State.RUNNING

    // Read progress if running, outputData if finished
    val workerData = if (workerState?.isFinished == true) workInfo?.outputData else workInfo?.progress
    val currentWorkerChapter = workerData?.getString("CURRENT_CHAPTER")
    val workerOverallSuccess = workerData?.getInt("SUCCESS_COUNT", 0) ?: 0
    val workerOverallFailed = workerData?.getInt("FAILED_COUNT", 0) ?: 0

    LaunchedEffect(novel?.id) {
        if (novel != null) {
            isReversed = prefs.getBoolean("is_reversed_${novel.id}", false)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val sortedChapters = remember(chapters, isReversed) {
        if (isReversed) chapters.reversed() else chapters
    }

    var isFirstLoad by remember { mutableStateOf(true) }

    LaunchedEffect(novel, sortedChapters.size) {
        if (isFirstLoad && novel != null && sortedChapters.isNotEmpty()) {
            val targetIdx = sortedChapters.indexOfFirst { it.chapterIndex == novel.lastReadChapterIndex }
            if (targetIdx >= 0) {
                listState.scrollToItem(targetIdx + 1) // +1 for the header item
            }
            isFirstLoad = false
        }
    }

    // When reversing the list, attempt to keep the first visible chapter in view
    LaunchedEffect(isReversed) {
        if (!isFirstLoad && sortedChapters.isNotEmpty()) {
            val visibleIndex = listState.firstVisibleItemIndex
            if (visibleIndex > 0) {
                // Approximate position in the reversed list
                val newIndex = (sortedChapters.size - visibleIndex + 1).coerceIn(1, sortedChapters.size)
                listState.scrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "作品詳情",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!isSyncing) {
                                isSyncing = true
                                onSyncLatestChapters {
                                    isSyncing = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "同步最新章節")
                    }
                }
            )
        }
    ) { padding ->
        if (novel == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(bottom = 80.dp) // Provide some padding at bottom
            ) {
                // 小說簡介區域 (Header)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Cover
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .aspectRatio(0.7f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (novel.coverUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = novel.coverUrl,
                                        contentDescription = "封面",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    NovelCoverPlaceholder()
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))

                            // Info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = novel.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = novel.author.ifBlank { "未知作者" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val readBtnText = if (!novel.lastReadChapterTitle.isNullOrBlank()) {
                                    "繼續閱讀"
                                } else {
                                    "開始閱讀"
                                }

                                Button(
                                    onClick = { onChapterClick(-1) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(readBtnText)
                                }
                            }
                        }

                        if (novel.intro.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "簡介",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                var showDiagnosticDialog by remember { mutableStateOf(false) }
                                TextButton(onClick = { showDiagnosticDialog = true }) {
                                    Text("下載診斷")
                                }

                                if (showDiagnosticDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showDiagnosticDialog = false },
                                        title = { Text("下載診斷紀錄") },
                                        text = {
                                            // Put it in a scrollable column in case it's long
                                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                                Text(com.novelreader.app.utils.DiagnosticLogger.readLogs(context))
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(onClick = { showDiagnosticDialog = false }) { Text("關閉") }
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = novel.intro,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "目錄 (${chapters.size} 章)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    isReversed = !isReversed
                                    novel.id.let { prefs.edit().putBoolean("is_reversed_$it", isReversed).apply() }
                                }) {
                                    Icon(androidx.compose.material.icons.Icons.Default.Sort, contentDescription = "排序", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isReversed) "倒序" else "正序")
                                }
                                TextButton(onClick = onDownloadAll) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("下載全部")
                                }
                            }
                        }
                        TextButton(onClick = { showRedownloadConfirmation = true }) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重新抓取全書（修復錯誤章節）")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 章節列表
                items(sortedChapters, key = { it.chapterIndex }) { chapter ->
                    val isDownloading = downloadingChapterIndices.contains(chapter.chapterIndex)
                    val isCurrentChapter = chapter.chapterIndex == novel.lastReadChapterIndex

                    // Check if this specific chapter is being downloaded by the worker
                    val isThisChapterWorkerDownloading = isWorkerDownloading && currentWorkerChapter == chapter.title

                    // If the worker has failed chapters and is no longer running, or is waiting
                    val isWorkerBatchFailed = isFailed || workerOverallFailed > 0

                    val isCurrentlyDownloading = isDownloading || isThisChapterWorkerDownloading

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(chapter.chapterIndex) }
                            .background(if (isCurrentChapter) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrentChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (chapter.isDownloaded) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "已離線",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (isCurrentlyDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else if (isWaiting) {
                            Text("等待系統排程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (isWorkerBatchFailed && !isWorkerDownloading) {
                            IconButton(
                                onClick = {
                                    downloadingChapterIndices = downloadingChapterIndices + chapter.chapterIndex
                                    onDownloadSingleChapter(chapter.chapterIndex) {
                                        downloadingChapterIndices = downloadingChapterIndices - chapter.chapterIndex
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "失敗可重試",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    downloadingChapterIndices = downloadingChapterIndices + chapter.chapterIndex
                                    onDownloadSingleChapter(chapter.chapterIndex) {
                                        downloadingChapterIndices = downloadingChapterIndices - chapter.chapterIndex
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "下載",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}
