import java.util.*
import java.util.concurrent.*

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.fibers.futures.AsyncCompletionStage
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
	// ...So sequence, threads and joins are useful. But why using expensive threads
	// when we can use featherweight fibers instead?

	// 1) _Imperative I/O actions_: let's get the stock name from the user
	print("Insert the stock name: ")

	val res = fiber {
		// Of course, each thread / fiber has full stack available for debugging purposes.

		// 2) Let the fiber convert a long, async operation into a fiber-blocking one and get its result
		val s = AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.findAsync(readLine() ?: "goog"))

		// 3) Let's launch more fibers to perform in parallel the other info retrieval, converting them into fiber-blocking
		val running = Triple (
				fiber {
					AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.avgAsync(s))
				},
				fiber {
					AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.nextAsync(s))
				},
				fiber {
					AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.adviceAsync(s))
				}
		)

		// 4) Let's wait for all the retrieving fibers to complete and return their result
		Triple(running.first.get(), running.second.get(), running.third.get())
	}.get() // 5) Let's wait for the result triple from the searching fiber

	// 6) All results available again in the main thread, all very sequentially.
	//    How much easier and debuggable is that compared to monads & co.? Same as using regular threads.
	println("The historical average is: ${res.first}")
	println("The current value is: ${res.second}")
	println("The current advice is: ${res.third}")
}
