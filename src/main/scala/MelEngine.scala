package melengine

import chisel3._
import chisel3.util._
import memories.MemoryGenerator
import interfaces.amba.axis.AXIStreamIO
import chisel3.util.log2Up
import chisel3.experimental.FixedPoint
import fft.FFTParams
import scala.io.Source

/*
 The input is a set of 32 frames 512 in length. At 16kHz sampling rate 32 frames equal roughly a second.
 The first 257 samples of each frames are to be considered and the rest thrown away, as it is a mirror 
 image of the first part. Also, only the real part is considered, and the imaginary part is ignored.
 The mel filters are a set of 20 filters, which represents a 20 x 257 sparse matrix to be multiplied by
 the corresponding real input FFT.
	
	                                                +----------+
	                                                |          |
	                                    +---------->|          |             +-------+     
	 fftIn               +---------+    |           | melMul0  +------------>|  acc0 |---->|
	---------+---------->|         |    |   +------>|          |             +-------+     |
	         |           |         |    |   |       |          |                           |
	         |           |squareMul+----+---+       +----------+                           |   +-------+
	         |           |         |    |   |                                              |-->| log2  |--> outStream
	         +---------->|         |    |   |                                              |   +-------+
	                     +---------+    |   |       +----------+                           |
	                                    |   +------>|          |                           |
	                                    |           |          |             +-------+     |
	     +-----------------+            |           | melMul1  +------------>|  acc1 |---->|
	     |                 |            |           |          |             +-------+
	     |  melFiltersROM  +------------+---------->|          |
	     |                 |                        +----------+
	     |                 |
	     |                 |
	     |                 |
	     |                 |
	     |                 |
	     +-----------------+
	
*/
class MelEngine(fftParams: FFTParams[FixedPoint], numMels:Int, numFrames:Int) 
extends Module {
  val io = IO(new Bundle {
    val fftIn = Flipped(Decoupled(fftParams.protoIQstages(log2Up(fftParams.numPoints) -1)))
    val lastFft = Input(Bool())

    val outStream = new AXIStreamIO(SInt())
  })

  val numElements = fftParams.numPoints
  val numRealElements = (fftParams.numPoints / 2) + 1

  val melFiltersHex = Source.fromResource("melFilters.hex").getLines().mkString("\n")
  val melFiltersEndings = Source.fromResource("melIndex.txt").getLines().toList // rom addresss where filters end
  val melFiltersROM = Module(MemoryGenerator.SRAMInitFromString(hexStr=melFiltersHex))
  
  val nextMel = Wire(Bool())
  val nextEnding = Wire(UInt())
  
  val squareMul = Module(new dspMul[SInt, UInt](SInt(io.fftIn.bits.real.getWidth.W), 
                                                SInt(io.fftIn.bits.real.getWidth.W), 
                                                UInt((2 * io.fftIn.bits.real.getWidth).W)))
  // U(29,0) x U(0,16) = U(29,16) 
  val melMul0 = Module(new dspMul[UInt, UInt](UInt((2 * io.fftIn.bits.real.getWidth).W), 
                                              UInt(16.W), 
                                              UInt((2 * io.fftIn.bits.real.getWidth + 16).W)))
  val melMul1 = Module(new dspMul[UInt, UInt](UInt((2 * io.fftIn.bits.real.getWidth).W), 
                                              UInt(16.W), 
                                              UInt((2 * io.fftIn.bits.real.getWidth + 16).W)))
  val acc0 = Module(new accumulatorWithValid(width=132, inWidth=(2 * io.fftIn.bits.real.getWidth + 16)))
  val acc1 = Module(new accumulatorWithValid(width=132, inWidth=(2 * io.fftIn.bits.real.getWidth + 16)))
  val (elemCntValue, elemCntWrap) = Counter(io.fftIn.valid, numElements)
  val (melCntValue, melCntWrap) = Counter(nextMel, numMels)
  val (frameCntValue, frameCntWrap) = Counter(melCntWrap, numFrames)
  
  /////////////////////////////
  /// ARITHMETIC PIPELINE   ///
  /////////////////////////////
  squareMul.io.inp0.bits := io.fftIn.bits.real.asUInt.asSInt 
  squareMul.io.inp1.bits := io.fftIn.bits.real.asUInt.asSInt 
  squareMul.io.inp0.valid := io.fftIn.valid && io.fftIn.ready // ready is always asserted
  squareMul.io.inp1.valid := io.fftIn.valid && io.fftIn.ready // ready is always asserted

  melMul0.io.inp0 <> RegNext(squareMul.io.out)
  melMul0.io.inp1.bits := melFiltersROM.io.read.data(16,0).asUInt // mel coefficients are 16bit - 2 fit in a 32bit row of ROM
  melMul0.io.inp1.valid := true.B
  
  melMul1.io.inp0 <> RegNext(squareMul.io.out)
  melMul1.io.inp1.bits := melFiltersROM.io.read.data(31,16).asUInt
  melMul1.io.inp1.valid := true.B
  
  acc0.io.in <> melMul0.io.out
  acc1.io.in <> melMul1.io.out
  val actRes = Mux(RegNext(RegNext(melCntValue(0))), acc1.io.out, acc0.io.out) // log(xy)=log(x)+log(y)
  val logRes = Log2(actRes)

  // TODO: Does this hold for when fftParams.keepMSBorLSB not all true?
  require(fftParams.keepMSBorLSB.reduce(_ && _))
  //val shiftFromInputs = 2 * fftParams.expandLogic.reduce(_ + _) // The 2 * comes from the squareMul
  val res = logRes.asSInt - (16+24).S // TODO: Make this automatic by using fixed-point
  dontTouch(res)
  dontTouch(logRes)
  /////////////////////////////
  /// CONTROL CIRCUITS      ///
  /////////////////////////////
  nextMel := elemCntValue === nextEnding
  acc0.io.reset := RegNext(RegNext(!melCntValue(0) && elemCntValue === nextEnding))
  acc1.io.reset := RegNext(RegNext(melCntValue(0) && elemCntValue === nextEnding))

  nextEnding := MuxLookup(melCntValue, 0.U, melFiltersEndings.zipWithIndex.map(x => (x._2.U) -> (x._1.toInt.U)))

  melFiltersROM.io.read.address := elemCntValue
  melFiltersROM.io.write.address := 0.U
  melFiltersROM.io.write.data := 0.U
  melFiltersROM.io.read.enable  := true.B
  melFiltersROM.io.write.enable  := false.B

  io.outStream.valid := RegNext(RegNext(elemCntValue === nextEnding))
  io.outStream.bits := res
  io.outStream.last := RegNext(RegNext(elemCntValue === (numRealElements - 1).U && frameCntValue === (numFrames - 1).U))
  io.fftIn.ready := true.B
}


class accumulatorWithValid(width: Int, inWidth: Int) extends Module {
	val io = IO(new Bundle {
		val in = Flipped(Valid(UInt(inWidth.W)))
		val out = UInt(width.W)
		val reset = Input(Bool())
	})  
	val acc = RegInit(0.U(width.W))
	when (io.reset) {
		acc := 0.U
	}.elsewhen (io.in.valid) {
		acc := acc + io.in.bits
	}
	io.out := acc
}

class dspMul[I <: Bits with Num[I], O <: Bits](genI0: I, 
                                               genI1: I, 
									                             genO: O) 
extends Module {
  val io = IO(new Bundle {
    val inp0 = Flipped(Valid(genI0))
    val inp1 = Flipped(Valid(genI1))
    val out = Valid(genO)
  })

  io.out.valid := io.inp0.valid && io.inp1.valid
  io.out.bits := (io.inp0.bits * io.inp1.bits).asTypeOf(io.out.bits)
}
