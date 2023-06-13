/*
 * Copyright 2022 Computer Systems Department, Jozef Stefan Insitute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package afe.bus

import chisel3._
import chisel3.util._
import chiseltest._

class AXIStreamIO[+T <: Data](gen: T) extends ReadyValidIO[T](gen) {
	// We extend the ready-valid interface with the last signal
	val last = Output(Bool())
}

/** This factory adds a decoupled handshaking protocol to a data bundle. */
object AXIStream {
  def apply[T <: Data](gen: T): AXIStreamIO[T] = new AXIStreamIO(gen)

  private final class EmptyBundle extends Bundle

  def apply(): AXIStreamIO[Data] = apply(new EmptyBundle)

  def empty: AXIStreamIO[Data] = AXIStream()
}

