package com.example.heospresence

import java.util.ArrayDeque

/** Décalage par place, relatif aux niveaux d'origine : on atténue l'arrière le plus proche. */
enum class Seat(val label: String, val leftDb: Int, val rightDb: Int) {
    LEFT("Gauche", -5, 7),
    CENTER("Centre", 0, 0),
    RIGHT("Droite", 7, -5),
}

private const val WINDOW = 7 // environ 2 s de mesures
private const val DWELL_MS = 3_000L
private const val ABSENT_MS = 30_000L

// Milieux entre les places calibrées : gauche -0,26, centre +0,08, droite +0,45
private const val LEFT_CENTER = -0.09f
private const val CENTER_RIGHT = 0.265f
private const val HYSTERESIS = 0.03f

/** Transforme les positions brutes en place stable : un geste ou une image ratée ne change rien. */
class SeatTracker {
    var seat = Seat.CENTER
        private set

    private val samples = ArrayDeque<Float>()
    private var candidate: Seat? = null
    private var candidateSince = 0L
    private var lastSeen = 0L

    fun update(x: Float?, now: Long): Seat {
        if (x == null) {
            if (now - lastSeen >= ABSENT_MS) reset()
            return seat
        }
        lastSeen = now
        samples.addLast(x)
        if (samples.size > WINDOW) samples.removeFirst()
        if (samples.size == WINDOW) settle(classify(samples.sorted()[WINDOW / 2]), now)
        return seat
    }

    /** Sortir de la place actuelle demande de franchir la limite d'une marge de plus. */
    private fun classify(x: Float): Seat {
        val leftEdge = LEFT_CENTER + if (seat == Seat.LEFT) HYSTERESIS else -HYSTERESIS
        val rightEdge = CENTER_RIGHT + if (seat == Seat.RIGHT) -HYSTERESIS else HYSTERESIS
        return when {
            x < leftEdge -> Seat.LEFT
            x > rightEdge -> Seat.RIGHT
            else -> Seat.CENTER
        }
    }

    private fun settle(next: Seat, now: Long) {
        if (next == seat) {
            candidate = null
        } else if (next != candidate) {
            candidate = next
            candidateSince = now
        } else if (now - candidateSince >= DWELL_MS) {
            seat = next
            candidate = null
        }
    }

    private fun reset() {
        seat = Seat.CENTER
        samples.clear()
        candidate = null
    }
}
