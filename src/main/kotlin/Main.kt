import java.util.*
import java.util.concurrent.*

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.kotlin.fiber

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

public fun main(args: Array<String>) {
	// Let's setup some nifty Quasar channels for communication with parallel threads ;)
	val avgResultChannel = Channels.newChannel<Double>(0);
	val valueResultChannel = Channels.newChannel<Value>(0);
	val sentimentResultChannel = Channels.newChannel<Advice>(0);

	// 1) _Imperative I/O actions_: let's get the stock name from the user
	print("Insert the stock name: ")

	// 2) Find the stock
	// ...Coool :)))
	val s = (Stock.find(readLine() ?: "goog") ?: Stock.default)

	// 3) _Only then_ (-> sequence) get info, but we can do it _in parallel with threads_ as we don't have action dependency
	thread {
		avgResultChannel.send(s.avg())
	}
	thread {
		valueResultChannel.send(s.next())
	}
	thread {
		sentimentResultChannel.send(s.advice())
	}

	// 4) And finally, _only then_ (-> join) let's output
	println("The historical average is: ${avgResultChannel.receive()}")
	println("The current value is: ${valueResultChannel.receive()}")
	println("The current advice is: ${sentimentResultChannel.receive()}")
}
