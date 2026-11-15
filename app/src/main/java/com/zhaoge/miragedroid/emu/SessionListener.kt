package com.zhaoge.miragedroid.emu

import com.zhaoge.miragedroid.net.RfbFrame

/** 会话状态 / 画面 / 控制台输出的回调（都在主线程触发）。 */
interface SessionListener {
    fun onSessionStateChanged(session: SessionManager)

    fun onSessionFrame(session: SessionManager, frame: RfbFrame)

    fun onSessionConsole(session: SessionManager, text: String)
}
