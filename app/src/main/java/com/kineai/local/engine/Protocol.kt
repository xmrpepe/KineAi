package com.kineai.local.engine

/** Messenger protocol between the UI process and the `:engine` process. */
object Protocol {
    // UI -> engine
    const val LOAD = 1
    const val UNLOAD = 2
    const val CHAT = 3
    const val CANCEL = 4
    const val INSPECT = 5
    const val CAPABILITIES = 6
    const val STATUS = 7

    // engine -> UI
    const val LOADED = 101
    const val LOAD_FAILED = 102
    const val UNLOADED = 103
    const val TOKEN = 104
    const val CHAT_DONE = 105
    const val CHAT_FAILED = 106
    const val INSPECTED = 107
    const val CAPABILITIES_REPLY = 108
    const val MEMORY_PRESSURE = 109
    const val RESTART_NEEDED = 110
    const val STATUS_REPLY = 111

    const val K_REQ = "req"
    const val K_PATH = "path"
    const val K_CTX = "ctx"
    const val K_THREADS = "threads"
    const val K_JSON = "json"
    const val K_TEXT = "text"
    const val K_ERROR = "error"
    const val K_MAX_TOKENS = "maxTokens"
    const val K_TEMP = "temp"
    const val K_SEED = "seed"
    const val K_LOAD_MS = "loadMs"
    const val K_RSS_KB = "rssKb"
    const val K_PEAK_RSS_KB = "peakRssKb"
    const val K_LEVEL = "level"
}
