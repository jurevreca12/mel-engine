package afe.tests

import chisel3._
import chisel3.util._
import fft._
import dsptools._
import dsptools.numbers._
import dsptools.numbers.implicits._
import afe.bus.AXIStream
import afe._

class MelEngineTestBed[T <: Data : Real : Ring : BinaryRepresentation](fftParams: FFTParams[T], outParamSize: Int) 
extends Module {
  val io = IO(new Bundle {
    // FFTIO
    val in = Flipped(Decoupled(fftParams.protoIQ))
    val lastIn = Input(Bool())
    val overflow = Output(Bool())

    // MelEngine out
    val outStream = new AXIStream(32)
})

  val sdfft = Module(new SDFFFT(fftParams))
  val melEngine = Module(new MelEngine(fftParams, 20, 32, outParamSize))

  sdfft.io.in <> io.in
  sdfft.io.lastIn := io.lastIn
  io.overflow := sdfft.io.overflow.getOrElse(VecInit(false.B)).reduceTree(_ || _)

  melEngine.io.fftIn <> sdfft.io.out
  melEngine.io.lastFft := sdfft.io.lastOut

  io.outStream <> melEngine.io.outStream
}
