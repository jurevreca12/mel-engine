// See README.md for license details.

package spi2axis

import chisel3._


class SPIBundle extends Bundle {
  val CS    = Output(Bool())
  val SDATA = Input (UInt(1.W))
  val SCLK  = Output(Bool())
}


class SPItoAXIStreamMaster extends Module {
  val io = IO(new Bundle {
    val spi  = new SPIBundle
    val axis = new Irrevocable(Output(UInt(32.W)))
  })

  val clockCnt    = RegInit(0.U(8.W))
  val clockCntToCS = 1000000 / 16000
  
  object spiState extends ChiselEnum {
    val sIDLE     = Value(0.U)
    val sWRITESPI = Value(1.U)
  }
}
