package com.babenko.rescueservice.llm

// Production-grade CommandParser:
// - широкий набор триггеров (RU/EN), включая коллоквиализмы и вариации,
// - гибкая нормализация (удаление пунктуации, сжатие пробелов),
// - матч по границам слов для переключения языка и общих триггеров,
// - сохранена логика payload extraction (для смены имени/запуска приложений и т.д.),
// - минимальные изменения совместимости: startsWith все ещё используется там, где важен префикс.

enum class Command {
    REPEAT,
    OPEN_SETTINGS,
    CHANGE_NAME,
    CHANGE_LANGUAGE,
    CHANGE_SPEECH_RATE_FASTER,
    CHANGE_SPEECH_RATE_SLOWER,
    // Intent commands for the settings dialog
    INTENT_CHANGE_NAME,
    INTENT_CHANGE_LANGUAGE,
    INTENT_CHANGE_SPEED,
    // Scrolling commands
    SCROLL_DOWN,
    SCROLL_UP,
    // App launch command (if present in variants)
    OPEN_APP,
    UNKNOWN
}

data class ParsedCommand(val command: Command, val payload: String? = null)

object CommandParser {

    // --- Repeat / Clarify ---
    private val repeatTriggers = setOf(
        // Russian (many colloquial forms to improve UX for seniors)
        "повтори", "скажи ещё раз", "скажи еще раз", "ещё раз", "еще раз", "дубль",
        "я не услышал", "я не услышала", "плохо слышно", "не понял", "не поняла",
        "пропустил", "пропустила", "мимо ушей", "чё ты сказал", "чего сказал", "как ты сказал",
        "как", "чё", "а", "э", "гм", "чего", "что",
        "повтори погромче", "повтори помедленнее", "повтори по легче", "повтори по медленнее",
        "повтори почётче", "повтори по четче", "давай ещё раз", "давай еще раз", "можно ещё раз", "можно еще раз",
        "повтори последнее", "повтори сначала",

        // English
        "repeat", "say it again", "say that again", "once more", "once again",
        "could you repeat", "can you repeat", "tell me again",
        "didnt hear you", "didnt catch that", "missed that", "what did you say",
        "pardon", "come again", "what was that", "huh", "sorry", "eh",
        "say it louder", "repeat slower", "more clearly", "one more time", "last bit", "from the beginning"
    )

    // --- Settings / menu ---
    private val settingsTriggers = setOf(
        "настройки", "settings", "сетап", "setup", "открой настройки", "open settings",
        "app settings", "configuration", "options", "menu", "open menu", "preferences",
        "change settings", "config", "давай в настройки", "покажи настройки", "хочу поменять",
        "помоги настроить", "меню настроек", "изменить параметры", "хочу изменить", "давай поправим",
        "зайди в настройки", "открыть настройки", "параметры", "конфигурация", "установки", "опции", "меню"
    )

    // --- Name changes / payload ---
    private val nameChangeTriggers = setOf(
        // Русский
        "сменить имя", "запомни имя", "измени имя", "зови меня", "меня зовут",
        // English
        "change name", "set name", "update name", "my name"
    )

    // --- Language Change Triggers (robust) ---
    // Covering many variations: direct, polite, short forms, hyphenated forms ("по-русски")
    private val toRussianTriggers = setOf(
        // English commands for switching to Russian
        "change language russian", "switch language russian", "set language russian", "speak russian",
        "speak russian please", "please speak russian", "switch to russian", "make russian",
        // Russian commands for switching to Russian (various colloquial forms)
        "сменить язык русский", "измени язык русский", "поставь русский", "говори русском",
        "по русски", "по-русски", "говори по русски", "говори по-русски", "пожалуйста говори по русски",
        "пожалуйста говори по-русски", "пожалуйста по русски", "пожалуйста по-русски"
    )

    private val toEnglishTriggers = setOf(
        // English commands for switching to English
        "change language english", "switch language english", "set language english", "speak english",
        "speak english please", "please speak english", "switch to english", "make english",
        // Russian commands for switching to English
        "сменить язык английский", "измени язык английский", "поставь английский", "говори английском",
        "по английски", "по-английски", "говори по английски", "говори по-английски",
        "пожалуйста говори по английски", "пожалуйста говори по-английски"
    )

    // --- Speech rate ---
    private val speechRateFasterTriggers = setOf(
        "ускорь речь", "скорость речи", "говори быстрее", "разговаривай быстрее",
        "speed up speech", "faster speech", "talk faster", "increase speech rate"
    )

    private val speechRateSlowerTriggers = setOf(
        "замедли речь", "говори медленнее", "говори помедленнее",
        "speed down speech", "slow speech", "talk slower", "decrease speech rate"
    )

    // --- Scroll ---
    private val scrollDownTriggers = setOf(
        "прокрути вниз", "листай вниз", "вниз", "ниже",
        "scroll down", "swipe down", "down", "page down"
    )

    private val scrollUpTriggers = setOf(
        "прокрути вверх", "листай вверх", "вверх", "выше",
        "scroll up", "swipe up", "up", "page up"
    )

    // --- Intent triggers for settings dialogs ---
    private val intentNameTriggers = setOf("имя", "name", "change name", "измени имя")
    private val intentLanguageTriggers = setOf("язык", "language")
    private val intentSpeedTriggers = setOf("скорость", "скорость речи", "speed", "speech speed")

    // --- App launch triggers (kept for compatibility / optional use) ---
    private val openAppTriggers = setOf(
        // Russian
        "открой", "запусти", "открыть", "старт", "включи",
        // English
        "open", "launch", "start", "run",
        // More colloquial
        "открой приложение", "open app", "запусти приложение"
    )

    /**
     * Parse the recognized text into a command. The input may contain several recognition
     * hypotheses separated by " ||| " (VoiceSessionService does this).
     */
    fun parse(text: String): ParsedCommand {
        // split alternatives returned by SR engine
        val variants = text.split(" ||| ")

        for (variant in variants) {
            val lowercasedText = variant.lowercase()
            val normalizedText = normalize(lowercasedText)

            // 1) High-priority: language change (use word-boundary matching)
            if (toRussianTriggers.any { containsWord(normalizedText, it) }) {
                return ParsedCommand(Command.CHANGE_LANGUAGE, "ru-RU")
            }
            if (toEnglishTriggers.any { containsWord(normalizedText, it) }) {
                return ParsedCommand(Command.CHANGE_LANGUAGE, "en-US")
            }

            // 2) Payload commands (name change)
            extractPayload(normalizedText, nameChangeTriggers)?.let { payload ->
                return ParsedCommand(Command.CHANGE_NAME, payload)
            }

            // 2.5) App launch (if used) - payload extraction
            extractPayload(normalizedText, openAppTriggers)?.let { payload ->
                return ParsedCommand(Command.OPEN_APP, payload)
            }

            // 3) Speech rate
            if (containsTrigger(normalizedText, speechRateFasterTriggers)) return ParsedCommand(Command.CHANGE_SPEECH_RATE_FASTER)
            if (containsTrigger(normalizedText, speechRateSlowerTriggers)) return ParsedCommand(Command.CHANGE_SPEECH_RATE_SLOWER)

            // 3.5) Scroll
            if (containsTrigger(normalizedText, scrollDownTriggers)) return ParsedCommand(Command.SCROLL_DOWN)
            if (containsTrigger(normalizedText, scrollUpTriggers)) return ParsedCommand(Command.SCROLL_UP)

            // 4) Intents for settings dialog
            if (containsTrigger(normalizedText, intentNameTriggers)) return ParsedCommand(Command.INTENT_CHANGE_NAME)
            if (containsTrigger(normalizedText, intentLanguageTriggers)) return ParsedCommand(Command.INTENT_CHANGE_LANGUAGE)
            if (containsTrigger(normalizedText, intentSpeedTriggers)) return ParsedCommand(Command.INTENT_CHANGE_SPEED)

            // 5) Repeat
            if (containsTrigger(normalizedText, repeatTriggers)) return ParsedCommand(Command.REPEAT)

            // 6) General settings
            if (containsTrigger(normalizedText, settingsTriggers)) return ParsedCommand(Command.OPEN_SETTINGS)
        }

        return ParsedCommand(Command.UNKNOWN)
    }

    // Normalization: remove punctuation, collapse multiple spaces, strip connectors.
    private fun normalize(text: String): String {
        // Replace some common connectors with spaces to keep tokens separate
        var res = text.replace(" на ", " ").replace(" to ", " ")
        // Remove any character that is not a letter, number or whitespace (removes punctuation)
        res = res.replace(Regex("[^\\p{L}\\p{N}\\s]+"), "")
        // Collapse multiple whitespace into a single space
        res = res.replace(Regex("\\s+"), " ")
        return res.trim()
    }

    // Word-boundary matcher: returns true if 'term' appears as a token or phrase at word boundaries in 'text'.
    private fun containsWord(text: String, term: String): Boolean {
        if (text == term) return true
        // term may contain spaces (multi-word). Use regex with word boundaries via whitespace.
        val pattern = Regex("(^|\\s)${Regex.escape(term)}(\\s|\$)")
        return pattern.containsMatchIn(text)
    }

    // For general triggers we allow startsWith or exact equivalence OR word-boundary containment.
    private fun containsTrigger(text: String, triggers: Set<String>): Boolean {
        return triggers.any { trigger ->
            text == trigger ||
                    text.startsWith(trigger) ||
                    containsWord(text, trigger)
        }
    }

    // Extract payload for prefix-like triggers: if text starts with trigger, return remainder.
    private fun extractPayload(text: String, triggers: Set<String>): String? {
        for (trigger in triggers) {
            if (text.startsWith(trigger)) {
                val payload = text.removePrefix(trigger).trim()
                if (payload.isNotEmpty()) return payload
            }
        }
        return null
    }
}