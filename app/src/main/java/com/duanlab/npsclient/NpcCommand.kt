package com.duanlab.npsclient

/** Parses arguments only; no shell expansion or command execution is performed. */
internal object NpcCommand {
    fun parse(command: String): List<String> {
        val args = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        for (char in command) {
            when {
                escaped -> { token.append(char); escaped = false }
                char == '\\' && quote != '\'' -> { escaped = true; started = true }
                quote != null -> if (char == quote) quote = null else token.append(char)
                char == '\'' || char == '"' -> { quote = char; started = true }
                char.isWhitespace() -> if (started) {
                    args.add(token.toString())
                    token.clear()
                    started = false
                }
                else -> { token.append(char); started = true }
            }
        }
        require(!escaped && quote == null) { "Unclosed quote or trailing escape" }
        if (started) args.add(token.toString())
        require(args.isNotEmpty()) { "Empty command" }
        // Accept the optional executable prefix used by copied NPC commands.
        if (args.first() == "npc" || args.first() == "./npc") args.removeAt(0)
        require(args.isNotEmpty()) { "Missing NPC arguments" }
        val end = args.indexOf("--").let { if (it < 0) args.size else it }
        for (i in 0 until end) {
            val arg = args[i]
            if (arg.startsWith("npc://") || arg.startsWith("http://") || arg.startsWith("https://")) {
                args[i] = "-launch=$arg"
            }
        }
        // A lone long token of base64 characters is an encoded launch value;
        // subcommand names are short.
        if (end == 1 && args[0].length >= 16 && !args[0].startsWith("-") &&
            Regex("[A-Za-z0-9+/_-]+={0,2}").matches(args[0])) {
            args[0] = "-launch=${args[0]}"
        }
        val defaults = listOf("debug" to "false", "log" to "stdout")
        val endOfOptions = args.indexOf("--").let { if (it < 0) args.size else it }
        val options = args.take(endOfOptions)
        val missing = defaults.filter { (name, _) ->
            options.none { it == "-$name" || it == "--$name" ||
                it.startsWith("-$name=") || it.startsWith("--$name=") }
        }.map { (name, value) -> "-$name=$value" }
        args.addAll(endOfOptions, missing)
        return args
    }
}
