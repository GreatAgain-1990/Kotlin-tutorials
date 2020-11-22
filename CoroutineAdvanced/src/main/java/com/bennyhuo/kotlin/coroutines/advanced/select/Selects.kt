package com.bennyhuo.kotlin.coroutines.advanced.select

import com.bennyhuo.kotlin.coroutines.advanced.common.User
import com.bennyhuo.kotlin.coroutines.advanced.common.gitHubServiceApi
import com.bennyhuo.kotlin.coroutines.advanced.utils.log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.File

val localDir = File("localCache").also { it.mkdirs() }

val gson = Gson()

/**
 * 从网络拿数据
 */
fun CoroutineScope.getUserFromApi(login: String) = async(Dispatchers.IO){
    gitHubServiceApi.getUserSuspend(login)
}

/**
 * 从本地拿数据
 */
fun CoroutineScope.getUserFromLocal(login:String) = async(Dispatchers.IO){
    File(localDir, login).takeIf { it.exists() }?.readText()?.let { gson.fromJson(it, User::class.java) }
}

/**
 * 缓存User数据，到本地
 */
fun cacheUser(login: String, user: User){
    File(localDir, login).writeText(gson.toJson(user))
}

data class Response<T>(val value: T, val isLocal: Boolean)

suspend fun main() {
    val login = "bennyhuo"
    GlobalScope.launch {
        val localDeferred = getUserFromLocal(login)
        val remoteDeferred = getUserFromApi(login)

        val userResponse = select<Response<User?>> {
            localDeferred.onAwait { Response(it, true) }
            remoteDeferred.onAwait { Response(it, false) }
        }

        userResponse.value?.let { log(it) }
        userResponse.isLocal.takeIf { it }?.let {
            val userFromApi = remoteDeferred.await()
            cacheUser(login, userFromApi)
            log(userFromApi)
        }
    }.join()
}