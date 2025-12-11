package com.babenko.rescueservice.core

/**
 * Событие для ввода текста в текущее сфокусированное поле
 * через AccessibilityService.
 */
data class InputTextEvent(
    val text: String
) : AppEvent()   // ← добавили скобки
