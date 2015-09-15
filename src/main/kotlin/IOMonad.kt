/**
 * The context this monadic pattern captures (and propagates) is an arbitrary lazy computation (the constructor enforces
 * this) that can include some predetermined, native I/O operations (enforced through their return type, which is
 * the monadic type, and by the chaining function's `this` type, which is still the monadic type).
 *
 * Computations defined like this are still purely functional but also _marked and separate_ from the rest of the pure
 * functional code. Furthermore, being lazy, they can be triggered by at the evaluator's discretion just like any other
 * computation.
 */
data class LazyLineStdIOMonad<T1>(val v: () -> T1) {
	companion object {
		/**
		 * @return A chainable function doing nothing.
		 */
		fun start() =
			LazyLineStdIOMonad({Unit})
	}

	object Native {
		/**
		 * @return An "unsafe native" chainable function that obtains its result by reading a line from stdio
		 * and returns it (or "" if unsuccessful).
		 */
		// @Native
		fun readLine() =
			LazyLineStdIOMonad({kotlin.io.readLine() ?: ""})

		/**
		 * @return An "unsafe native" chainable function that prints ignores its input and prints a line `l` on stdio.
		 */
		// @Native
		fun printLine(l: String) =
			LazyLineStdIOMonad({kotlin.io.println(l)})

		/**
		 * @return A "unsafe native" function that prints a string `s` on stdio.
		 */
		// @Native
		fun print(s: String) = LazyLineStdIOMonad({kotlin.io.print(s)})
	}

	/**
	 * @return A new chainable function obtained by chaining a new one after the
	 *         current one. The new one is produced by `f` depending on the result
	 *         of the current one.
	 */
	fun <T2> ioSemiColon(f: (T1) -> LazyLineStdIOMonad<T2>) =
		LazyLineStdIOMonad ( // The new chainable function will wrap...
			{              // ...a function that when evaluated...
				f (          // ...will obtain the caller-provided rest of the I/O program `f`...
					v()        //   ...based on the result of the currently wrapped function...
				).v()        // ...and will then evaluate it.
			}
		)

	/**
	 * @return A new chainable function obtained by chaining a regular function `f`
	 *         after the current one. `f` can use the value produced by the current
	 *         chainable function.
	 */
	fun <T2> pipeThrough(f: (T1) -> T2) =
		LazyLineStdIOMonad ( // The new chainable function will wrap...
			{              // ...a function that when evaluated...
				f (          // ...will perform a caller-provided evaluation `f`...
					v()        //   ...based on the result of the currently wrapped function.
				)
			}
		)

	object End {
		/**
		 * @return The result of a chainable function's execution.
		 */
		fun <T> run(m: LazyLineStdIOMonad<T>): T = m.v()
	}
}
