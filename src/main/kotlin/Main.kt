import java.util.concurrent.ExecutionException

import co.paralleluniverse.strands.*
import co.paralleluniverse.strands.channels.*

import co.paralleluniverse.fibers.*
import co.paralleluniverse.kotlin.fiber
import java.util.concurrent.ThreadLocalRandom
import kotlin.concurrent.thread

val DEVIATION = 0.25

data class Value(val get: Double)

enum class Sentiment {
	BUY, SELL, KEEP
}

class Stock(val name: String) {
	var prev: Double? = null

	fun value(): Value {
		if (prev == null)
			prev = ThreadLocalRandom.current().nextDouble()
		else
			prev = prev!!.plus(prev!!.times(ThreadLocalRandom.current().nextDouble(-DEVIATION, +DEVIATION)))
		return Value(prev!!)
	}

	fun sentiment(): Sentiment = Sentiment.values().get(ThreadLocalRandom.current().nextInt(0, Sentiment.values().size()))
}

public fun main(args: Array<String>) {
	print("Insert the stock name: ")
	val sName = readLine()
	val s = Stock(sName!!)
	val valueResultChannel = Channels.newChannel<Value>(0);
	val sentimentResultChannel = Channels.newChannel<Sentiment>(0);
	thread {
		valueResultChannel.send(s.value())
	}
	thread {
		sentimentResultChannel.send(s.sentiment())
	}
	println("The current value is: ${valueResultChannel.receive()}")
	println("The current sentiment is: ${sentimentResultChannel.receive()}")
}
