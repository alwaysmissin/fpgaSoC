package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class GPIOIO extends Bundle {
  val out = Output(UInt(16.W))
  val in = Input(UInt(16.W))
  // val seg = Output(Vec(8, UInt(8.W)))
}

class GPIOCtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val gpio = new GPIOIO
}

class gpio_top_apb extends BlackBox {
  val io = IO(new GPIOCtrlIO)
}

class BitsToSeg extends Module {
  val io = IO(new Bundle{
    val in = Input(UInt(4.W))
    val seg = Output(UInt(8.W))
  })
  io.seg := MuxCase("b11111111".U, Array(
    (io.in === 0.U)  -> "b00000011".U,
    (io.in === 1.U)  -> "b10011111".U,
    (io.in === 2.U)  -> "b00100101".U,
    (io.in === 3.U)  -> "b00001101".U,
    (io.in === 4.U)  -> "b10011001".U,
    (io.in === 5.U)  -> "b01001001".U,
    (io.in === 6.U)  -> "b01000001".U,
    (io.in === 7.U)  -> "b00011111".U,
    (io.in === 8.U)  -> "b00000001".U,
    (io.in === 9.U)  -> "b00001001".U,
    (io.in === 10.U) -> "b00010001".U,
    (io.in === 11.U) -> "b11000001".U,
    (io.in === 12.U) -> "b01100011".U,
    (io.in === 13.U) -> "b10000101".U,
    (io.in === 14.U) -> "b01100001".U,
    (io.in === 15.U) -> "b01110001".U
  ))
}

class gpioChisel extends Module {
  val io = IO(new GPIOCtrlIO)
  val gpioInReg = RegInit(0.U(16.W))
  val gpioOutReg = RegInit(0.U(16.W))
  val segReg = RegInit(0x12345678.U(32.W))
  io.gpio.out <> gpioOutReg
//  io.gpio.seg <> segReg
  gpioInReg := io.gpio.in
  // for (i <- 0 until 8){
  //   val seg = Module(new BitsToSeg)
  //   seg.io.in := segReg(i*4+3, i*4)
  //   io.gpio.seg(i) := seg.io.seg
  // }
  val writeMask = Cat(Fill(8, io.in.pstrb(3)), Fill(8, io.in.pstrb(2)), Fill(8, io.in.pstrb(1)), Fill(8, io.in.pstrb(0)))
  val pready = RegInit(false.B)
  val prdata = RegInit(0.U(32.W))
  io.in.pready <> pready
  io.in.prdata <> prdata
  io.in.pslverr := false.B
  val addr = io.in.paddr & 0xF.U


  when(io.in.psel){
    pready := true.B
    when(io.in.pwrite){
      io.in.prdata := 0.U
      switch(addr){
        is (0.U){
          gpioOutReg := (gpioOutReg & (~writeMask).asUInt ) | (io.in.pwdata & writeMask)
        }
        is (4.U){
          gpioInReg := (gpioInReg & (~writeMask).asUInt ) | (io.in.pwdata & writeMask)
        }
        is (8.U){
          segReg := (segReg & (~writeMask).asUInt ) | (io.in.pwdata & writeMask)
        }
      }
    } otherwise {
      switch(addr){
        is (0.U){
          prdata := Cat(0.U(16.W), gpioOutReg)
        }
        is (4.U){
          prdata := Cat(0.U(16.W), gpioInReg)
        }
        is (8.U){
          prdata := segReg
        }
      }
    }
  } otherwise {
    pready := false.B
  }
}

class APBGPIO(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val gpio_bundle = IO(new GPIOIO)

    val mgpio = Module(new gpioChisel)
    mgpio.io.clock := clock
    mgpio.io.reset := reset
    mgpio.io.in <> in
    gpio_bundle <> mgpio.io.gpio
  }
}
