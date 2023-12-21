package melengine

import chisel3._
import chisel3.util._
import interfaces.amba.axis.AXIStream
import fft._
import dsptools.numbers._
import dsptools.numbers.implicits._
import afe._

class MelEngineTestBed[T <: Data : Real : BinaryRepresentation](fftParams: FFTParams[T]) 
extends Module {
  val io = IO(new Bundle {
    val inStream = Flipped(AXIStream(fftParams.protoIQ))
    val outStream = AXIStream(SInt(6.W))
    val overflow = Output(Bool())
    val busy = Output(Bool())
})

  val sdfft = Module(new SDFFFT(fftParams))
  val melEngine = Module(new MelEngine(fftParams, 20, 32))

  // Fix discrepancy between last signal semantics of LBIRDriver and FFT.
  // FFT has per frame last signals, LBIRDriver per tensor last signal.
  val (_, fftCounterWrap) = Counter(io.inStream.fire, fftParams.numPoints)
  
  object afeState extends ChiselEnum {
    val sWAIT  = Value(0.U)
    val sREADY = Value(1.U)
  }
  val state = RegInit(afeState.sREADY)

  when (state === afeState.sREADY && fftCounterWrap) {
    state := afeState.sWAIT
  }.elsewhen(state === afeState.sWAIT && sdfft.io.lastOut) {
    state := afeState.sREADY
  }

  io.inStream.ready := state === afeState.sREADY 
  sdfft.io.in.valid := io.inStream.valid
  sdfft.io.in.bits  := io.inStream.bits
  sdfft.io.lastIn   := io.inStream.last || fftCounterWrap
  io.overflow := sdfft.io.overflow.get.reduceTree(_ || _)
  dontTouch(io.overflow)
  io.busy := sdfft.io.busy

  melEngine.io.fftIn <> sdfft.io.out
  melEngine.io.lastFft := sdfft.io.lastOut

  io.outStream <> melEngine.io.outStream
}
