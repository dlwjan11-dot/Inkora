package com.abdulmedia.inkora

/**
 * Speed numbers for the test switches (Settings › Advanced › Show speed numbers): how many frames
 * the screen draws, how long a frame and a pen event take, how far behind the pen the app is, and
 * what the GPU engine does. Counted only while it is on; read and reset twice a second.
 */
object Perf {
    @Volatile var on = false
    @Volatile var draws = 0
    @Volatile var drawNs = 0L
    @Volatile var moves = 0
    @Volatile var moveNs = 0L
    @Volatile var lagNs = 0L
    @Volatile var dabs = 0
    @Volatile var glFrames = 0
    @Volatile var glNs = 0L
    @Volatile var glImages = 0
    @Volatile var glPending = 0

    /** one line of numbers for [seconds] of counting, then everything starts again from zero */
    fun take(seconds: Float): String {
        fun ms(ns: Long, n: Int) = if (n == 0) "-" else String.format(java.util.Locale.US, "%.1f", ns / 1e6 / n)
        val s = String.format(java.util.Locale.US,
            "screen %.0f fps · draw %s ms · pen %.0f/s %s ms · behind %s ms · dabs %.0f/s · GPU %.0f fps %s ms, shown %.0f/s, waiting %d",
            draws / seconds, ms(drawNs, draws), moves / seconds, ms(moveNs, moves), ms(lagNs, moves), dabs / seconds,
            glFrames / seconds, ms(glNs, glFrames), glImages / seconds, glPending)
        draws = 0; drawNs = 0; moves = 0; moveNs = 0; lagNs = 0; dabs = 0; glFrames = 0; glNs = 0; glImages = 0; glPending = 0
        return s
    }
}
