// - Semicolons are optional in Kotlin.
// - Kotlin support _import bindings_.

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.fibers.futures.AsyncCompletionStage
import co.paralleluniverse.kotlin.Receive
import co.paralleluniverse.kotlin.fiber
import co.paralleluniverse.kotlin.select
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

// - "import" can be used for all sorts of inputs (package, class, object etc.).
import java.util.concurrent.TimeUnit.*

import kotlin.concurrent.thread

/**
 * Stock
 */
// - Kotlin _primary constructor_ is part of the class header and can specify `val` or `var` to
//   create corresponding mutable or immutable _properties_ resp.: no boilerplate assignments are
//   necessary.
class Stock(val name: String) {

    // - Kotlin supports singletons directly as _literal objects_ and _companion objects_ are a special case
    //   that can be referred through the enclosing class' identifier.
    // - Singleton objects are full-blown objects: they can inherit, implement and define properties and methods.
    companion object {

        // - Kotlin is strongly types but has extensive type inference,
        //   although explicit typing for signatures is required.
        private val HISTORY_WINDOW_SIZE = 10

        // - There's no "new" keyword in Kotlin, constructors are called simply through the class name.
        val default = Stock("whatever")

        /**
         * Finds a stock by name. The current example implementation just constructs and caches Stock objects.
         */
        // - Companion object methods and properties can be used as an equivalent of Java's _static_.
        // - Methods can be defined by an expression, in this case the return type is optional.
        // - Method parameters can also have default values.
        private fun find(name: String = "goog") =
                // - Kotlin supports higher-order functions.
                // - If the last parameter is a function, a DSL-like block-based syntax can be used.
                // - A _lambda_, or _function literal_, has the form `{ (param1[:Type1], ...) -> BODY }` and
                //   if there is only a single parameter the `{ BODY }` form can be used and the parameter
                //   can be referred to as `it`.
                cache.getOrPut(name) {
                    Stock(name)
                }
        private val cache = ConcurrentHashMap<String, Stock>()

        // - We'll simulate a slow system with threads that will wait up to 3 seconds to answer our inquiry
        private val MAX_WAIT_MILLIS: Long = 3000
        private fun <I, O> slowAsyncVal(s: I, f: (I) -> O): CompletableFuture<O> {
            val ret = CompletableFuture<O>()
            thread {
                Strand.sleep(ThreadLocalRandom.current().nextLong(0, MAX_WAIT_MILLIS))
                ret.complete(f(s))
            }
            return ret
        }
        fun findAsync(s: String) = slowAsyncVal(s) { Stock.find(it) ?: Stock.default }
        fun avgAsync(s: Stock) = slowAsyncVal(s) {
            it.avg()
        }
        fun currentAsync(s: Stock) = slowAsyncVal(s) { it.current() }
        fun adviceAsync(s: Stock) = slowAsyncVal(s) { it.advice() }
    }

    /**
     * Sliding window for historical data
     */
    private val hist = EvictingQueue.create<Value>(HISTORY_WINDOW_SIZE)

    // - Kotlin classes can have _initialization blocks_.
    init {
        // - Kotlin supports _dynamic ranges_.
        for(i in 0..HISTORY_WINDOW_SIZE)
            hist.offer(Value(newVal().num))
    }

    /**
     * Generates a new stock value
     */
    // - Kotlin has _`public`_, _`private`_, _`protected`_ and _`internal`_ (default) visibility.
    private fun newVal(): Value =
            Value (
                    // - Many Kotlin constructs, like "if", can be used both as statements and as expressions.
                    if (hist.isEmpty())
                        ThreadLocalRandom.current().nextDouble()
                    else {
                        // - Locals can be mutable or immutable too.
                        val DEVIATION = 0.25
                        val last = hist.last().num
                        last + (last * ThreadLocalRandom.current().nextDouble(-DEVIATION, +DEVIATION))
                    }
            )

    /**
     * Calculates the current stock value
     */
    private fun current(): Value {
        hist.offer(newVal())

        // - `return` is mandatory if the method is defined as a block
        return hist.last()
    }

    /**
     * Calculates the current stock advice
     */
    private fun advice(): Advice =
            Advice.values()[ThreadLocalRandom.current().nextInt(0, Advice.values().size)]

    /**
     * Returns the historical average
     */
    fun avg(): Double {
        // Mutable local
        var sum = 0.0
        for (i in hist)
            sum += i.num
        return sum / hist.size
    }
}

/**
 * Stock value
 */
// - For _data classes_ Kotlin will generate "toString", "equals", "hashCode" and a nice "copy" method with
//   optional parameters to replace some of the properties during the copy.
data class Value(val num: Double)

/**
 * Stock Advice
 */
// - Kotlin _enum literals_ are _literal object instances_ of the _enum class_.
enum class Advice {
    BUY, SELL, KEEP
}

// - Kotlin _extension functions_ allow adding functionality to classes whose source are not under our control (libs).
// - They are _statically dispatched_.
// - We'll convert slow, async `CompletableFuture`-based operation into _fiber-blocking_ ones.
@Suspendable fun <T> CompletionStage<T>.fiberBlocking() = AsyncCompletionStage.get(this)

fun main(args: Array<String>) {

    print("Insert the stock name: ")
    val sName = readLine() ?: "goog"

    val avgRetCh = Channels.newChannel<Double>(0)
    val currentRetCh = Channels.newChannel<Value>(0)
    val adviceRetCh = Channels.newChannel<Advice>(0)

    fiber {
        val s = Stock.findAsync(sName).fiberBlocking()

        // - We'll send once again each information on its return channel after obtaining it.
        fiber {
            println("Fiber getting the AVG started...")
            avgRetCh.send(Stock.avgAsync(s).fiberBlocking())
            println("...Fiber got the AVG.")
        }
        fiber {
            println("Fiber getting the VALUE started...")
            currentRetCh.send(Stock.currentAsync(s).fiberBlocking())
            println("...Fiber got the VALUE.")
        }
        fiber {
            println("Fiber getting the ADVICE started...")
            adviceRetCh.send(Stock.adviceAsync(s).fiberBlocking())
            println("...Fiber got the ADVICE.")
        }
    }

    // - We want to be extra-cool and fetch results as soon as they are available on any channel by using _channel selection_.
    // - Select will unblock as soon as one of the channel operation completes (or on timeout).
    for(i in 1..3)
        select(60, SECONDS, Receive(avgRetCh), Receive(currentRetCh), Receive(adviceRetCh)) {
            // - Kotlin's _`when`_ is a simplified _pattern matching_ construct.
            // - Each branch can match a single expression on another expression, a set of expressions, a dynamic range, or a type.
            // - Without an expression argument it can also replace an `if/else if/.../else` chain.
            // - This makes `when` ideal to process the result of `receive` and `select` calls.
            when (it) {
                is Receive -> {
                    // - Kotlin will _smart-cast_ `it` to the `Receive` type here, so we can access `receivePort` without
                    //   repetitive down-casts.
                    if (it.receivePort == avgRetCh) println("The historical average is: ${it.msg}")
                    else if (it.receivePort == currentRetCh) println("The current value is: ${it.msg}")
                    else if (it.receivePort == adviceRetCh) println("The current advice is: ${it.msg}")
                }
            // - This shouldn't happen because our system will answer within 3 seconds.
            // - ...Unless it is down, that's why a timeout is always a good idea.
                else -> println("Timeout!!!")
            }
        }
}
