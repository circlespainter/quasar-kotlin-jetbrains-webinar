import java.util.*
import java.util.concurrent.*

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.kotlin.fiber
import java.util.concurrent
import java.util.function.Supplier

import kotlin.concurrent.thread

// Stock value type. Data class wrapping can work as type aliasing (with "branding") as well
data class Value(val num: Double)

// Types of Stock advice
enum class Advice {
	BUY, SELL, KEEP
}

// Our Stock
class Stock(val name: String) {
	// Default singleton
	companion object  {
		val HISTORY_WINDOW_SIZE = 10

		val default = Stock("whatever")

		// Let's start with some straightforward impl.
		private val cache = ConcurrentHashMap<String, Stock>()
		fun find(name: String): Stock? = cache.getOrPut(name, { Stock(name) })
	}

	// Sliding window for historical data
	val hist = EvictingQueue.create<Value>(HISTORY_WINDOW_SIZE)

	// Let's fill the history
	init {
		for(i in 0..HISTORY_WINDOW_SIZE)
			hist.offer(Value(newVal().num))
	}

	// Random but not too far from the last one
	// _Functional already!_ 100% expression (= equation to be reduced), bindings are just for readability's sake
	private fun newVal(): Value =
		Value (
			if (hist.isEmpty())
				ThreadLocalRandom.current().nextDouble()
			else {
				val DEVIATION = 0.25
				val last = hist.last().num
				last + (last * ThreadLocalRandom.current().nextDouble(-DEVIATION, +DEVIATION))
			}
		)

	fun next(): Value {
		hist.offer(newVal())
		return hist.last()
	}

	// Random advice will work for now
	fun advice(): Advice = Advice.values().get(ThreadLocalRandom.current().nextInt(0, Advice.values().size()))

	// _Functional_: let's write a nifty mathematical equation for it
	fun avg(): Double =
		// What _is_ it? It is the division by its size of (the sum of (the projection of the historical data on its numerical value))
		hist.map { it.num }.sum() / hist.size()
}

class SlowAndFarStockInfoMachinery {
	companion object {
		val MAX_WAIT_MILLIS: Long = 3000

		fun <I, O> slowAsyncVal(s: I, f: (I) -> O): CompletableFuture<O> {
			val ret = CompletableFuture<O>()
			thread {
				Strand.sleep(ThreadLocalRandom.current().nextLong(0, MAX_WAIT_MILLIS))
				ret.complete(f(s))
			}
			return ret
		}

		fun findAsync(s: String) = slowAsyncVal(s, { Stock.find(it) ?: Stock.default })
		fun avgAsync(s: Stock) = slowAsyncVal(s, { it.avg() })
		fun nextAsync(s: Stock) = slowAsyncVal(s, { it.next() })
		fun adviceAsync(s: Stock) = slowAsyncVal(s, { it.advice() })
	}
}

public fun main(args: Array<String>) {
	// Our equation solver isn't smart enough and threads are too heavy, so let's try to use Java8's async monads
	// to build a computation representation.

	// 1) _Imperative I/O actions_: let's get the stock name from the user
	print("Insert the stock name: ")

	// 2) Let's submit the expensive search task to our efficient, async execution engine
	SlowAndFarStockInfoMachinery.findAsync(readLine() ?: "goog")
		.thenComposeAsync<Unit> (
			// 4) Let's chain more actions after the search is complete (sequence)

			java.util.function.Function {
				// 5) Let's submit calculation tasks in parallel (-> spawn) to our efficient, async execution engine
				val calcAvg = SlowAndFarStockInfoMachinery.avgAsync(it)
				val calcNext = SlowAndFarStockInfoMachinery.nextAsync(it)
				val calcAdvice = SlowAndFarStockInfoMachinery.adviceAsync(it)
				// 6) And finally, _only when they are complete_ (-> join) let's chain the output function
				CompletableFuture.allOf(calcAvg, calcNext, calcAdvice).thenApply (
					java.util.function.Function {
						println("The historical average is: ${calcAvg.join()}")
						println("The current value is: ${calcNext.join()}")
						println("The current advice is: ${calcAdvice.join()}")
					}
				)
			}
	)

	// 3) No need for run, will start immediately. But let's say we have submitted.
	println("...Submitted!")
}
