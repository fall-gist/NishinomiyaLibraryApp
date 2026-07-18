package com.fallgist.nishinomiyalibrary.data.remote.licsxp

sealed class LibraryError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : LibraryError(cause = cause)

    class Auth(val memberName: String?) : LibraryError()

    class Parse(val screen: String, val detail: String) : LibraryError("画面の解析に失敗しました: $screen")

    class Maintenance : LibraryError("図書館システムはメンテナンス中です")
}
