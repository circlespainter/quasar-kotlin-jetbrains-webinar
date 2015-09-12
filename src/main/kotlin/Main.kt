import java.util.*
import java.util.concurrent.*

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.fibers.futures.AsyncCompletionStage
import co.paralleluniverse.kotlin.Receive
import co.paralleluniverse.kotlin.fiber
import co.paralleluniverse.kotlin.select
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

	// 2) Ok... Now that we have super-powers we want to be extra-cool and, instead of joining everything, get results
	//    over channels and select the first one available.
	val avgRetCh = Channels.newChannel<Any>(0)
	val nextRetCh = Channels.newChannel<Any>(0)
	val adviceRetCh = Channels.newChannel<Any>(0)

	fiber {
		// Of course, each thread / fiber has full stack available for debugging purposes.

		// 3) Let the fiber convert a long, async operation into a fiber-blocking one and get its result.
		val s = AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.findAsync(readLine() ?: "goog"))

		// 4) Let's launch more fibers to perform in parallel the other info retrieval, converting them into fiber-blocking,
		//    and return their result on the channels.
		fiber {
			avgRetCh.send(AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.avgAsync(s)))
		}
		fiber {
			nextRetCh.send(AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.nextAsync(s)))
		}
		fiber {
			adviceRetCh.send(AsyncCompletionStage.get(SlowAndFarStockInfoMachinery.adviceAsync(s)))
		}
	}

	// 4) Let's print the data in the order of arrival, selecting on the channels.
	for(i in 1..3)
		select(60, TimeUnit.SECONDS, Receive(avgRetCh), Receive(nextRetCh), Receive(adviceRetCh)) {
			when (it) {
				is Receive -> {
					if (it.receivePort == avgRetCh) println("The historical average is: ${it.msg}")
					else if (it.receivePort == nextRetCh) println("The current value is: ${it.msg}")
					else if (it.receivePort == adviceRetCh) println("The current advice is: ${it.msg}")
				}
				else -> println("Timeout!!!") // Shouldn't happen
			}
		}
}
