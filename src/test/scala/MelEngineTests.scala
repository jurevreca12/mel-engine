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
package afe.tests

import _root_.org.slf4j.LoggerFactory
import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import dsptools.numbers.DspComplex
import breeze.math.Complex
import dsptools._
import dsptools.numbers._
import chisel3.experimental._
import fft._

class MelEngineTests extends AnyFlatSpec with ChiselScalatestTester {
  val logger = LoggerFactory.getLogger(classOf[MelEngineTests])

  val buildDirName = "verilog"
  val wordSize = 13
  val fftSize = 512
  val isBitReverse = true
  val radix = "2"
  val separateVerilog = true
                                                                                               
  val params = FFTParams.fixed( 
    dataWidth = wordSize,
    binPoint = 0,
    trimEnable= false,
    //dataWidthOut = 16, // only appied when trimEnable=True
    //binPointOut = 0,
    twiddleWidth = 16,
    numPoints = fftSize,
    decimType = DIFDecimType,
    trimType = RoundHalfUp,
    useBitReverse = isBitReverse,
    windowFunc = WindowFunctionTypes.Hamming(),
    overflowReg = true,
    numAddPipes = 1,
    numMulPipes = 1,
    sdfRadix = radix,
    runTime = false,
    expandLogic =  Array.fill(log2Up(fftSize))(0),
    keepMSBorLSB = Array.fill(log2Up(fftSize))(true),
    minSRAMdepth = 8
  )    

  behavior.of("MelEngine module together with SDFFT module")
  it should "simulate MelEngine and SDFFT together" in {
    test(new MelEngineTestBed(params, 8)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteFstAnnotation)) { dut =>
      dut.clock.setTimeout(32000)
      val length_sec = 1
      val fs = 16000.0
      val tone_freq = 200.0
      val tone = (0 until 512*32).map(i => Complex((math.sin(2.0 * math.Pi * tone_freq * (i/fs)) + 1.0)*2047.0*0.8, 0.0))
      //val tone = Array(0, 1, 2, 3, 2, 1, 0, 1).map(i => Complex(i, 0.0))
      dut.clock.step(10)
      dut.io.outStream.data.ready.poke(true.B)
      for (t <- 0 until tone.length) {
        dut.io.in.valid.poke(true.B)
        while (!dut.io.in.ready.peek().litToBoolean) { dut.clock.step() } // wait for ready
        dut.io.in.bits.poke(DspComplex.protoWithFixedWidth[FixedPoint](tone(t), FixedPoint(13.W, 0.BP)))
        if ((t+1) % fftSize == 0) {
          dut.io.lastIn.poke(true.B)
        }
        dut.clock.step()
        dut.io.lastIn.poke(false.B)
      }
      dut.io.in.valid.poke(false.B)
      dut.io.lastIn.poke(false.B)
      println("overflow = " + dut.io.overflow.peek().litValue)

      dut.clock.step(32000)
    }
  }
}
