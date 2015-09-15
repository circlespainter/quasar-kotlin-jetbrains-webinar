// - Semicolons are optional in Kotlin.
import java.util.*

// - Kotlin support _import bindings_.
import java.util.concurrent as jc
import java.util.function.*

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.fibers.futures.AsyncCompletionStage
import co.paralleluniverse.kotlin.Actor
import co.paralleluniverse.kotlin.Receive
import co.paralleluniverse.kotlin.fiber
import co.paralleluniverse.kotlin.select
import co.paralleluniverse.strands.dataflow.Val
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
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
		private val cache = jc.ConcurrentHashMap<String, Stock>()

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
						jc.ThreadLocalRandom.current().nextDouble()
					else {
						// - Locals can be mutable or immutable too.
						val DEVIATION = 0.25
						val last = hist.last().num
						last + (last * jc.ThreadLocalRandom.current().nextDouble(-DEVIATION, +DEVIATION))
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
			Advice.values().get(jc.ThreadLocalRandom.current().nextInt(0, Advice.values().size()))

	/**
	 * Returns the historical average
	 */
	fun avg() =
		// - What _is_ the average? It is the division by its size of
		//     (the sum of
		//       (the map
		//         of the historical data
		//         on its numerical value
		//       )
		//     )
		hist.map { it.num }.sum() / hist.size()
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

// - _Functional monadic async_: let's _define_ a representation of our program as a "special" equation
//                               both marked as performing async actions and compositions and allowed to used it.
// - Monadic programs are essentially _async_ and _lazy_ and usually implemented in libraries so
//   they are extremely difficult to debug with regular tools.

// - Monads creep into signatures because they are meant to "mark" programs, they are _infectious_.
public fun main(args: Array<String>) {

	print("Insert the stock name: ")

	val sName = readLine() ?: "goog"

	println("Defining and submitting the monadic async program...")

	// - Let's use Java 8's `CompletableFuture` to model our monadic async program.
	Stock.findAsync(sName)
		// - Monadic `flatMap` or `bind`
		.thenComposeAsync<Unit>(Function {
			// - We'll create 3 "concurrent" async monads now.
			val calcAvg = Stock.avgAsync(it)
			val calcNext = Stock.currentAsync(it)
			val calcAdvice = Stock.adviceAsync(it)
			// - We'll compose them with `allOf` and then use `thenApply` or monadic `map` to print the results.
			CompletableFuture.allOf(calcAvg, calcNext, calcAdvice).thenApply(Function {
				println("The historical average is: ${calcAvg.join()}")
				println("The current value is: ${calcNext.join()}")
				println("The current advice is: ${calcAdvice.join()}")
			})
		})

	// 3) No need for run, will start immediately. But let's say we have submitted.
	println("...Submitted!")
}
