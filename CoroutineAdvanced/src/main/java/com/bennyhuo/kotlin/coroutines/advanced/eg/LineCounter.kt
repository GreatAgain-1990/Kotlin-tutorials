package com.bennyhuo.kotlin.coroutines.advanced.eg

import com.bennyhuo.kotlin.coroutines.advanced.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

val KotlinFileFilter = { file: File -> file.isDirectory || file.name.endsWith(".kt") }

/**
 * 数据类
 */
data class FileLines(val file: File, val lines: Int) {
    override fun toString(): String {
        return "${file.name}: $lines"
    }
}

suspend fun main() {
    val result = lineCounter(File("."))
    log(result)
}

/**
 * 使用suspend的挂起函数
 * 传入一个文件，返回一个hashMap
 */
suspend fun lineCounter(root: File): HashMap<File, Int> {
    return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1)
        .asCoroutineDispatcher()
        //use自动关闭流
        .use {
            withContext(it) {
                val fileChannel = walkFile(root)

                val fileLinesChannels = List(5) {//开启5个协程
                    fileLineCounter(fileChannel)
                }

                resultAggregator(fileLinesChannels)
            }
        }
}


suspend fun SendChannel<File>.fileWalker(file: File) {
    if (file.isDirectory) {
        file.listFiles()?.filter(KotlinFileFilter)?.forEach { fileWalker(it) }
    } else {
        send(file)
    }
}

/**
 * Scope的扩展方法，创建
 */
fun CoroutineScope.walkFile(root: File): ReceiveChannel<File> {
    return produce(capacity = Channel.BUFFERED) {
        fileWalker(root)
    }
}

fun CoroutineScope.fileLineCounter(input: ReceiveChannel<File>): ReceiveChannel<FileLines> {
    return produce(capacity = Channel.BUFFERED) {
        for (file in input) {
            file.useLines {
                send(FileLines(file, it.count()))
            }
        }
    }
}

suspend fun CoroutineScope.resultAggregator(channels: List<ReceiveChannel<FileLines>>): HashMap<File, Int> {
    val map = HashMap<File, Int>()
    channels.aggregate { filteredChannels ->
        //知识点
        select<FileLines?> {
            filteredChannels.forEach {
                it.onReceiveOrNull {
                    log("received: $it")
                    it
                }
            }
        }?.let {
            map[it.file] = it.lines
        }
    }
    return map
}

tailrec suspend fun List<ReceiveChannel<FileLines>>.aggregate(block: suspend (List<ReceiveChannel<FileLines>>) -> Unit) {
    block(this)
    filter {
        !it.isClosedForReceive//过滤掉已经关闭的
    }.takeIf { it.isNotEmpty() }?.aggregate(block)
}