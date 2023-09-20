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
package fft

import _root_.org.slf4j.LoggerFactory
import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import dsptools.numbers.DspComplex
import breeze.math.Complex
import dsptools.numbers._
import chisel3.experimental._

class SDFFTTests extends AnyFlatSpec with ChiselScalatestTester {
  val logger = LoggerFactory.getLogger(classOf[SDFFTTests])

  val buildDirName = "verilog"
  val wordSize = 13
  val fftSize = 512
  val isBitReverse = true
  val radix = "2"
  val separateVerilog = true
                                                                                               
  val params = FFTParams.fixed( 
    dataWidth = wordSize,                           
    binPoint = 0,
    twiddleWidth = 16,
    numPoints = fftSize,
    decimType = DIFDecimType,
    useBitReverse = isBitReverse,
    windowFunc = WindowFunctionTypes.Hamming(),
    overflowReg = true,
    numAddPipes = 1,
    numMulPipes = 1,
    sdfRadix = radix,
    runTime = false,
    expandLogic = Array.fill(log2Up(fftSize))(0),
    keepMSBorLSB = Array.fill(log2Up(fftSize))(true),
    minSRAMdepth = 8
  )    

  behavior.of("SDFFT module")
  it should "simulate fft" in {
    test(new SDFFFT(params)) { dut =>
      dut.clock.setTimeout(2000)
      val cycles = 20
      val length = math.Pi * 2 * cycles
      val resolution = 512
      val tone = (0 until 512).map(i => Complex(math.min((math.sin((length/resolution) * i) + 1)*2048, 4095), 0.0))
      dut.clock.step(10)
      for (t <- 0 until 512) {
        dut.io.in.valid.poke(true.B)
        while (!dut.io.in.ready.peek().litToBoolean) { dut.clock.step() } // wait for ready
        dut.io.in.bits.poke(DspComplex.protoWithFixedWidth[FixedPoint](tone(t), FixedPoint(13.W, 0.BP)))
        if (t == 511) {
          dut.io.lastIn.poke(true.B)
        }
        dut.clock.step()
      }
      dut.io.in.valid.poke(false.B)
      dut.io.lastIn.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step(2000)
    }
  }
}
