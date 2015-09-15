// - Semicolons are optional in Kotlin.
import java.util.*

// - Kotlin support _import bindings_.
import java.util.concurrent as jc

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
		fun find(name: String = "goog") =
				// - Kotlin supports higher-order functions.
				// - If the last parameter is a function, a DSL-like block-based syntax can be used.
				// - A _lambda_, or _function literal_, has the form `{ (param1[:Type1], ...) -> BODY }` and
		    //   if there is only a single parameter the `{ BODY }` form can be used and the parameter
		    //   can be referred to as `it`.
				cache.getOrPut(name) {
					Stock(name)
				}
		private val cache = jc.ConcurrentHashMap<String, Stock>()
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
	fun current(): Value {
		hist.offer(newVal())

		// - `return` is mandatory if the method is defined as a block
		return hist.last()
	}

	/**
	 * Calculates the current stock advice
	 */
	fun advice(): Advice =
			Advice.values().get(jc.ThreadLocalRandom.current().nextInt(0, Advice.values().size()))

	/**
	 * Returns the historical average
	 */
	// - Functional style: the program is an equation to be "solved".
	fun avg(): Double =
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

// - _Functional monadic I/O_: let's _define_ a representation of our program  as a "special" equation
//                             both marked as using I/O and allowed to used it.
// - Monadic programs are essentially _async_ and _lazy_ and usually implemented in libraries so
//   they are extremely difficult to debug with regular tools.

// - Monads creep into signatures because they are meant to "mark" programs, they are _infectious_.
public fun subProgram(it: Triple<Double, Value, Advice>): LazyLineStdIOMonad<Unit> {
	return LazyLineStdIOMonad.Native.printLine("The historical average is: ${it.first}").ioSemiColon { _ ->
		LazyLineStdIOMonad.Native.printLine("The current value is: ${it.second}").ioSemiColon { _ ->
			LazyLineStdIOMonad.Native.printLine("The current advice is: ${it.third}")
		}
	}
}

public fun main(args: Array<String>) {
	println("Defining monadic program...")

	val ioProgram =
			// - Monadic `flatMap` or `bind`
			LazyLineStdIOMonad.start().ioSemiColon {
				LazyLineStdIOMonad.Native.print("Insert the stock name: ").ioSemiColon {
					LazyLineStdIOMonad.Native.readLine()
							// - Monadic `map`
							.pipeThrough {
								Stock.find(it) ?: Stock.default
							}
							// - If our functional equation solver was smart enough (and our language restricted enough
							//   not to exhibit unexpected stateful interactions (probably purely functional),
							//   _it could in theory figure out that the 3 values are independent and can be computed concurrently_:
							.pipeThrough {
								Triple (
									it.avg(),
									it.current(),
									it.advice())
							}
							// - Kotlin fuctions can be used as lambdas
							.ioSemiColon(::subProgram)
				}
			}

	// - Let's run our "impure" I/O program on our "impure" evaluation engine that supports "impure" native I/O functions.
	println("...Monadic program defined, executing it.")

	LazyLineStdIOMonad.End.run(ioProgram)
}
