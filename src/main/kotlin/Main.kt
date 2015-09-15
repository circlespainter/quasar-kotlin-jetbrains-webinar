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

// - Kotlin _extension functions_ allow adding functionality to classes whose source are not under our control (libs).
// - They are _statically dispatched_.
// - We'll convert slow, async `CompletableFuture`-based operation into _fiber-blocking_ ones.
Suspendable fun <T> CompletionStage<T>.fiberBlocking() = AsyncCompletionStage.get(this)

/**
 * Creates an actor that will receive and print stock information
 */
// - Actors have been made popular by Erlang to write fault-tolerant applications.
// - Actors are a big topic and there's no time to cover it all so we'll see how to write one.
// - A _Quasar-Kotlin actor_ is just like an Erlang actor: a sequential process that will run on a
//   new fiber (by default, but it can run on any type of strand including _threads_).
// - Spawned actors offer a _public handle_ or _ref_ that can be used to send them general or actor-type-specific messages.
// - Actor refs can be passed around but they can also be stored in (and retrieved from) a global registry.
// - Actors have only one _input channel_ (AKA _mailbox_) but they perform _selective receive_ operations.
// - Notice that Kotlin allows _top-level functions and properties_.
fun newActor() = object : Actor() {
	override Suspendable fun doRun() {
		// - We'll perform 2 receives, _deferring_ the advice if it comes in first.
		for (i in 1..2)
			receive(60, SECONDS) {
				when (it) {
					is Double -> println("The historical average is: ${it}")
					is Value -> println("The current value is: ${it}")
					is Timeout -> println("Timeout!!!") // Shouldn't happen
					else -> defer()
				}
			}

		// - We'll then receive the advice.
		receive(60, SECONDS) {
			when (it) {
				is Advice -> {
					println("The current advice is: ${it}")
					// - Kotlin allows _non-local returns_ from flow control constructs as well as from
					//   _inline higher-order functions_ (like Quasar-Kotlin's actor `receive`).
					// - We exit the actor as we don't have anything more to do.
					return
				}
				else -> println("Unexpected!!!") // Shouldn't happen
			}
		}

		println("Never printed")
	}
}

public fun main(args: Array<String>) {

	print("Insert the stock name: ")
	val sName = readLine() ?: "goog"

	val actor = newActor()
	val actorRef = actor.spawn() // The actor handle

	fiber {
		val s = Stock.findAsync(sName).fiberBlocking()

		// - We'll send once again each information on its return channel after obtaining it.
		// - Notice that all Quasar abstractions can interact with each other as well as with regular threads.
		fiber {
			println("Fiber getting the AVG started...")
			actorRef.send(Stock.avgAsync(s).fiberBlocking())
			println("...Fiber got the AVG.")
		}
		fiber {
			println("Fiber getting the VALUE started...")
			actorRef.send(Stock.currentAsync(s).fiberBlocking())
			println("...Fiber got the VALUE.")
		}
		fiber {
			println("Fiber getting the ADVICE started...")
			actorRef.send(Stock.adviceAsync(s).fiberBlocking())
			println("...Fiber got the ADVICE.")
		}
	}

	// - We need to join only the actor from the main thread because it'll be last to finish.
	// - If we don't join our program could terminate before the actor (and the fibers it receives data from) has
	//   finished executing.
	actor.join()
}
