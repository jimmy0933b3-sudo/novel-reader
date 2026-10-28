package com.novelreader.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.ui.*
import com.novelreader.app.ui.theme.NovelReaderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NovelApplication
        val repository = app.repository

        setContent {
            NovelReaderTheme {
                val navController = rememberNavController()
                val novels by repository.getAllNovels().collectAsState(initial = emptyList())

                var showAddDialog by remember { mutableStateOf(false) }
                var inputUrl by remember { mutableStateOf("") }
                var isImporting by remember { mutableStateOf(false) }

                NavHost(navController = navController, startDestination = "bookshelf") {
                    // 1. 書架主頁
                    composable("bookshelf") {
                        BookshelfScreen(
                            novels = novels,
                            onNovelClick = { novelId ->
                                navController.navigate("novel_detail/$novelId")
                            },
                            onReadNovel = { novelId, chapterIndex ->
                                navController.navigate("reader/$novelId/$chapterIndex")
                            },
                            onAddClick = { showAddDialog = true },
                            onOpenWebClick = { navController.navigate("web_browser") },
                            onDownloadAll = { novelId ->
                                repository.startDownloadAllChapters(novelId)
                                Toast.makeText(this@MainActivity, "已在背景開始整部下載！", Toast.LENGTH_SHORT).show()
                            },
                            onDeleteNovel = { novel ->
                                lifecycleScope.launch {
                                    repository.deleteNovel(novel)
                                }
                            }
                        )
                    }

                    // 2. 書籍詳情與章節目錄
                    composable(
                        "novel_detail/{novelId}",
                        arguments = listOf(navArgument("novelId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val novelId = backStackEntry.arguments?.getString("novelId") ?: return@composable
                        val novel by repository.getNovelFlow(novelId).collectAsState(initial = null)
                        val chapters by repository.getChaptersFlow(novelId).collectAsState(initial = emptyList())

                        NovelDetailScreen(
                            novel = novel,
                            chapters = chapters,
                            onBack = { navController.popBackStack() },
                            onChapterClick = { index ->
                                navController.navigate("reader/$novelId/$index")
                            },
                            onDownloadAll = {
                                repository.startDownloadAllChapters(novelId)
                                Toast.makeText(this@MainActivity, "已在背景開始整部下載！", Toast.LENGTH_SHORT).show()
                            },
                            onRedownloadAll = {
                                repository.startDownloadAllChapters(novelId, forceRefresh = true)
                                Toast.makeText(this@MainActivity, "開始重新抓取全書；失敗時保留原有內容", Toast.LENGTH_LONG).show()
                            },
                            onSyncLatestChapters = { callback ->
                                lifecycleScope.launch {
                                    Toast.makeText(this@MainActivity, "正在檢查並同步最新章節...", Toast.LENGTH_SHORT).show()
                                    try {
                                        val result = repository.syncLatestChapters(novelId)
                                        if (result.isSuccess) {
                                            val newCount = result.getOrNull() ?: 0
                                            if (newCount > 0) {
                                                Toast.makeText(this@MainActivity, "同步成功！新增了 $newCount 個最新章節", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "目前已是最新章節，無新內容", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(this@MainActivity, "同步失敗：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        callback()
                                    }
                                }
                            },
                            onDownloadSingleChapter = { chapterIndex, callback ->
                                lifecycleScope.launch {
                                    try {
                                        val result = repository.downloadSingleChapter(novelId, chapterIndex)
                                        if (result.isSuccess) {
                                            Toast.makeText(this@MainActivity, "該章節下載完成！", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "下載失敗：${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } finally {
                                        callback()
                                    }
                                }
                            }
                        )
                    }

                    // 3. 離線閱讀器
                    composable(
                        "reader/{novelId}/{chapterIndex}",
                        arguments = listOf(
                            navArgument("novelId") { type = NavType.StringType },
                            navArgument("chapterIndex") { type = NavType.IntType }
                        )
                    ) { backStackEntry ->
                        val novelId = backStackEntry.arguments?.getString("novelId") ?: return@composable
                        val requestedChapterIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: 0

                        var resolvedChapterIndex by remember { mutableStateOf<Int?>(null) }

                        LaunchedEffect(novelId, requestedChapterIndex) {
                            val novel = repository.getNovelById(novelId)
                            if (novel != null) {
                                resolvedChapterIndex = if (requestedChapterIndex == -1) novel.lastReadChapterIndex else requestedChapterIndex
                            } else {
                                resolvedChapterIndex = requestedChapterIndex
                            }
                        }

                        if (resolvedChapterIndex != null) {
                            ReaderScreen(
                                novelId = novelId,
                                initialChapterIndex = resolvedChapterIndex!!,
                                repository = repository,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // 4. 內建瀏覽器
                    composable("web_browser") {
                        WebBrowserScreen(
                            onBack = { navController.popBackStack() },
                            onImportHtml = { url, html ->
                                lifecycleScope.launch {
                                    Toast.makeText(this@MainActivity, "正在解析並加入書架...", Toast.LENGTH_SHORT).show()
                                    val result = repository.importNovelFromUrl(url, html)
                                    if (result.isSuccess) {
                                        Toast.makeText(this@MainActivity, "成功加入書架！", Toast.LENGTH_SHORT).show()
                                        navController.popBackStack()
                                    } else {
                                        Toast.makeText(this@MainActivity, "匯入失敗：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }

                // 貼網址匯入對話框
                if (showAddDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!isImporting) showAddDialog = false },
                        title = { Text("貼上小說網址") },
                        text = {
                            Column {
                                Text("支援 czbooks 或一般小說網站首頁 / 目錄頁網址：", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = inputUrl,
                                    onValueChange = { inputUrl = it },
                                    label = { Text("小說網址 (https://...)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (isImporting) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = inputUrl.isNotBlank() && !isImporting,
                                onClick = {
                                    isImporting = true
                                    lifecycleScope.launch {
                                        val result = repository.importNovelFromUrl(inputUrl.trim())
                                        isImporting = false
                                        if (result.isSuccess) {
                                            Toast.makeText(this@MainActivity, "成功加入書架！", Toast.LENGTH_SHORT).show()
                                            showAddDialog = false
                                            inputUrl = ""
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "直接連線被擋或解析失敗。建議使用右上角「地球」圖示內建瀏覽器打開並加入書架！",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            ) {
                                Text("匯入")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !isImporting,
                                onClick = { showAddDialog = false }
                            ) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }
}
