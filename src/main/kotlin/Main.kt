// - Semicolons are optional in Kotlin.
// - Kotlin support _import bindings_.
// - "import" can be used for all sorts of inputs (package, class, object etc.).

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.fibers.futures.AsyncCompletionStage
import co.paralleluniverse.kotlin.fiber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

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
        private fun find(name: String = "goog"): Stock? =
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
@Suspendable fun <T> CompletionStage<T>.fiberBlocking() =
        AsyncCompletionStage.get(this)

fun main(args: Array<String>) {

    print("Insert the stock name: ")
    val sName = readLine() ?: "goog"

    // - Suppose we have to retrieve stock information from a slow and far system (see above).
    // - Also suppose that our system will serve many concurrent stock information requests.
    // ==> We want to use fibers, of which we can have millions, rather than threads, of which we can only have few 1000s.
    // - Fibers can _yield a result_ when joined, so we don't need result channels in this case.
    // - Fibers are just like threads, _debugging included_.

    val res = fiber {
        val s = (Stock.findAsync(sName).fiberBlocking() ?: Stock.default)

        // - Let's store the references to the 3 concurrent fibers in a new _Triple_ immutable data class (from Kotlin's stdlib).
        val running = Triple (
                fiber {
                    println("Fiber getting the AVG started...")
                    val ret = Stock.avgAsync(s).fiberBlocking()
                    println("...Fiber got the AVG.")
                    ret
                },
                fiber {
                    println("Fiber getting the VALUE started...")
                    val ret = Stock.currentAsync(s).fiberBlocking()
                    println("...Fiber got the VALUE.")
                    ret
                },
                fiber {
                    println("Fiber getting the ADVICE started...")
                    val ret = Stock.adviceAsync(s).fiberBlocking()
                    println("...Fiber got the ADVICE.")
                    ret
                }
        )

        // - Let's also store the results obtained by joining the fibers in a new _Triple_ and let's return it
        //   to the main thread as the result of the lookup fiber.
        Triple(running.first.get(), running.second.get(), running.third.get())
    }.get() // - We're letting the main thread and the lookup fiber _inter-operate_ and _synchronize_ as just two
    //   different types of _strands_.

    // - `Triple` and `Pair` have convenient typed accessor methods.
    // - All data classes have `componentX` accessor methods.
    // - Data classes can also be de-structured in a type-safe way through multiple assignment.
    val (avg, _ignored1, _ignored2) = res
    println("The historical average is: $avg")
    println("The current value is: ${res.component2()}")
    println("The current advice is: ${res.third}")
}
