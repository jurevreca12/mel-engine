package afe.melEngine

import chisel3._
import afe.memory.SRAMInit
import chisel3.util.log2Up
import chisel3.experimental.ChiselEnum


class MelEngine [T <: Data : Ring](fftParams: FFTParams[T], outParamSize: Int) extends Module {  
  val io = (new Bundle {
    val fftIn = Flipped(Decoupled(fftParams.protoIQstages(log2Up(fftParams.numPoint) -1)))
    val lastFft = Input(Bool())

    val outStream = new AXIStream(32)
  })
  
  val mem = SRAMInit(depth=471, memFile="melFilters.hex")
  val vecInd = RegInit(0.U(log2Up(512).W))
  val squareMul = dspMultiplier(13, 13)

  val melMul0 = dspMultiplier(26, 16, shift=26+16-32)
  val melMul1 = dspMultiplier(26, 16, shift=26+16-32)
  val acc0 = RegInit(0.U(32.W))
  val acc1 = RegInit(0.U(32.W))

  object melState extends ChiselEnum {
    val sWAIT = Value(0.U)
    val sCOMP = Value(1.U)
  }

  val nstate = WireInit(melState.sWAIT)
  val state  = RegInit(melState.SWAIT)


  ////////////////////////////
  ///  Vector Index Logic  ///
  ////////////////////////////
  when (state == melState.sWAIT) {
    vecInd := 0.U
  }.elsewhen(state == melState.sCOMP && !nextMelValReady){
    vecInd := vecInd + 1.U
  }

}


class dspMultiplier(inp0Width: Int, inp1Width: Int, shift: int = 0) extends Module {
  require(shift >= 0)
  val outWidth = if (shift == 0) {
    inp0Width + inp1Width
  } else {
    require(inp0Width + inp1Width - shift == 32)
    32
  }

  val io = (new Bundle {
    val inp0 = Input(UInt(inp0Width.W)),
    val inp1 = Input(UInt(inp1Width.W)),
    val out = Output(UInt((outWidth).W))
  })

  val mul = Wire(Uint())
  mul := io.inp0 * io.inp1
  if (shift == 0) {
    io.out := mul
  } else {
    io.out := mul >> shift
  }
}
