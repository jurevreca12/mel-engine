package melengine

import chisel3._
import chisel3.util._
import melengine.utils.toBinaryB
import memories.MemoryGenerator
import chisel3.experimental.FixedPoint
import org.slf4j.LoggerFactory
import scala.collection.mutable.ArrayBuffer
import interfaces.amba.axis.AXIStream

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

class MelEngine(fftSize: Int, numMels: Int, numFrames: Int, melFilters: Seq[Float], genIn: => FixedPoint) extends Module {
  def convertMelsToHex(fftSize: Int, numMels: Int, melFilters: Seq[Float]): (String, List[Int]) = {
    val filterSize = (fftSize / 2) + 1
    // We group the filters into a two dimensional array
    val melArray = melFilters.grouped(filterSize).toSeq
    // and then we get only the elements greater than zero, together with their indecies
    val filters = melArray.map(_.zipWithIndex).map(_.partition(_._1 > 0.0)._1)
    val melIndecies = filters.map(_.last._2).toList
    require(melIndecies.length == numMels)
    // we split the filters in two that will together fill the melFilterROM
    val (even, odd) = filters.zipWithIndex.partition(_._2 % 2 == 0)
    val evenVals = even.map(_._1).flatten.toSeq
    val oddVals = odd.map(_._1).flatten.toSeq
    val compressedValues = ArrayBuffer.fill(2*filterSize)(0.0).grouped(filterSize).toSeq
    for ((value, index) <- evenVals) {
      compressedValues(0)(index) = value  
    }
    for ((value, index) <- oddVals) {
      compressedValues(1)(index) = value  
    }
    val quantVals = compressedValues.map(_.map(_.F(16.W, 14.BP)))
    var hexStr = ""
    for (n <- 0 until filterSize) {
      val v0 = quantVals(0)(n).litToDouble
      val v1 = quantVals(1)(n).litToDouble
      val s0 = toBinaryB(quantVals(0)(n).litValue, digits=16)
      val s1 = toBinaryB(quantVals(1)(n).litValue, digits=16)
      hexStr += s"$s1$s0 // ${v1}, ${v0}\n"
    }
    (hexStr, melIndecies)
  }
  val logger = LoggerFactory.getLogger("MelEngine")

  val io = IO(new Bundle {
    val inStream = Flipped(AXIStream(genIn))
    val outStream = AXIStream(UInt(8.W))
  })
  val accumulatorWidth = 128
  val (filtersHex, filterEnds) = convertMelsToHex(fftSize, numMels, melFilters)
  val melFiltersROM = Module(MemoryGenerator.SRAMInitFromString(hexStr=filtersHex, isBinary = true, noWritePort = true))
  
  val nextMel = Wire(Bool())
  val nextEnding = Wire(UInt()) 
  val (elementCounter, elementCounterWrap) = Counter(0 until fftSize, io.inStream.fire)
  val (melCounter, melCounterWrap) = Counter(0 until numMels, nextMel)
  val (frameCounter, frameCounterWrap) = Counter(0 until numFrames, melCounterWrap)

  val fftPlusOne = io.inStream.bits + 1.0.F(io.inStream.bits.binaryPoint)
  val squared = RegNext(fftPlusOne * fftPlusOne)
  dontTouch(squared)

  val paddedFilter0 = Wire(FixedPoint(16.W, 14.BP))
  val paddedFilter1 = Wire(FixedPoint(16.W, 14.BP))
  paddedFilter0 := melFiltersROM.io.read.data(15,0).asTypeOf(paddedFilter0)
  paddedFilter1 := melFiltersROM.io.read.data(31,16).asTypeOf(paddedFilter1)
  val melsValues0 = squared * paddedFilter0
  val melsValues1 = squared * paddedFilter1
  val acc0 = accumulatorWithValid(in=melsValues0, valid=RegNext(io.inStream.fire), accWidth=accumulatorWidth)
  val acc1 = accumulatorWithValid(in=melsValues1, valid=RegNext(io.inStream.fire), accWidth=accumulatorWidth)
  val activeAccumulator = Mux(RegNext(RegNext(melCounter(0))), acc1, acc0)
  val hi = accumulatorWidth - 1
  val lo = melsValues0.binaryPoint.get
  val logOfAccumulator = Log2(activeAccumulator(hi, lo))

  /////////////////////////////
  /// CONTROL CIRCUITS      ///
  /////////////////////////////
  nextMel := elementCounter === nextEnding
  val preReset0 = RegNext(!melCounter(0) && elementCounter === nextEnding)
  val preReset1 = RegNext(melCounter(0) && elementCounter === nextEnding)
  when(RegNext(preReset0)) { acc0 := melsValues0 }
  when(RegNext(preReset1)) { acc1 := melsValues1 }
  nextEnding := MuxLookup(melCounter, 0.U, filterEnds.zipWithIndex.map(x => (x._2.U) -> (x._1.toInt.U)))

  melFiltersROM.io.read.address := elementCounter
  melFiltersROM.io.read.enable  := true.B

  require(logOfAccumulator.getWidth <= 8, s"Output port set to 8-bits.")
  io.outStream.valid := RegNext(RegNext(elementCounter === nextEnding))
  io.outStream.bits := logOfAccumulator
  io.outStream.last := RegNext(RegNext(elementCounter === (fftSize / 2).U && frameCounter === (numFrames - 1).U))
  io.inStream.ready := true.B
}

object accumulatorWithValid {
  def apply(in: FixedPoint, valid: Bool, accWidth: Int): FixedPoint = {
    val acc = RegInit(FixedPoint(value=BigInt(0), width=accWidth.W, binaryPoint=in.binaryPoint))
    when(valid) {
      acc := acc + in
    }
    acc
  }
}
