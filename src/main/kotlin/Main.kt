// - Semicolons are optional in Kotlin.
import java.util.*

// - Kotlin support _import bindings_.
// - "import" can be used for all sorts of inputs (package, class, object etc.).
import java.util.concurrent as jc

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.kotlin.fiber

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
	fun avg(): Double {
		// Mutable local
		var sum = 0.0
		for (i in hist)
			sum += i.num
		return sum / hist.size()
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

public fun main(args: Array<String>) {
	print("Insert the stock name: ")

	// - Kotlin aims at eliminating `NullPointerException`s and to this end it has has _nullable_
	//   and _non-nullable_ types as well as _platform_ types for values produced by Java code.
	// - A _nullable reference_ is suffixed by a "?" (question mark).
	val sNameMaybe: String? = readLine()

	// - After getting the stock name we can look it up
	val sMaybe = Stock.find (
			if (sNameMaybe == null)
				// - In this `if` branch Kotlin will autocast String? -> String.
				// - This works only with immutables because mutables can be changed after the check.
				// - There is a more compact syntax for this null-check, the "elvis" expression.
				"goog"
			else
				sNameMaybe
	)
	val s = (if (sMaybe == null) Stock.default else sMaybe)

	// - We'll now use _threads_ and _channels_ to retrieve the stock information concurrently.

	// - Quasar channels are just like Go channels.
	// - These are channels without buffer, so they synchronize producers and consumers.
	// - Channels with buffers decouple them up to the buffer size.
	// - When a buffer is full, the channel can be configured to throw, drop or block.
	// - Channels can be created to accept multiple consumers and/or producers.
	val avgResultChannel = Channels.newChannel<Double>(0);
	val valueResultChannel = Channels.newChannel<Value>(0);
	val adviceResultChannel = Channels.newChannel<Advice>(0);

	// - Threads are virtual sequential machines with a body.
	// - Threads can be _spawned_ and _joined_ (= awaited for termination).
	// - The `thread` higher-order function/DSL is part of the Kotlin stdlib and uses regular JVM threads.
	// - The JVM implements threads as general-purpose OS threads, which wake up fast from I/O but not
	//   from synchronization and are heavy on resources. This means they're not ideal for fine-grained
	//   concurrency.
	// - Each thread will retrieve an information about the stock and will output it on a dedicated
	//   result channel.
	thread {
		avgResultChannel.send(s.avg())
	}
	thread {
		valueResultChannel.send(s.current())
	}
	thread {
		adviceResultChannel.send(s.advice())
	}

	// - We'll join the stock information threads from the main thread by performing a potentially
	//   thread-blocking `receive` operation from each result channel.
	val avg = avgResultChannel.receive()
	val v = valueResultChannel.receive()
	val advice = adviceResultChannel.receive()

	// - We'll do so directly in Kotlin's _string templates_.
	println("The historical average is: $avg")
	println("The current value is: $v")
	println("The current advice is: $advice")
}
