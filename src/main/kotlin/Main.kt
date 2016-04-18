// - Semicolons are optional in Kotlin.
// - Kotlin support _import bindings_.
// - "import" can be used for all sorts of inputs (package, class, object etc.).

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.fibers.futures.AsyncCompletionStage
import co.paralleluniverse.strands.dataflow.Val
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

// - Convenient shortcut
private fun <R> kval(b: () -> R): Val<R> =
        Val(SuspendableCallable<R> { b() })

fun main(args: Array<String>) {

    print("Insert the stock name: ")
    val sName = readLine() ?: "goog"

    // - Dataflow: let's build a network of computations with a single output each (or an error).
    // - Dataflow nodes can be `Val` (write-once) or `Var` (always mutable).
    // - A Dataflow program is similar to a "concurrent spreadsheet".
    // - The network's "arcs" are the value dependencies between the nodes: some nodes can block waiting on the output
    //   of other nodes before proceeding.
    // - Quasar will implement dataflow nodes with fibers (by default).

    // - There's 1 "initial" and "inert" node, its value is provided by the main thread. The "middle node" awaits its output.
    val stockNameVal = Val<String>()

    // - There's 1 "middle" node reading from the "initial" node. It will output the result of the stock search.
    val stockVal = kval { Stock.findAsync(stockNameVal.get()).fiberBlocking() }

    // - There are 3 "final" nodes reading from the "middle" node. Each of them will output a single stock information.
    val stockAvgVal = kval {
        println("Dataflow Val getting the AVG waiting for the stock...")
        val ret = Stock.avgAsync(stockVal.get()).fiberBlocking()
        println("...Dataflow Val got the AVG, returning it")
        ret
    }
    val stockNextVal = kval {
        println("Dataflow Val getting the VALUE waiting for the stock...")
        val ret = Stock.currentAsync(stockVal.get()).fiberBlocking()
        println("...Dataflow Val got the VALUE, returning it")
        ret
    }
    val stockAdviceVal = kval {
        println("Dataflow Val getting the ADVICE waiting for the stock...")
        val ret = Stock.adviceAsync(stockVal.get()).fiberBlocking()
        println("...Dataflow Val got the ADVICE, returning it")
        ret
    }

    // - The main thread kicks-off the network by providing the stock name.
    stockNameVal.set(sName)

    // - We'll "join" on the "final" nodes' output in order to print the stock information.
    println("The historical average is: ${stockAvgVal.get()}")
    println("The current value is: ${stockNextVal.get()}")
    println("The current advice is: ${stockAdviceVal.get()}")
}
