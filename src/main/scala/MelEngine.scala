package melengine

import chisel3._
import chisel3.util._
import memories.MemoryGenerator
import interfaces.amba.axis.AXIStreamIO
import fft.FFTParams
import scala.io.Source
import chisel3.experimental.FixedPoint

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

    val outStream = new AXIStreamIO(UInt())
  })
  val numElements = fftParams.numPoints
  val numRealElements = (fftParams.numPoints / 2) + 1
  val accumulatorWidth = 128
  val melFiltersHex = Source.fromResource("melFilters.hex").getLines().mkString("\n")
  val melFiltersEndings = Source.fromResource("melIndex.txt").getLines().toList // rom addresss where filters end
  val melFiltersROM = Module(MemoryGenerator.SRAMInitFromString(hexStr=melFiltersHex))
  
  val nextMel = Wire(Bool())
  val nextEnding = Wire(UInt()) 
  val (elemCntValue, elemCntWrap) = Counter(io.fftIn.valid, numElements)
  val (melCntValue, melCntWrap) = Counter(nextMel, numMels)
  val (frameCntValue, frameCntWrap) = Counter(melCntWrap, numFrames)

  val fftPlusOne = io.fftIn.bits.real + 1.0.F(0.BP)
  val squared = fftPlusOne * fftPlusOne
  dontTouch(squared)

  val paddedFilter0 = Wire(FixedPoint(17.W, 16.BP))
  val paddedFilter1 = Wire(FixedPoint(17.W, 16.BP))
  paddedFilter0 := melFiltersROM.io.read.data(15,0).zext.asTypeOf(paddedFilter0)
  paddedFilter1 := melFiltersROM.io.read.data(31,16).zext.asTypeOf(paddedFilter1)
  val melsValues0 = RegNext(squared) * paddedFilter0
  val melsValues1 = RegNext(squared) * paddedFilter1
  val acc0 = Module(new accumulatorWithValid(width=accumulatorWidth, genIn=melsValues0.cloneType))
  val acc1 = Module(new accumulatorWithValid(width=accumulatorWidth, genIn=melsValues1.cloneType))
  acc0.io.in.bits := melsValues0
  acc0.io.in.valid := RegNext(io.fftIn.valid)
  acc1.io.in.bits := melsValues1
  acc1.io.in.valid := RegNext(io.fftIn.valid)
  val activeAccumulator = Mux(RegNext(RegNext(melCntValue(0))), acc1.io.out, acc0.io.out) 
  val hi = accumulatorWidth - 1
  val lo = melsValues0.binaryPoint.get
  val logOfAccumulator = Log2(activeAccumulator(hi, lo)) 

  /////////////////////////////
  /// CONTROL CIRCUITS      ///
  /////////////////////////////
  nextMel := elemCntValue === nextEnding
  acc0.reset := RegNext(RegNext(!melCntValue(0) && elemCntValue === nextEnding))
  acc1.reset := RegNext(RegNext(melCntValue(0) && elemCntValue === nextEnding))
  nextEnding := MuxLookup(melCntValue, 0.U, melFiltersEndings.zipWithIndex.map(x => (x._2.U) -> (x._1.toInt.U)))

  melFiltersROM.io.read.address := elemCntValue
  melFiltersROM.io.write.address := 0.U
  melFiltersROM.io.write.data := 0.U
  melFiltersROM.io.read.enable  := true.B
  melFiltersROM.io.write.enable  := false.B

  io.outStream.valid := RegNext(RegNext(elemCntValue === nextEnding))
  io.outStream.bits := logOfAccumulator
  io.outStream.last := RegNext(RegNext(elemCntValue === (numRealElements - 1).U && frameCntValue === (numFrames - 1).U))
  io.fftIn.ready := true.B
}


class accumulatorWithValid(width: Int, genIn: => FixedPoint) extends Module {
	val io = IO(new Bundle {
		val in = Flipped(Valid(genIn))
		val out = FixedPoint()
	})  
	val acc = RegInit(FixedPoint(value=BigInt(0), width=width.W, binaryPoint=genIn.binaryPoint))
	when (Module.reset.asBool) {
		acc := 0.F(genIn.binaryPoint)
	}.elsewhen (io.in.valid) {
		acc := acc + io.in.bits
	}
	io.out := acc
}



