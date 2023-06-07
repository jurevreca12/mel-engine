package afe

import chisel3._
import chisel3.util._
import dsptools._
import dsptools.numbers._
import afe.memory.SRAMInit
import afe.bus.AXIStream
import chisel3.util.log2Up
import fft.FFTParams
import scala.io.Source

/*
 The input is a set of 32 frames 512 in length. At 16kHz sampling rate 32 frames equal roughly a second.
 The first 257 samples of each frames are to be considered and the rest thrown away, as it is a mirror 
 image of the first part. Also, only the real part is considered, and the imaginary part is ignored.
 The mel filters are a set of 20 filters, which represents a 20 x 257 sparse matrix to be multiplied by
 the corresponding real input FFT.
*/
class MelEngine [T <: Data : Ring](fftParams: FFTParams[T], outParamSize: Int) extends Module {
  val io = IO(new Bundle {
    val fftIn = Flipped(Decoupled(fftParams.protoIQstages(log2Up(fftParams.numPoints) -1)))
    val lastFft = Input(Bool())

    val outStream = new AXIStream(32)
  })

  val elemCnt = RegInit(0.U(log2Up(512).W))  // Counts the number of elements within a frame
  val romAddr = Wire(UInt(log2Up(512).W))
  val filCnt = RegInit(0.U(log2Up(20).W))
  val frameCnt = RegInit(0.U(log2Up(32).W))  // Counts the number of frames to a second

  val rom = Module(new SRAMInit(depth=471, memFile="melFilters.hex")) // the filter values
  val melFilterEndings = Source.fromFile("melIndex.txt").getLines().toList  // rom addresss where filters end
  val nextEnding = MuxLookup(filCnt, 0.U, melFilterEndings.zipWithIndex.map(x => (x._2.U) -> (x._1.toInt.U)))

  val squareMul = Module(new dspMultiplier(13, 13))
  // U(26,0) x U(0,16) = U(26,16) => U(26,16) >> 10 => U(26, 6)
  val melMul0 = Module(new dspMultiplier(26, 16))
  val melMul1 = Module(new dspMultiplier(26, 16))

  val acc0 = RegInit(0.U(32.W))
  val acc1 = RegInit(0.U(32.W))

  object melState extends ChiselEnum {
    val sWAIT = Value(0.U)
    val sCOMP = Value(1.U)
  }

  val nstate = WireInit(melState.sWAIT)
  val state  = RegInit(melState.sWAIT)


  /////////////////////////////
  /// Element Counter Logic ///
  /////////////////////////////
  when (state === melState.sWAIT) {
    when (io.fftIn.valid === true.B &&
          io.fftIn.ready === true.B) {
      elemCnt := elemCnt + 1.U
    }.otherwise {
      elemCnt := 0.U
    }
  }.elsewhen(state === melState.sCOMP){
    when (io.fftIn.valid === true.B &&
          io.fftIn.ready === true.B) {
      elemCnt := elemCnt + 1.U
    }
  }

  when (elemCnt <= 257.U) {
    romAddr := elemCnt
  }.otherwise {
    romAddr := 0.U
  }


  when (io.outStream.data.valid) {
    when (filCnt < 19.U) {
      filCnt := filCnt + 1.U
    }.otherwise {
      filCnt := 0.U
    }
  }

  when (!filCnt(0) && elemCnt === nextEnding) {
    acc0 := 0.U
  }.elsewhen (melMul0.io.outValid) {
    acc0 := acc0 + melMul0.io.out
  }
  
  when (filCnt(0) && elemCnt === nextEnding) {
    acc1 := 0.U
  }.elsewhen (melMul1.io.outValid) {
    acc1 := acc1 + melMul1.io.out
  }

  rom.io.rdAddr := elemCnt
  rom.io.wrAddr := 0.U
  rom.io.wrData := 0.U
  rom.io.rdEna  := true.B
  rom.io.wrEna  := false.B
  io.outStream.data.valid := elemCnt === nextEnding
  io.outStream.data.bits := Mux(filCnt(0), acc1, acc0)
  io.outStream.last := false.B
  io.fftIn.ready := true.B

  squareMul.io.inp0 := io.fftIn.bits.real.asUInt
  squareMul.io.inp1 := io.fftIn.bits.real.asUInt
  squareMul.io.inValid := io.fftIn.valid && io.fftIn.ready
  melMul0.io.inp0 := squareMul.io.out
  melMul1.io.inp0 := squareMul.io.out
  melMul0.io.inp1 := rom.io.rdData(31,16)
  melMul1.io.inp1 := rom.io.rdData(16,0)
  melMul0.io.inValid := squareMul.io.outValid
  melMul1.io.inValid := squareMul.io.outValid
}


class dspMultiplier(inp0Width: Int, inp1Width: Int) extends Module {
  val outWidth = inp0Width + inp1Width
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val outValid = Output(Bool())
    val inp0 = Input(UInt(inp0Width.W))
    val inp1 = Input(UInt(inp1Width.W))
    val out = Output(UInt((outWidth).W))
  })

  io.outValid := io.inValid
  io.out := io.inp0 * io.inp1
}
