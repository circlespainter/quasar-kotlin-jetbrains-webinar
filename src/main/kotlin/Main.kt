import java.util.*
import java.util.concurrent.*

import com.google.common.collect.EvictingQueue

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*
import co.paralleluniverse.fibers.*
import co.paralleluniverse.kotlin.fiber
import monads.io.LineStdIOMonad

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
	// _Functional monadic I/O_: let's _define_ a representation of our program using I/O as a formula
	val ioProgram =
		LineStdIOMonad.start().semiColon {
			// 1 Get the stock name
			LineStdIOMonad.Native.print("Insert the stock name: ").semiColon {
				LineStdIOMonad.Native.readLine().semiColon {
					// 2) Find the stock
					// ...Coool :)))
					val s = (Stock.find(it) ?: Stock.default)
					// 3) If our equation solver was smart enough (and our language restricted enough not to exhibit unexpected stateful interactions
					// (probably purely functional), _it could in theory figure out that all the 3 values are independent and can be computed concurrently_:
					val res = Triple(s.avg(), s.next(), s.advice())
					// 4) And finally, _only then_ (-> join) let's output with monadic I/O
					LineStdIOMonad.Native.printLine("The historical average is: ${res.first}").semiColon {
						LineStdIOMonad.Native.printLine("The current value is: ${res.second}").semiColon {
							LineStdIOMonad.Native.printLine("The current advice is: ${res.third}")
						}
					}
				}
			}
		}

	// 5) Let's run our "impure" I/O program we just define on our "impure" evaluation engine that supports "impure" native I/O functions
	LineStdIOMonad.End.run(ioProgram)
}
