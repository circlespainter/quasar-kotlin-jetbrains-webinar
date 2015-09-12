package monads.io

data class LineStdIOMonad<T1>(val v: () -> T1) {
	companion object {
		/**
		 * @return A chainable function doing nothing.
		 */
		fun start() =
			LineStdIOMonad({Unit})
	}

	object Native {
		/**
		 * @return An "unsafe native" chainable function that obtains its result by reading a line from stdio
		 * and returns it (or "" if unsuccessful).
		 */
		// @Native
		fun readLine() =
			LineStdIOMonad({kotlin.io.readLine() ?: ""})

		/**
		 * @return An "unsafe native" chainable function that prints ignores its input and prints a line `l` on stdio.
		 */
		// @Native
		fun printLine(l: String) =
			LineStdIOMonad({kotlin.io.println(l)})

		/**
		 * @return A "unsafe native" function that prints a string `s` on stdio.
		 */
		// @Native
		fun print(s: String) = LineStdIOMonad({kotlin.io.print(s)})
	}

	/**
	 * @return A new chainable function obtained by chaining a new one after the
	 *         current one. The new one is produced by `f` depending on the result
	 *         of the current one.
	 */
	fun <T2> semiColon(f: (T1) -> LineStdIOMonad<T2>) =
		LineStdIOMonad ( // The new chainable function will contain...
			{              // ...a function that when evaluated...
				f (          // ...will obtain the rest of the program...
					v()        //   ...based on the result of the currently wrapped function...
				).v()        // ...and will then evaluate the rest of the program.
			}
		)

	object End {
		/**
		 * @return The result of a chainable function's execution.
		 */
		fun <T> run(m: LineStdIOMonad<T>): T = m.v()
	}
}
