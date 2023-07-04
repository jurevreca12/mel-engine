package afe

import chisel3._
import chisel3.util._
import interfaces.amba.axis.AXIStream
import fft._
import dsptools._
import dsptools.numbers._
import dsptools.numbers.implicits._
import afe._

class AudioFeaturesExtract[T <: Data : Real : Ring : BinaryRepresentation](fftParams: FFTParams[T]) 
extends Module {
  val io = IO(new Bundle {
    val inStream = Flipped(AXIStream(fftParams.protoIQ))
    val outStream = AXIStream(SInt(6.W))
    val overflow = Output(Bool())
    val busy = Output(Bool())
})

  val sdfft = Module(new SDFFFT(fftParams))
  val melEngine = Module(new MelEngine(fftParams, 20, 32))

  io.inStream.ready := sdfft.io.in.ready 
  sdfft.io.in.valid := io.inStream.valid
  sdfft.io.in.bits  := io.inStream.bits
  sdfft.io.lastIn   := io.inStream.last
  io.overflow := sdfft.io.overflow.get.reduceTree(_ || _)
  dontTouch(io.overflow)
  io.busy := sdfft.io.busy

  melEngine.io.fftIn <> sdfft.io.out
  melEngine.io.lastFft := sdfft.io.lastOut

  io.outStream <> melEngine.io.outStream
}
